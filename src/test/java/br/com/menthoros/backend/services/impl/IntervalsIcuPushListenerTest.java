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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Fronteira transacional (hardening): o {@link IntervalsIcuPushProcessor} deixou de ser
 * construído com mocks internos para virar um {@code @Mock} de classe concreta — o listener agora
 * é um orquestrador puro, sem transação própria; o claim/marcação de cada treino vive nas TXs
 * curtas do processor (fora do escopo deste teste, coberto por {@code IntervalsIcuPushProcessorTest}).
 */
@ExtendWith(MockitoExtension.class)
class IntervalsIcuPushListenerTest {

    @Mock private IntervalsIcuConnectionService connectionService;
    @Mock private IntervalsIcuPushProcessor pushProcessor;
    @Mock private WorkoutChannel workoutChannel;
    @Mock private PlanoSemanalRepository planoSemanalRepository;
    @Mock private TreinoPlanejadoRepository treinoPlanejadoRepository;
    @Mock private IntegracaoExternaRepository integracaoExternaRepository;

    @InjectMocks
    private IntervalsIcuPushListener listener;

    private UUID planoId;
    private UUID atletaId;
    private UUID tenantId;
    private PlanoAprovadoEvent event;
    private IntegracaoExterna conexao;

    @BeforeEach
    void setUp() {
        planoId = UUID.randomUUID();
        atletaId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
        event = new PlanoAprovadoEvent(planoId, atletaId, tenantId);
        conexao = new IntegracaoExterna();
        conexao.setId(UUID.randomUUID());
        conexao.setTenantId(tenantId);
    }

    @Nested
    @DisplayName("onPlanoAprovado")
    class OnPlanoAprovado {

        @Test
        @DisplayName("sem conexão ativa retorna sem tocar nos treinos nem no processor")
        void semConexaoAtivaNaoTocaTreinos() {
            when(connectionService.conexaoAtiva(atletaId, tenantId)).thenReturn(Optional.empty());

            listener.onPlanoAprovado(event);

            verifyNoInteractions(planoSemanalRepository, treinoPlanejadoRepository, workoutChannel, pushProcessor,
                    integracaoExternaRepository);
        }

        @Test
        @DisplayName("plano não encontrado no tenant retorna sem tocar nos treinos nem no processor")
        void planoNaoEncontradoNaoTocaTreinos() {
            when(connectionService.conexaoAtiva(atletaId, tenantId)).thenReturn(Optional.of(conexao));
            when(planoSemanalRepository.findByIdAndTenantId(planoId, tenantId)).thenReturn(Optional.empty());

            listener.onPlanoAprovado(event);

            verifyNoInteractions(treinoPlanejadoRepository, workoutChannel, pushProcessor, integracaoExternaRepository);
        }

        @Test
        @DisplayName("plano sem treinos planejados retorna sem tocar no processor nem no workoutChannel")
        void planoSemTreinosNaoTocaProcessorNemChannel() {
            PlanoSemanal plano = planoCom(LocalDate.now(), LocalDate.now().plusDays(6));

            mocarConexaoEPlano(plano);
            when(treinoPlanejadoRepository.findAllByPlanoSemanalIdAndTenantId(planoId, tenantId))
                    .thenReturn(java.util.List.of());

            listener.onPlanoAprovado(event);

            verifyNoInteractions(workoutChannel, pushProcessor, integracaoExternaRepository);
        }

