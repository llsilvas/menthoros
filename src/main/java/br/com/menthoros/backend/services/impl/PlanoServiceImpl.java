package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.dto.llm.PlanoSemanalLlmDto;
import br.com.menthoros.backend.dto.output.PlanoSemanalOutputDto;
import br.com.menthoros.backend.dto.output.TreinoPlanejadoOutputDto;
import br.com.menthoros.backend.entity.AnaliseWorkout;
import br.com.menthoros.backend.entity.PlanoSemanal;
import br.com.menthoros.backend.entity.TreinoRealizado;
import br.com.menthoros.backend.enums.AnaliseStatus;
import br.com.menthoros.backend.enums.ModoGeracaoPlano;
import br.com.menthoros.backend.enums.PlanoReviewStatus;
import br.com.menthoros.backend.enums.PlanoStatus;
import br.com.menthoros.backend.events.PlanoDeletadoEvent;
import br.com.menthoros.backend.exception.DomainNotFoundException;
import br.com.menthoros.backend.exception.DomainRuleViolationException;
import br.com.menthoros.backend.exception.LLMException;
import br.com.menthoros.backend.exception.PlanoJaExistenteException;
import br.com.menthoros.backend.exception.ResourceNotFoundException;
import br.com.menthoros.backend.mapper.PlanoSemanalMapper;
import br.com.menthoros.backend.repository.AiWorkoutAnalysisRepository;
import br.com.menthoros.backend.repository.PlanoSemanalRepository;
import br.com.menthoros.backend.repository.TreinoRealizadoRepository;
import br.com.menthoros.backend.services.IaService;
import br.com.menthoros.backend.services.PlanoService;
import br.com.menthoros.backend.services.helper.LlmConcurrencyLimiter;
import br.com.menthoros.backend.services.helper.PlanGenerationContext;
import br.com.menthoros.backend.services.helper.PlanGenerationContextLoader;
import br.com.menthoros.backend.services.helper.PlannerShadowService;
import br.com.menthoros.backend.domain.planner.WeekPlanSkeleton;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import br.com.menthoros.backend.services.helper.PlanGenerationPersister;
import br.com.menthoros.backend.multitenancy.TenantContext;
import br.com.menthoros.backend.config.core.WorkoutAnalysisProperties;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@Service
public class PlanoServiceImpl implements PlanoService {

    private final IaService iaService;
    private final LlmConcurrencyLimiter llmConcurrencyLimiter;
    private final PlanGenerationContextLoader contextLoader;
    private final PlanGenerationPersister persister;
    private final PlanoSemanalRepository planoSemanalRepository;
    private final TreinoRealizadoRepository treinoRealizadoRepository;
    private final PlanoSemanalMapper planoSemanalMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final AiWorkoutAnalysisRepository aiWorkoutAnalysisRepository;
    private final WorkoutAnalysisProperties workoutAnalysisProperties;
    private final PlannerShadowService plannerShadowService;

    // planner-engine-enforcement §3: com enabled=true, o WeekPlanSkeleton é computado ANTES do prompt
    // e injetado como bloco mandatório. Default false — rollout gated (tasks 8.4).
    @Value("${planner-engine.enabled:false}")
    private boolean plannerEnabled;

    /**
     * Idempotent: YES — leitura pura.
     * Side Effects: NONE.
     * Tenant-aware: YES — usa TenantContext.getRequiredTenantId().
     */
    @Override
    @Transactional
    public boolean existePlanoParaSemana(UUID atletaId, ModoGeracaoPlano modoGeracao) {
        LocalDate semanaAlvo = contextLoader.calcularSemanaInicio(atletaId, LocalDate.now(), modoGeracao);
        return planoSemanalRepository.existePlanoAtivoNaSemana(
                atletaId, semanaAlvo, TenantContext.getRequiredTenantId());
    }

