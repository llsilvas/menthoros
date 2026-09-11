package br.com.menthoros.backend.services.helper;

import br.com.menthoros.backend.dto.input.DadosPlanoDto;
import br.com.menthoros.backend.dto.llm.PlanoSemanalLlmDto;
import br.com.menthoros.backend.dto.llm.TreinoPlanejadoLlmDto;
import br.com.menthoros.backend.dto.output.MetricasSemanaisMedias;
import br.com.menthoros.backend.dto.output.PadroesTreino;
import br.com.menthoros.backend.entity.Assessoria;
import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.entity.PlanoMetaDados;
import br.com.menthoros.backend.entity.PlanoSemanal;
import br.com.menthoros.backend.enums.ModoGeracaoPlano;
import br.com.menthoros.backend.mapper.PlanoSemanalMapper;
import br.com.menthoros.backend.mapper.TreinoMapper;
import br.com.menthoros.backend.mapper.TreinoMapperImpl;
import br.com.menthoros.backend.multitenancy.TenantContext;
import br.com.menthoros.backend.repository.PlanoMetadadosRepository;
import br.com.menthoros.backend.repository.PlanoSemanalRepository;
import br.com.menthoros.backend.services.MetricasAgregadasService;
import br.com.menthoros.backend.services.PlanoReviewService;
import br.com.menthoros.backend.services.impl.MetricasAlertaService;
import br.com.menthoros.backend.services.onboarding.OnboardingService;
import br.com.menthoros.backend.services.plano.ProvaNoPlanoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D2/2.2 (prova-no-plano-semanal): {@code PlanGenerationPersister} chama
 * {@code ProvaNoPlanoService.garantirProvasNaSemana} entre a redistribuição e a validação, e usa
 * a lista final de treinos — não o {@code volumePlanejadoKm} bruto do LLM — para os metadados.
 */
@ExtendWith(MockitoExtension.class)
class PlanGenerationPersisterProvaTest {

    @Mock private PlanoSemanalRepository planoSemanalRepository;
    @Mock private PlanoMetadadosRepository planoMetadadosRepository;
    @Mock private PlanoSemanalMapper planoSemanalMapper;
    @Mock private RedistribuicaoTreinoHelper redistribuicaoHelper;
    @Mock private MetricasAlertaService metricasAlertaService;
    @Mock private MetricasAgregadasService metricasAgregadasService;
    @Mock private PlannerShadowService plannerShadowService;
    @Mock private OnboardingService onboardingService;
    @Mock private PlanoReviewService planoReviewService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ProvaNoPlanoService provaNoPlanoService;

    private final TreinoMapper treinoMapper = new TreinoMapperImpl(null, null);