        @Test
        @DisplayName("lote de 2 treinos com sucesso: processor chamado por treino, set de órfãos com os "
                + "externalIds canônicos, ultimaSincronizacao gravada")
        void loteDoisTreinosSucesso() {
            TreinoPlanejado t1 = treino(UUID.randomUUID());
            TreinoPlanejado t2 = treino(UUID.randomUUID());
            LocalDate inicio = LocalDate.now();
            LocalDate fim = inicio.plusDays(6);
            PlanoSemanal plano = planoCom(inicio, fim);

            mocarConexaoEPlano(plano);
            when(treinoPlanejadoRepository.findAllByPlanoSemanalIdAndTenantId(planoId, tenantId))
                    .thenReturn(java.util.List.of(t1, t2));
            when(pushProcessor.processar(t1.getId(), tenantId, conexao))
                    .thenReturn(new IntervalsIcuPushProcessor.ResultadoPush(
                            IntervalsIcuPushProcessor.ProcessamentoResultado.PROCESSADO_SUCESSO, true, 111L));
            when(pushProcessor.processar(t2.getId(), tenantId, conexao))
                    .thenReturn(new IntervalsIcuPushProcessor.ResultadoPush(
                            IntervalsIcuPushProcessor.ProcessamentoResultado.PROCESSADO_SUCESSO, false, 222L));

            listener.onPlanoAprovado(event);

            verify(pushProcessor).processar(t1.getId(), tenantId, conexao);
            verify(pushProcessor).processar(t2.getId(), tenantId, conexao);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Set<String>> setCaptor = ArgumentCaptor.forClass(Set.class);
            verify(workoutChannel).removerOrfaos(eq(conexao), eq(inicio), eq(fim), setCaptor.capture());
            assertThat(setCaptor.getValue())
                    .containsExactlyInAnyOrder(canonico(t1.getId()), canonico(t2.getId()));

            ArgumentCaptor<IntegracaoExterna> conexaoCaptor = ArgumentCaptor.forClass(IntegracaoExterna.class);
            verify(integracaoExternaRepository).save(conexaoCaptor.capture());
            assertThat(conexaoCaptor.getValue().getUltimaSincronizacao()).isNotNull();
        }

        @Test
        @DisplayName("NAO_EXPORTAVEL e NAO_ENCONTRADO ficam fora do set de reconciliação de órfãos")
        void naoExportavelENaoEncontradoForaDoSet() {
            TreinoPlanejado t1 = treino(UUID.randomUUID());
            TreinoPlanejado t2 = treino(UUID.randomUUID());
            PlanoSemanal plano = planoCom(LocalDate.now(), LocalDate.now().plusDays(6));

            mocarConexaoEPlano(plano);
            when(treinoPlanejadoRepository.findAllByPlanoSemanalIdAndTenantId(planoId, tenantId))
                    .thenReturn(java.util.List.of(t1, t2));
            when(pushProcessor.processar(t1.getId(), tenantId, conexao))
                    .thenReturn(IntervalsIcuPushProcessor.ResultadoPush.simples(
                            IntervalsIcuPushProcessor.ProcessamentoResultado.NAO_EXPORTAVEL));
            when(pushProcessor.processar(t2.getId(), tenantId, conexao))
                    .thenReturn(IntervalsIcuPushProcessor.ResultadoPush.simples(
                            IntervalsIcuPushProcessor.ProcessamentoResultado.NAO_ENCONTRADO));

            listener.onPlanoAprovado(event);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Set<String>> setCaptor = ArgumentCaptor.forClass(Set.class);
            verify(workoutChannel).removerOrfaos(any(), any(), any(), setCaptor.capture());
            assertThat(setCaptor.getValue()).isEmpty();
            verify(integracaoExternaRepository, never()).save(any());
        }