    /**
     * Gera um plano de treino semanal personalizado para um atleta usando IA, em três fases
     * (refactor-llm-call-outside-transaction, design.md D1):
     * <ol>
     *   <li>{@link PlanGenerationContextLoader#load} — transação curta de leitura</li>
     *   <li>chamada ao LLM — <b>sem transação</b>: nenhuma conexão do pool em posse</li>
     *   <li>{@link PlanGenerationPersister#persist} — transação curta de escrita</li>
     * </ol>
     *
     * <p>Deliberadamente SEM {@code @Transactional} (nem aqui nem na interface): a fronteira é o
     * ganho da change. Isso também é o que permite capturar a
     * {@link DataIntegrityViolationException} do commit da fase 3 e traduzi-la (design.md D3).
     *
     * <p>Contrato de erro preservado: exceções de domínio da fase 1 ({@code DomainNotFound},
     * {@code DomainRuleViolation}, {@code PlanoJaExistente}) e {@code IllegalStateException}
     * propagam como antes; {@link DataIntegrityViolationException} também propaga (antes ela
     * nascia no commit do proxy, fora deste método, e chegava crua ao handler 409 — continua
     * chegando), exceto a do índice de plano ativo, que vira {@code PlanoJaExistenteException};
     * o resto das fases 2 e 3 vira {@link LLMException}.
     *
     * Idempotent: NÃO — cria o plano; duplicata bloqueada pela checagem cedo, pela re-checagem e
     *   pelo índice parcial da V52.
     * Side Effects: fase 1 pode inserir metadados; fase 3 persiste plano e atualiza metadados.
     * Tenant-aware: YES.
     *
     * @throws DomainNotFoundException       atleta inexistente ou de outro tenant
     * @throws DomainRuleViolationException  atleta inválido, plano duplicado ou sem treinos
     * @throws LLMException                  falha do modelo, resposta inválida ou erro inesperado
     */
    @Override
    public PlanoSemanal gerarPlanoTreino(UUID atletaId, ModoGeracaoPlano modoGeracao) {
        validarParametrosEntrada(atletaId, modoGeracao);

        try {
            PlanGenerationContext ctx = contextLoader.load(atletaId, modoGeracao);

            PlanoSemanalLlmDto planoDto = gerarPlanoSemanal(ctx, modoGeracao);

            if (planoDto == null) {
                throw new LLMException("Falha ao gerar plano: IA retornou resposta nula. Tente novamente.");
            }

            return persister.persist(planoDto, ctx, modoGeracao);
        } catch (LLMException | DomainRuleViolationException | DomainNotFoundException | IllegalStateException e) {
            log.error("Erro de domínio ao gerar plano para atleta {}: {}", atletaId, e.getMessage());
            throw e;
        } catch (DataIntegrityViolationException e) {
            // Duas geracoes passaram pelas checagens e commitaram juntas: o indice da V52 decidiu.
            // Qualquer outra constraint segue como conflito generico (design.md D3).
            if (PlanoJaExistenteException.causadaPeloIndiceDePlanoAtivo(e)) {
                log.warn("Geração concorrente para atleta {} perdeu a corrida no índice {}",
                        atletaId, PlanoJaExistenteException.INDICE_PLANO_ATIVO);
                throw PlanoJaExistenteException.paraCorridaNoIndice(atletaId);
            }
            throw e;
        } catch (IllegalArgumentException e) {
            log.error("Erro de validação ao gerar plano para atleta {}: {}", atletaId, e.getMessage());
            throw new LLMException("Erro ao gerar plano semanal: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Erro inesperado ao gerar plano para atleta {}", atletaId, e);
            throw new LLMException("Erro inesperado ao gerar plano. Por favor, tente novamente.", e);
        }
    }

    /**
     * Computa o {@link WeekPlanSkeleton} pré-prompt quando {@code planner-engine.enabled=true}.
     * Fail-open (design Decisão 3): qualquer falha aqui NÃO derruba a geração — devolve {@code null}
     * e o prompt cai no caminho legado. Onboarding vazio: o planner usa os dias disponíveis do atleta.
     */
    @Nullable
    private WeekPlanSkeleton computarSkeletonSeHabilitado(PlanGenerationContext ctx) {
        if (!plannerEnabled) {
            return null;
        }
        try {
            return plannerShadowService.computarSkeleton(
                    ctx.dados(), ctx.decisaoProgressao(), ctx.semanaInicio(), Optional.empty());
        } catch (Exception e) {
            log.warn("Falha ao computar skeleton do planner (fail-open, seguindo sem ele): {}", e.getMessage());
            return null;
        }
    }

