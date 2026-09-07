package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.domain.workout.StructuredWorkout;
import br.com.menthoros.backend.entity.IntegracaoExterna;
import br.com.menthoros.backend.entity.PlanoSemanal;
import br.com.menthoros.backend.entity.TreinoPlanejado;
import br.com.menthoros.backend.events.PlanoAprovadoEvent;
import br.com.menthoros.backend.repository.IntegracaoExternaRepository;
import br.com.menthoros.backend.repository.PlanoSemanalRepository;
import br.com.menthoros.backend.repository.TreinoPlanejadoRepository;
import br.com.menthoros.backend.services.IntervalsIcuConnectionService;
import br.com.menthoros.backend.services.WorkoutChannel;
import br.com.menthoros.backend.services.impl.IntervalsIcuPushProcessor.ProcessamentoResultado;
import br.com.menthoros.backend.services.impl.IntervalsIcuPushProcessor.ResultadoPush;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Escuta {@link PlanoAprovadoEvent} e empurra os treinos exportáveis do plano para o intervals.icu,
 * um treino por vez, com claim atômico e reconciliação de eventos órfãos ao final.
 *
 * <p>Roda fora da transação de aprovação (AFTER_COMMIT): a aprovação do plano nunca falha por
 * causa do push.
 *
 * <p><b>Fronteira transacional (hardening):</b> este método NÃO abre transação própria — é um
 * orquestrador. Cada treino é processado em transações curtas e independentes dentro de
 * {@link IntervalsIcuPushProcessor} (claim e marcação em TXs próprias; HTTP fora de TX), de modo
 * que um claim perdido em um treino jamais arrasta por rollback as marcações dos demais (CA1 da
 * change intervals-icu-push-hardening). As leituras daqui (plano, treinos) usam queries avulsas
 * tenant-scoped; nenhuma coleção lazy é percorrida fora de sessão.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntervalsIcuPushListener {

    private final IntervalsIcuConnectionService connectionService;
    private final IntervalsIcuPushProcessor pushProcessor;
    private final WorkoutChannel workoutChannel;
    private final PlanoSemanalRepository planoSemanalRepository;
    private final TreinoPlanejadoRepository treinoPlanejadoRepository;
    private final IntegracaoExternaRepository integracaoExternaRepository;

    /**
     * Processa o push de todos os treinos exportáveis do plano aprovado para o intervals.icu.
     *
     * <p><b>Idempotente:</b> YES — cada execução reclama (claim) apenas treinos ainda não
     * assumidos por outro worker e re-envia o mesmo {@code externalId} determinístico; re-executar
     * após falha parcial converge para o mesmo estado final (upsert no canal externo).
     * <p><b>Side Effects:</b> chamada HTTP externa (push, nudge anti-debounce e remoção de órfãos)
     * + atualização de {@link TreinoPlanejado} (via processor, em TXs próprias) e, quando houver
     * ao menos um push bem-sucedido no lote, de {@link IntegracaoExterna#getUltimaSincronizacao()};
     * em erro de autenticação, de {@link IntegracaoExterna#getLastSyncError()}.
     * <p><b>Tenant-aware:</b> YES — todas as queries usam {@code event.tenantId()}; um treino cujo
     * tenant não bate com o evento é ignorado com log de segurança.
     *
     * @param event evento de aprovação do plano
     */
    @Async("intervalsIcuPushExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPlanoAprovado(PlanoAprovadoEvent event) {
        UUID planoId = event.planoId();
        UUID atletaId = event.atletaId();
        UUID tenantId = event.tenantId();

        Optional<IntegracaoExterna> conexaoOpt = connectionService.conexaoAtiva(atletaId, tenantId);
        if (conexaoOpt.isEmpty()) {
            log.info("Push intervals.icu ignorado: sem conexão ativa. planoId={}, atletaId={}", planoId, atletaId);
            return;
        }
        IntegracaoExterna conexao = conexaoOpt.get();

        // Janela da semana e lista de treinos por queries avulsas tenant-scoped — o reload fresco
        // de cada treino acontece DENTRO da TX própria do processor, nunca aqui.
        PlanoSemanal plano = planoSemanalRepository.findByIdAndTenantId(planoId, tenantId).orElse(null);
        if (plano == null) {
            log.warn("Push intervals.icu abortado: plano {} não encontrado no tenant {}", planoId, tenantId);
            return;
        }
        List<TreinoPlanejado> treinos = treinoPlanejadoRepository.findAllByPlanoSemanalIdAndTenantId(planoId, tenantId);
        if (treinos.isEmpty()) {
            log.warn("Push intervals.icu abortado: plano {} não tem treinos planejados no tenant {}", planoId, tenantId);
            return;
        }

        Set<String> externalIdsAtuais = new HashSet<>();
        ResultadoLote lote = new ResultadoLote();

        for (TreinoPlanejado treinoOrigem : treinos) {
            try {
                processarTreino(treinoOrigem, tenantId, conexao, externalIdsAtuais, lote);
            } catch (Exception e) {
                // Regra 6: erro em um treino não pode abortar o processamento dos demais.
                log.error("Erro inesperado ao processar push do treino {}: {}",
                        treinoOrigem.getId(), e.getMessage(), e);
            }
        }

        boolean precisaSalvarConexao = false;
        if (lote.algumPushComSucesso) {
            conexao.setUltimaSincronizacao(Instant.now());
            precisaSalvarConexao = true;
        }
        if (lote.autenticacaoFalhou) {
            conexao.setLastSyncError("Falha de autenticação intervals.icu ao sincronizar plano " + planoId);
            precisaSalvarConexao = true;
        }
        if (precisaSalvarConexao) {
            integracaoExternaRepository.save(conexao);
        }

        workoutChannel.removerOrfaos(conexao, plano.getSemanaInicio(), plano.getSemanaFim(), externalIdsAtuais);

        // Nudge anti-debounce (CA2): DEPOIS de removerOrfaos de propósito — é a última palavra do
        // lote. Só dispara com 2+ eventos CRIADOS (rajada), no ÚLTIMO deles; best-effort, nunca
        // altera o estado de nenhum treino.
        // Invariante: criados só incrementa no MESMO bloco que seta ultimoEventoCriado e
        // ultimoExternalIdCanonico — criados >= 2 implica ambos não-nulos.
        if (lote.criados >= 2) {
            try {
                workoutChannel.tocarEvento(conexao, lote.ultimoEventoCriado, lote.ultimoExternalIdCanonico);
            } catch (Exception e) {
                log.warn("Nudge anti-debounce falhou para o plano {} (best-effort, sem impacto nos treinos): {}",
                        planoId, e.getMessage());
            }
        }
    }

    private void processarTreino(TreinoPlanejado treinoOrigem, UUID tenantId, IntegracaoExterna conexao,
                                  Set<String> externalIdsAtuais, ResultadoLote lote) {
        UUID treinoId = treinoOrigem.getId();

        // Regra 9: tenant do treino (herdado do plano) deve bater com o tenant do evento.
        if (!IntervalsIcuPushProcessor.tenantValido(treinoId, tenantId, treinoOrigem.getTenantId())) {
            return;
        }

        ResultadoPush resultado = pushProcessor.processar(treinoId, tenantId, conexao);

        // Regra 4: treino não exportável não entra no conjunto de externalIds atuais — o antigo
        // evento, se houver, vira órfão e a reconciliação o deleta (o reset do vínculo local
        // acontece dentro do processor, na mesma TX do reload). Treino que sumiu entre o evento
        // e o processamento (deleção concorrente) também fica fora do set.
        if (resultado.tipo() == ProcessamentoResultado.NAO_EXPORTAVEL
                || resultado.tipo() == ProcessamentoResultado.NAO_ENCONTRADO) {
            return;
        }

        if (resultado.tipo() == ProcessamentoResultado.PROCESSADO_ERRO_AUTENTICACAO) {
            lote.autenticacaoFalhou = true;
        }
        if (resultado.tipo() == ProcessamentoResultado.PROCESSADO_SUCESSO) {
            lote.algumPushComSucesso = true;
            if (resultado.criadoNovo()) {
                lote.criados++;
                lote.ultimoEventoCriado = resultado.eventId();
                lote.ultimoExternalIdCanonico = StructuredWorkout.externalIdCanonico(treinoId);
            }
        }

        // Regra 7: o external_id do evento no intervals.icu é SEMPRE o canônico
        // "menthoros-<treinoId>". TODO treino exportável entra no set — inclusive claim perdido
        // e erro no push: o evento antigo dele continua válido e não pode ser reconciliado como
        // órfão.
        externalIdsAtuais.add(StructuredWorkout.externalIdCanonico(treinoId));
    }

    /** Flags e contadores agregados do lote de pushes de um plano, acumulados treino a treino. */
    private static final class ResultadoLote {
        private boolean autenticacaoFalhou;
        private boolean algumPushComSucesso;
        private int criados;
        private Long ultimoEventoCriado;
        private String ultimoExternalIdCanonico;
    }
}