        @Test
        @DisplayName("CLAIM_PERDIDO e PROCESSADO_ERRO permanecem DENTRO do set de reconciliação de órfãos")
        void claimPerdidoEProcessadoErroDentroDoSet() {
            TreinoPlanejado t1 = treino(UUID.randomUUID());
            TreinoPlanejado t2 = treino(UUID.randomUUID());
            PlanoSemanal plano = planoCom(LocalDate.now(), LocalDate.now().plusDays(6));

            mocarConexaoEPlano(plano);
            when(treinoPlanejadoRepository.findAllByPlanoSemanalIdAndTenantId(planoId, tenantId))
                    .thenReturn(java.util.List.of(t1, t2));
            when(pushProcessor.processar(t1.getId(), tenantId, conexao))
                    .thenReturn(IntervalsIcuPushProcessor.ResultadoPush.simples(
                            IntervalsIcuPushProcessor.ProcessamentoResultado.CLAIM_PERDIDO));
            when(pushProcessor.processar(t2.getId(), tenantId, conexao))
                    .thenReturn(IntervalsIcuPushProcessor.ResultadoPush.simples(
                            IntervalsIcuPushProcessor.ProcessamentoResultado.PROCESSADO_ERRO));

            listener.onPlanoAprovado(event);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Set<String>> setCaptor = ArgumentCaptor.forClass(Set.class);
            verify(workoutChannel).removerOrfaos(any(), any(), any(), setCaptor.capture());
            assertThat(setCaptor.getValue()).containsExactlyInAnyOrder(canonico(t1.getId()), canonico(t2.getId()));
        }

        @Test
        @DisplayName("PROCESSADO_ERRO_AUTENTICACAO grava lastSyncError na conexão")
        void erroAutenticacaoGravaLastSyncError() {
            TreinoPlanejado t = treino(UUID.randomUUID());
            PlanoSemanal plano = planoCom(LocalDate.now(), LocalDate.now().plusDays(6));

            mocarConexaoEPlano(plano);
            when(treinoPlanejadoRepository.findAllByPlanoSemanalIdAndTenantId(planoId, tenantId))
                    .thenReturn(java.util.List.of(t));
            when(pushProcessor.processar(t.getId(), tenantId, conexao))
                    .thenReturn(IntervalsIcuPushProcessor.ResultadoPush.simples(
                            IntervalsIcuPushProcessor.ProcessamentoResultado.PROCESSADO_ERRO_AUTENTICACAO));

            listener.onPlanoAprovado(event);

            ArgumentCaptor<IntegracaoExterna> captor = ArgumentCaptor.forClass(IntegracaoExterna.class);
            verify(integracaoExternaRepository).save(captor.capture());
            assertThat(captor.getValue().getLastSyncError()).containsIgnoringCase("autenticação");
        }

        @Test
        @DisplayName("lote todo com falha (não-autenticação) não grava ultimaSincronizacao nem salva a conexão")
        void loteTodoComFalhaConexaoNaoSalva() {
            TreinoPlanejado t = treino(UUID.randomUUID());
            PlanoSemanal plano = planoCom(LocalDate.now(), LocalDate.now().plusDays(6));

            mocarConexaoEPlano(plano);
            when(treinoPlanejadoRepository.findAllByPlanoSemanalIdAndTenantId(planoId, tenantId))
                    .thenReturn(java.util.List.of(t));
            when(pushProcessor.processar(t.getId(), tenantId, conexao))
                    .thenReturn(IntervalsIcuPushProcessor.ResultadoPush.simples(
                            IntervalsIcuPushProcessor.ProcessamentoResultado.PROCESSADO_ERRO));

            listener.onPlanoAprovado(event);

            assertThat(conexao.getUltimaSincronizacao()).isNull();
            verify(integracaoExternaRepository, never()).save(any());
        }