    private PlanoSemanalLlmDto gerarPlanoSemanal(PlanGenerationContext ctx, ModoGeracaoPlano modoGeracao) {
        UUID atletaId = ctx.atleta().getId();
        try {
            log.info("Iniciando geração de plano para atleta: {}", atletaId);

            // planner-engine-enforcement §3: skeleton prescritivo computado ANTES do prompt (fail-open).
            WeekPlanSkeleton skeleton = computarSkeletonSeHabilitado(ctx);

            // Faixa interativa do limiter, só em volta da chamada ao LLM (nunca das transações).
            // Para gerações vindas do lote é no-op: a thread já segura permits (reentrância).
            PlanoSemanalLlmDto planoDto = llmConcurrencyLimiter.executarInterativo(() ->
                    iaService.geraPlanoSemanalAvancado(
                            ctx.atleta(), ctx.metaDados(), ctx.proximaProva(), modoGeracao,
                            ctx.decisaoProgressao(), ctx.revisaoConsumida(), ctx.semanaInicio(), skeleton));

            validaPlanoGerado(planoDto);
            return planoDto;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMException("Geração de plano interrompida. Tente novamente.", e);
        } catch (LLMException e) {
            log.error("Falha na IA ao gerar o plano para o atleta: {}", atletaId);
            throw e;
        } catch (Exception e) {
            log.error("Erro inesperado ao gerar o plano para o atleta: {}", atletaId, e);
            throw new LLMException("Erro inesperado ao gerar plano", e);
        }
    }

    private void validaPlanoGerado(PlanoSemanalLlmDto planoDto) {
        if (planoDto == null) {
            throw new LLMException("IA retornou plano nulo");
        }
        if (planoDto.treinosPlanejados() == null || planoDto.treinosPlanejados().isEmpty()) {
            throw new LLMException("IA retornou plano sem treinos");
        }
    }

    private void validarParametrosEntrada(UUID atletaId, ModoGeracaoPlano modoGeracao) {
        Objects.requireNonNull(atletaId, "ID do atleta é obrigatório");
        Objects.requireNonNull(modoGeracao, "Modo de geração é obrigatório");
    }