    private PlanGenerationPersister persister;
    private io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId);
        meterRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        persister = new PlanGenerationPersister(
                planoSemanalRepository, planoMetadadosRepository, treinoMapper, planoSemanalMapper,
                redistribuicaoHelper, metricasAlertaService, metricasAgregadasService,
                plannerShadowService, onboardingService, planoReviewService, eventPublisher,
                provaNoPlanoService,
                meterRegistry);

        lenient().when(planoSemanalRepository.existePlanoAtivoNaSemana(any(), any(), any())).thenReturn(false);
        lenient().when(planoSemanalRepository.findTopByAtletaIdOrderBySemanaInicioDesc(any())).thenReturn(Optional.empty());
        lenient().when(planoSemanalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // possuiBaseline=false + migrateExistingEnabled=false (default do campo, sem Spring)
        // faz resolverOnboardingContext parar em Optional.empty() sem chamar montarContexto.
        lenient().when(onboardingService.possuiBaseline(any(), any())).thenReturn(false);
        lenient().when(plannerShadowService.aplicarShadow(any(), any(), any(), any(), any(), eq(false), any()))
                .thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("estagio 2 — compliance pos-redistribuicao (planner-engine-enforcement §5)")
    class EnforcementEstagio2 {

        private br.com.menthoros.backend.domain.planner.WeekPlanSkeleton skeleton() {
            return new br.com.menthoros.backend.domain.planner.WeekPlanSkeleton(
                    null, null, List.of(), null, null, false, null,
                    LocalDate.of(2026, 9, 7), null, Optional.empty());
        }

        private void invoke(PlanoSemanal plano) throws Exception {
            var m = PlanGenerationPersister.class.getDeclaredMethod("aplicarEnforcementEstagio2",
                    PlanoSemanal.class,
                    br.com.menthoros.backend.domain.planner.WeekPlanSkeleton.class,
                    Atleta.class, LocalDate.class);
            m.setAccessible(true);
            try {
                m.invoke(persister, plano, skeleton(), new Atleta(), LocalDate.of(2026, 9, 7));
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException re) throw re;
                throw e;
            }
        }

        private double postFailureCount() {
            var c = meterRegistry.find("planner.compliance.failure.count").tag("stage", "POST").counter();
            return c == null ? 0.0 : c.count();
        }

        @Test
        @DisplayName("sem violacao: status PASSED, sem requiresCoachReview, sem metrica")
        void semViolacao() throws Exception {
            when(plannerShadowService.checkPostRedistribution(any(), any(), any(), any()))
                    .thenReturn(List.of());
            PlanoSemanal plano = new PlanoSemanal();

            invoke(plano);

            assertThat(plano.getPlannerComplianceStatus())
                    .isEqualTo(br.com.menthoros.backend.domain.compliance.PlannerComplianceStatus.PASSED.name());
            assertThat(plano.getPlannerRequiresCoachReview()).isNotEqualTo(Boolean.TRUE);
            assertThat(postFailureCount()).isZero();
        }

        @Test
        @DisplayName("violacao soft + fail-open=true: FAILED + requiresCoachReview + metrica {stage=POST}")
        void violacaoFailOpen() throws Exception {
            org.springframework.test.util.ReflectionTestUtils.setField(persister, "plannerFailOpen", true);
            when(plannerShadowService.checkPostRedistribution(any(), any(), any(), any()))
                    .thenReturn(List.of(new br.com.menthoros.backend.domain.compliance.PlannerViolation(
                            br.com.menthoros.backend.domain.compliance.PlannerViolationKey.DIA_INDISPONIVEL,
                            "treino em dia nao disponivel do atleta")));
            PlanoSemanal plano = new PlanoSemanal();

            invoke(plano);

            assertThat(plano.getPlannerComplianceStatus())
                    .isEqualTo(br.com.menthoros.backend.domain.compliance.PlannerComplianceStatus.FAILED.name());
            assertThat(plano.getPlannerRequiresCoachReview()).isTrue();
            assertThat(postFailureCount()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("violacao + fail-open=false: erro de dominio, nada mutado, sem metrica")
        void violacaoFailClosed() {
            org.springframework.test.util.ReflectionTestUtils.setField(persister, "plannerFailOpen", false);
            when(plannerShadowService.checkPostRedistribution(any(), any(), any(), any()))
                    .thenReturn(List.of(new br.com.menthoros.backend.domain.compliance.PlannerViolation(
                            br.com.menthoros.backend.domain.compliance.PlannerViolationKey.TAPER_VIOLADO,
                            "carga alta na semana de taper")));
            PlanoSemanal plano = new PlanoSemanal();

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> invoke(plano))
                    .isInstanceOf(br.com.menthoros.backend.exception.DomainRuleViolationException.class)
                    .hasMessageContaining("TAPER_VIOLADO");
            assertThat(plano.getPlannerComplianceStatus()).isNull();
            assertThat(postFailureCount()).isZero();
        }
    }

    @Nested
    @DisplayName("veto do enforcement na auto-aprovacao (Codex blocker 3)")
    class VetoAutoAprovacao {

        private final br.com.menthoros.backend.domain.planner.OnboardingContext contextoExceptionOnly =
                new br.com.menthoros.backend.domain.planner.OnboardingContext(
                        new br.com.menthoros.backend.domain.planner.AthleteBaseline(null, null),
                        1.0,
                        new br.com.menthoros.backend.domain.planner.PlanningPolicy(
                                br.com.menthoros.backend.domain.planner.ReviewMode.EXCEPTION_ONLY, 0.0, false),
                        new br.com.menthoros.backend.domain.planner.AthleteConstraints(List.of(), null, null, List.of()));

        private br.com.menthoros.backend.domain.planner.WeekPlanSkeleton skeletonSemReview() {
            return new br.com.menthoros.backend.domain.planner.WeekPlanSkeleton(
                    null, null, List.of(), null, null, false, null,
                    LocalDate.of(2026, 9, 7), null, Optional.empty());
        }

        private void invoke(PlanoSemanal plano) throws Exception {
            var m = PlanGenerationPersister.class.getDeclaredMethod("aplicarAutoApproveSeElegivel",
                    PlanoSemanal.class,
                    br.com.menthoros.backend.domain.planner.OnboardingContext.class,
                    Optional.class, UUID.class);
            m.setAccessible(true);
            org.springframework.test.util.ReflectionTestUtils.setField(persister, "autoApproveEnabled", true);
            m.invoke(persister, plano, contextoExceptionOnly, Optional.of(skeletonSemReview()), tenantId);
        }

        @Test
        @DisplayName("plano FAILED nao e auto-aprovado: aprovarTransicao nunca chamado")
        void planoFailedNaoAprovado() throws Exception {
            PlanoSemanal plano = new PlanoSemanal();
            plano.setPlannerComplianceStatus(
                    br.com.menthoros.backend.domain.compliance.PlannerComplianceStatus.FAILED.name());

            invoke(plano);

            verify(planoReviewService, org.mockito.Mockito.never())
                    .aprovarTransicao(any(), any(), any());
        }

        @Test
        @DisplayName("plano com requiresCoachReview=true nao e auto-aprovado")
        void planoRequiresReviewNaoAprovado() throws Exception {
            PlanoSemanal plano = new PlanoSemanal();
            plano.setPlannerRequiresCoachReview(true);

            invoke(plano);

            verify(planoReviewService, org.mockito.Mockito.never())
                    .aprovarTransicao(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("persist — garantia da prova")
    class GarantiaDaProva {

        @Test
        @DisplayName("chama garantirProvasNaSemana com o atleta e o período do plano, depois da redistribuição")
        void chamaGarantirProvasNaSemanaComPeriodoCorreto() {
            Atleta atleta = atletaComAssessoria();
            LocalDate semanaInicio = LocalDate.now();
            TreinoPlanejadoLlmDto longoNoDomingo = treinoDto("DOMINGO", "LONGO", 15.0);
            TreinoPlanejadoLlmDto provaNoDomingo = treinoDto("DOMINGO", "PROVA", 21.1);

            when(provaNoPlanoService.garantirProvasNaSemana(anyList(), eq(atleta), eq(semanaInicio), eq(semanaInicio.plusDays(6))))
                    .thenReturn(List.of(provaNoDomingo));

            PlanoSemanalLlmDto planoDto = planoDtoCom(List.of(longoNoDomingo), 15.0);
            PlanoMetaDados metaDadosSemId = new PlanoMetaDados(); // id nulo → prepararMetadados não mexe em collaborators extras
            DadosPlanoDto dadosPlano = dadosPlanoDto(atleta, metaDadosSemId);
            when(planoSemanalMapper.toEntity(planoDto)).thenReturn(new PlanoSemanal());

            PlanGenerationContext ctx = new PlanGenerationContext(dadosPlano, null, semanaInicio, null, null);

            PlanoSemanal salvo = persister.persist(planoDto, ctx, ModoGeracaoPlano.PROXIMA_SEMANA);

            verify(provaNoPlanoService).garantirProvasNaSemana(eq(List.of(longoNoDomingo)), eq(atleta),
                    eq(semanaInicio), eq(semanaInicio.plusDays(6)));

            assertThat(salvo.getTreinosPlanejados()).hasSize(1);
            assertThat(salvo.getTreinosPlanejados().getFirst().getTipoTreino().name()).isEqualTo("PROVA");
        }

        @Test
        @DisplayName("volumePlanejadoKm do plano inclui a distância da prova garantida")
        void volumeDoPlanoIncluiProva() {
            Atleta atleta = atletaComAssessoria();
            LocalDate semanaInicio = LocalDate.now();
            TreinoPlanejadoLlmDto continuo = treinoDto("SEGUNDA", "CONTINUO", 8.0);
            TreinoPlanejadoLlmDto provaGarantida = treinoDto("DOMINGO", "PROVA", 21.1);

            when(provaNoPlanoService.garantirProvasNaSemana(anyList(), any(), any(), any()))
                    .thenReturn(List.of(continuo, provaGarantida));

            PlanoSemanalLlmDto planoDto = planoDtoCom(List.of(continuo), 8.0);
            DadosPlanoDto dadosPlano = dadosPlanoDto(atleta, new PlanoMetaDados());
            when(planoSemanalMapper.toEntity(planoDto)).thenReturn(new PlanoSemanal());

            PlanGenerationContext ctx = new PlanGenerationContext(dadosPlano, null, semanaInicio, null, null);

            PlanoSemanal salvo = persister.persist(planoDto, ctx, ModoGeracaoPlano.PROXIMA_SEMANA);

            assertThat(salvo.getVolumePlanejadoKm()).isEqualByComparingTo(BigDecimal.valueOf(8.0 + 21.1));
        }

        @Test
        @DisplayName("metadados usam o volume recalculado da lista final, não o volumePlanejadoKm bruto do LLM")
        void metadadosUsamVolumeRecalculado() {
            Atleta atleta = atletaComAssessoria();
            LocalDate semanaInicio = LocalDate.now();
            TreinoPlanejadoLlmDto continuo = treinoDto("SEGUNDA", "CONTINUO", 8.0);
            TreinoPlanejadoLlmDto provaGarantida = treinoDto("DOMINGO", "PROVA", 21.1);

            when(provaNoPlanoService.garantirProvasNaSemana(anyList(), any(), any(), any()))
                    .thenReturn(List.of(continuo, provaGarantida));

            // LLM declarou 8.0 (sem a prova) — o valor errado que o Major do DoR apontou.
            PlanoSemanalLlmDto planoDto = planoDtoCom(List.of(continuo), 8.0);

            PlanoMetaDados metaDados = new PlanoMetaDados();
            UUID metaDadosId = UUID.randomUUID();
            metaDados.setId(metaDadosId);
            DadosPlanoDto dadosPlano = dadosPlanoDto(atleta, metaDados);
            when(planoSemanalMapper.toEntity(planoDto)).thenReturn(new PlanoSemanal());

            when(planoMetadadosRepository.findByIdAndTenantId(metaDadosId, tenantId)).thenReturn(Optional.of(metaDados));
            when(metricasAgregadasService.calcularMetricasSemanais(atleta.getId(), 6))
                    .thenReturn(new MetricasSemanaisMedias(BigDecimal.ZERO, 0, 0.0));
            when(metricasAgregadasService.calcularPadroesTreino(atleta.getId()))
                    .thenReturn(new PadroesTreino(0, 0));
            when(metricasAlertaService.analisarMetricas(any()))
                    .thenReturn(new br.com.menthoros.backend.dto.output.ResultadoAnalise(
                            "OK", "Manter", null, false, false, false, false, List.of()));
            when(planoMetadadosRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PlanGenerationContext ctx = new PlanGenerationContext(dadosPlano, null, semanaInicio, null, null);

            persister.persist(planoDto, ctx, ModoGeracaoPlano.PROXIMA_SEMANA);

            ArgumentCaptor<PlanoMetaDados> captor = ArgumentCaptor.forClass(PlanoMetaDados.class);
            verify(planoMetadadosRepository).save(captor.capture());
            assertThat(captor.getValue().getVolumePlanejado()).isEqualByComparingTo(BigDecimal.valueOf(8.0 + 21.1));
        }
    }

    // ---- helpers ----

    private Atleta atletaComAssessoria() {
        Assessoria assessoria = new Assessoria();
        assessoria.setId(tenantId);
        Atleta atleta = new Atleta();
        atleta.setId(UUID.randomUUID());
        atleta.setAssessoria(assessoria);
        return atleta;
    }

    private TreinoPlanejadoLlmDto treinoDto(String diaSemana, String tipoTreino, double distanciaKm) {
        return new TreinoPlanejadoLlmDto(diaSemana, tipoTreino, null, null, null, null, null,
                "01:00:00", distanciaKm, null, null);
    }

    private PlanoSemanalLlmDto planoDtoCom(List<TreinoPlanejadoLlmDto> treinos, double volumeDeclaradoPeloLlm) {
        return PlanoSemanalLlmDto.builder()
                .volumePlanejadoKm(volumeDeclaradoPeloLlm)
                .volumeAlvoKm(volumeDeclaradoPeloLlm)
                .status("PLANEJADO")
                .objetivoSemanal("Semana de teste")
                .treinosPlanejados(treinos)
                .build();
    }

    private DadosPlanoDto dadosPlanoDto(Atleta atleta, PlanoMetaDados metaDados) {
        return new DadosPlanoDto(atleta, LocalDate.now(), null, List.of(), metaDados);
    }
}