        @Test
        @DisplayName("exceção inesperada do processor em um treino não aborta o processamento dos demais")
        void excecaoDoProcessorNaoAbortaDemais() {
            TreinoPlanejado t1 = treino(UUID.randomUUID());
            TreinoPlanejado t2 = treino(UUID.randomUUID());
            PlanoSemanal plano = planoCom(LocalDate.now(), LocalDate.now().plusDays(6));

            mocarConexaoEPlano(plano);
            when(treinoPlanejadoRepository.findAllByPlanoSemanalIdAndTenantId(planoId, tenantId))
                    .thenReturn(java.util.List.of(t1, t2));
            when(pushProcessor.processar(t1.getId(), tenantId, conexao)).thenThrow(new RuntimeException("boom"));
            when(pushProcessor.processar(t2.getId(), tenantId, conexao))
                    .thenReturn(new IntervalsIcuPushProcessor.ResultadoPush(
                            IntervalsIcuPushProcessor.ProcessamentoResultado.PROCESSADO_SUCESSO, true, 999L));

            listener.onPlanoAprovado(event);

            verify(pushProcessor).processar(t1.getId(), tenantId, conexao);
            verify(pushProcessor).processar(t2.getId(), tenantId, conexao);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Set<String>> setCaptor = ArgumentCaptor.forClass(Set.class);
            verify(workoutChannel).removerOrfaos(any(), any(), any(), setCaptor.capture());
            assertThat(setCaptor.getValue()).containsExactly(canonico(t2.getId()));
        }

        @Test
        @DisplayName("treino com tenant diferente do evento é ignorado com log de segurança: processor "
                + "never chamado para ele, mas o restante do lote é processado normalmente")
        void tenantMismatchIgnoraTreinoSemChamarProcessor() {
            UUID outroTenant = UUID.randomUUID();
            TreinoPlanejado mismatch = treino(UUID.randomUUID());
            mismatch.setTenantId(outroTenant);
            TreinoPlanejado valido = treino(UUID.randomUUID());
            PlanoSemanal plano = planoCom(LocalDate.now(), LocalDate.now().plusDays(6));

            mocarConexaoEPlano(plano);
            when(treinoPlanejadoRepository.findAllByPlanoSemanalIdAndTenantId(planoId, tenantId))
                    .thenReturn(java.util.List.of(mismatch, valido));
            when(pushProcessor.processar(valido.getId(), tenantId, conexao))
                    .thenReturn(new IntervalsIcuPushProcessor.ResultadoPush(
                            IntervalsIcuPushProcessor.ProcessamentoResultado.PROCESSADO_SUCESSO, true, 1L));

            listener.onPlanoAprovado(event);

            verify(pushProcessor, never()).processar(eq(mismatch.getId()), any(), any());
            verify(pushProcessor).processar(valido.getId(), tenantId, conexao);
        }

        @Test
        @DisplayName("nudge anti-debounce (CA2): 2+ eventos criados no lote disparam tocarEvento exatamente "
                + "uma vez, no ÚLTIMO evento criado, DEPOIS de removerOrfaos")
        void doisOuMaisCriadosDisparaNudgeNoUltimoAposRemoverOrfaos() {
            TreinoPlanejado t1 = treino(UUID.randomUUID());
            TreinoPlanejado t2 = treino(UUID.randomUUID());
            TreinoPlanejado t3 = treino(UUID.randomUUID());
            PlanoSemanal plano = planoCom(LocalDate.now(), LocalDate.now().plusDays(6));

            mocarConexaoEPlano(plano);
            when(treinoPlanejadoRepository.findAllByPlanoSemanalIdAndTenantId(planoId, tenantId))
                    .thenReturn(java.util.List.of(t1, t2, t3));
            when(pushProcessor.processar(t1.getId(), tenantId, conexao))
                    .thenReturn(new IntervalsIcuPushProcessor.ResultadoPush(
                            IntervalsIcuPushProcessor.ProcessamentoResultado.PROCESSADO_SUCESSO, true, 100L));
            when(pushProcessor.processar(t2.getId(), tenantId, conexao))
                    .thenReturn(new IntervalsIcuPushProcessor.ResultadoPush(
                            IntervalsIcuPushProcessor.ProcessamentoResultado.PROCESSADO_SUCESSO, false, 200L));
            when(pushProcessor.processar(t3.getId(), tenantId, conexao))
                    .thenReturn(new IntervalsIcuPushProcessor.ResultadoPush(
                            IntervalsIcuPushProcessor.ProcessamentoResultado.PROCESSADO_SUCESSO, true, 300L));

            listener.onPlanoAprovado(event);

            verify(workoutChannel, times(1)).tocarEvento(eq(conexao), eq(300L), eq(canonico(t3.getId())));
            var ordem = inOrder(workoutChannel);
            ordem.verify(workoutChannel).removerOrfaos(any(), any(), any(), any());
            ordem.verify(workoutChannel).tocarEvento(any(), anyLong(), any());
        }