    @Override
    @Transactional
    public void deletePlanoSemanal(UUID planoSemanalId) {
        // tenant-aware: garante que o plano pertence ao tenant do contexto
        UUID tenantId = TenantContext.getRequiredTenantId();
        PlanoSemanal plano = planoSemanalRepository.findByIdAndTenantId(planoSemanalId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Plano não encontrado: " + planoSemanalId));

        if (plano.getStatus() != PlanoStatus.PLANEJADO) {
            throw new DomainRuleViolationException("Apenas planos ainda não iniciados podem ser excluídos");
        }

        // Plano com status PLANEJADO não tem TreinoRealizado vinculados,
        // mas os bulk updates garantem integridade caso haja dados inconsistentes.
        // clearAutomatically=true limpa o cache L1 do Hibernate evitando CHECK_ON_FLUSH.
        treinoRealizadoRepository.desvinculardeTreinosPlanejados(planoSemanalId);
        treinoRealizadoRepository.desvinculardePlanoSemanal(planoSemanalId);

        // Janela capturada ANTES do delete: o CascadeType.ALL apaga os TreinoPlanejado vinculados,
        // então esses dados não estariam mais disponíveis depois para o listener de limpeza.
        UUID atletaId = plano.getAtleta().getId();
        LocalDate semanaInicio = plano.getSemanaInicio();
        LocalDate semanaFim = plano.getSemanaFim();

        // CascadeType.ALL propaga a exclusão para os TreinoPlanejado vinculados.
        planoSemanalRepository.delete(plano);
        eventPublisher.publishEvent(
                new PlanoDeletadoEvent(planoSemanalId, atletaId, tenantId, semanaInicio, semanaFim));
        log.info("✅ Plano deletado com sucesso - ID: {}", planoSemanalId);
    }

    @Transactional
    @Override
    public PlanoSemanalOutputDto buscarPlanoPorAtleta(UUID atletaId, boolean apenasAprovados) {
        UUID tenantId = TenantContext.getRequiredTenantId();
        PlanoSemanal planoSemanal;

        if (apenasAprovados) {
            // APROVADO ou AGUARDANDO_REVISAO reaberto por prova (prova-no-plano-semanal, D4) —
            // o atleta continua vendo a versão vigente em vez de cair na semana anterior.
            planoSemanal = planoSemanalRepository
                    .findVisiveisParaAtletaOrderBySemanaInicioDesc(atletaId, tenantId)
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Nenhum plano aprovado encontrado para o atleta: " + atletaId));
        } else {
            planoSemanal = planoSemanalRepository
                    .findAtivosPorAtleta(atletaId, tenantId)
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Plano não encontrado para o atleta: " + atletaId));
        }

        Hibernate.initialize(planoSemanal.getTreinosPlanejados());
        double volumeRealizadoKm = calcularVolumeRealizadoKm(
                atletaId, tenantId, planoSemanal.getSemanaInicio(), planoSemanal.getSemanaFim());
        PlanoSemanalOutputDto dto = planoSemanalMapper.toOutputDto(planoSemanal).toBuilder()
                .volumeRealizadoKm(volumeRealizadoKm)
                .build();
        return dto.toBuilder()
                .treinosPlanejados(comFlagDeAnalise(dto.treinosPlanejados(), tenantId))
                .build();
    }

    /**
     * Marca quais treinos têm análise pronta PARA O ATLETA (analise-ia-treino-atleta): bloco do
     * atleta presente ({@code atletaComoFoi != null}), status COMPLETED e kill switch ligado.
     * Uma consulta por plano ({@code findByTreinoRealizadoIdIn}), nunca N.
     */
    private List<TreinoPlanejadoOutputDto> comFlagDeAnalise(List<TreinoPlanejadoOutputDto> treinos, UUID tenantId) {
        if (treinos == null || treinos.isEmpty()) {
            return treinos;
        }
        if (!workoutAnalysisProperties.getAthleteMessage().isEnabled()) {
            return treinos; // flag já nasce false no mapper
        }
        List<UUID> realizadoIds = treinos.stream()
                .map(TreinoPlanejadoOutputDto::treinoRealizadoId)
                .filter(Objects::nonNull)
                .toList();
        if (realizadoIds.isEmpty()) {
            return treinos;
        }
        Set<UUID> comAnalise = aiWorkoutAnalysisRepository
                .findByTreinoRealizadoIdInAndTenantId(realizadoIds, tenantId)
                .stream()
                .filter(a -> a.getStatus() == AnaliseStatus.COMPLETED && a.getAtletaComoFoi() != null)
                .map(AnaliseWorkout::getTreinoRealizadoId)
                .collect(Collectors.toSet());
        if (comAnalise.isEmpty()) {
            return treinos;
        }
        return treinos.stream()
                .map(t -> comAnalise.contains(t.treinoRealizadoId())
                        ? t.toBuilder().analiseAtletaDisponivel(true).build()
                        : t)
                .toList();
    }

    /**
     * Soma a distância dos treinos realizados pelo atleta dentro da janela da semana do plano.
     *
     * <p>Calculado dinamicamente por atletaId + janela de datas (não pela FK
     * {@code TreinoRealizado.planoSemanal}) porque nem todo fluxo de registro de treino
     * (sync Strava, upload .fit, lançamento do coach, reconciliação) vincula essa FK — somar
     * por data evita que esses treinos fiquem invisíveis ao volume realizado.
     *
     * Idempotent: YES — pure read, no side effects.
     * Side Effects: NONE
     * Tenant-aware: YES — query filtra por tenantId explicitamente.
     */
    private double calcularVolumeRealizadoKm(
            UUID atletaId, UUID tenantId, LocalDate semanaInicio, LocalDate semanaFim) {
        // D8 (ingestao-treino-realizado): cancelado não conta na carga — achado do /qa do Bloco 2
        // (Codex adversarial-review, 2026-08-24).
        return treinoRealizadoRepository
                .findByAtletaIdAndTenantIdAndDataTreinoBetween(atletaId, tenantId, semanaInicio, semanaFim)
                .stream()
                .filter(TreinoRealizado::contaNaCarga)
                .map(t -> Optional.ofNullable(t.getDistanciaKm()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .doubleValue();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Optional<PlanoSemanal> findPlanoVigenteRelevante(UUID atletaId, UUID tenantId) {
        return planoSemanalRepository.findMostRecentRelevantPlano(atletaId, tenantId);
    }
}