        @Test
        @DisplayName("nudge anti-debounce (CA2): apenas 1 evento criado no lote nunca dispara tocarEvento")
        void umCriadoNuncaDisparaNudge() {
            TreinoPlanejado t1 = treino(UUID.randomUUID());
            PlanoSemanal plano = planoCom(LocalDate.now(), LocalDate.now().plusDays(6));

            mocarConexaoEPlano(plano);
            when(treinoPlanejadoRepository.findAllByPlanoSemanalIdAndTenantId(planoId, tenantId))
                    .thenReturn(java.util.List.of(t1));
            when(pushProcessor.processar(t1.getId(), tenantId, conexao))
                    .thenReturn(new IntervalsIcuPushProcessor.ResultadoPush(
                            IntervalsIcuPushProcessor.ProcessamentoResultado.PROCESSADO_SUCESSO, true, 100L));

            listener.onPlanoAprovado(event);

            verify(workoutChannel, never()).tocarEvento(any(), anyLong(), any());
        }

        @Test
        @DisplayName("nudge anti-debounce (CA2): lote só com atualizações (criadoNovo=false) nunca dispara "
                + "tocarEvento")
        void soAtualizacoesNuncaDisparaNudge() {
            TreinoPlanejado t1 = treino(UUID.randomUUID());
            TreinoPlanejado t2 = treino(UUID.randomUUID());
            PlanoSemanal plano = planoCom(LocalDate.now(), LocalDate.now().plusDays(6));

            mocarConexaoEPlano(plano);
            when(treinoPlanejadoRepository.findAllByPlanoSemanalIdAndTenantId(planoId, tenantId))
                    .thenReturn(java.util.List.of(t1, t2));
            when(pushProcessor.processar(t1.getId(), tenantId, conexao))
                    .thenReturn(new IntervalsIcuPushProcessor.ResultadoPush(
                            IntervalsIcuPushProcessor.ProcessamentoResultado.PROCESSADO_SUCESSO, false, 100L));
            when(pushProcessor.processar(t2.getId(), tenantId, conexao))
                    .thenReturn(new IntervalsIcuPushProcessor.ResultadoPush(
                            IntervalsIcuPushProcessor.ProcessamentoResultado.PROCESSADO_SUCESSO, false, 200L));

            listener.onPlanoAprovado(event);

            verify(workoutChannel, never()).tocarEvento(any(), anyLong(), any());
        }

        @Test
        @DisplayName("nudge anti-debounce (CA2): tocarEvento lançando exceção não afeta os demais estados do lote")
        void nudgeLancandoNaoAfetaEstados() {
            TreinoPlanejado t1 = treino(UUID.randomUUID());
            TreinoPlanejado t2 = treino(UUID.randomUUID());
            PlanoSemanal plano = planoCom(LocalDate.now(), LocalDate.now().plusDays(6));

            mocarConexaoEPlano(plano);
            when(treinoPlanejadoRepository.findAllByPlanoSemanalIdAndTenantId(planoId, tenantId))
                    .thenReturn(java.util.List.of(t1, t2));
            when(pushProcessor.processar(t1.getId(), tenantId, conexao))
                    .thenReturn(new IntervalsIcuPushProcessor.ResultadoPush(
                            IntervalsIcuPushProcessor.ProcessamentoResultado.PROCESSADO_SUCESSO, true, 100L));
            when(pushProcessor.processar(t2.getId(), tenantId, conexao))
                    .thenReturn(new IntervalsIcuPushProcessor.ResultadoPush(
                            IntervalsIcuPushProcessor.ProcessamentoResultado.PROCESSADO_SUCESSO, true, 200L));
            doThrow(new RuntimeException("boom")).when(workoutChannel).tocarEvento(any(), anyLong(), any());

            listener.onPlanoAprovado(event);

            assertThat(conexao.getUltimaSincronizacao()).isNotNull();
            verify(integracaoExternaRepository).save(conexao);
            verify(workoutChannel).removerOrfaos(eq(conexao), any(), any(), any());
            verify(workoutChannel).tocarEvento(eq(conexao), eq(200L), eq(canonico(t2.getId())));
        }
    }

    // =========================================================================
    // Guard-rail spec 8.7 — a aprovação nunca falha por causa do push.
    //
    // Invertido pelo hardening (intervals-icu-push-hardening): o listener deixou de abrir
    // transação própria — cada treino é processado nas TXs curtas do IntervalsIcuPushProcessor
    // (claim; marcação). A AUSÊNCIA de @Transactional aqui é agora a invariante: se alguém
    // reintroduzir @Transactional no listener, uma falha de claim/marcação de um treino voltaria
    // a arrastar rollback sobre os demais do lote.
    // =========================================================================

    @Nested
    @DisplayName("guard-rail spec 8.7: anotações do listener")
    class GuardRailAnotacoes {

        private Method onPlanoAprovado() throws NoSuchMethodException {
            return IntervalsIcuPushListener.class.getMethod("onPlanoAprovado", PlanoAprovadoEvent.class);
        }

        @Test
        @DisplayName("@Async no executor dedicado intervalsIcuPushExecutor")
        void asyncNoExecutorDedicado() throws NoSuchMethodException {
            Async async = onPlanoAprovado().getAnnotation(Async.class);
            assertThat(async).as("@Async removida: o push passaria a rodar síncrono na aprovação").isNotNull();
            assertThat(async.value()).isEqualTo("intervalsIcuPushExecutor");
        }

        @Test
        @DisplayName("@TransactionalEventListener com phase AFTER_COMMIT")
        void listenerAposCommit() throws NoSuchMethodException {
            TransactionalEventListener listener = onPlanoAprovado().getAnnotation(TransactionalEventListener.class);
            assertThat(listener).as("@TransactionalEventListener removida").isNotNull();
            assertThat(listener.phase())
                    .as("sem AFTER_COMMIT o push processaria eventos de transações que sofreram rollback")
                    .isEqualTo(TransactionPhase.AFTER_COMMIT);
        }

        @Test
        @DisplayName("SEM @Transactional: as TXs curtas por treino vivem no IntervalsIcuPushProcessor, "
                + "nunca no listener — reintroduzir a anotação voltaria a arrastar rollback entre treinos")
        void semTransactionalProprio() throws NoSuchMethodException {
            Transactional transactional = onPlanoAprovado().getAnnotation(Transactional.class);
            assertThat(transactional)
                    .as("@Transactional reintroduzida no listener: as TXs agora são do processor, por treino")
                    .isNull();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void mocarConexaoEPlano(PlanoSemanal plano) {
        when(connectionService.conexaoAtiva(atletaId, tenantId)).thenReturn(Optional.of(conexao));
        when(planoSemanalRepository.findByIdAndTenantId(planoId, tenantId)).thenReturn(Optional.of(plano));
    }

    private PlanoSemanal planoCom(LocalDate inicio, LocalDate fim) {
        PlanoSemanal plano = new PlanoSemanal();
        plano.setId(planoId);
        plano.setSemanaInicio(inicio);
        plano.setSemanaFim(fim);
        return plano;
    }

    private TreinoPlanejado treino(UUID id) {
        TreinoPlanejado t = new TreinoPlanejado();
        t.setId(id);
        t.setTenantId(tenantId);
        return t;
    }

    private String canonico(UUID treinoId) {
        return StructuredWorkout.externalIdCanonico(treinoId);
    }
}
