package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.domain.planner.AthleteBaseline;
import br.com.menthoros.backend.domain.planner.AthleteConstraints;
import br.com.menthoros.backend.domain.planner.ConstraintValidationResult;
import br.com.menthoros.backend.domain.planner.InjuryRiskAssessment;
import br.com.menthoros.backend.domain.planner.InjuryRiskLevel;
import br.com.menthoros.backend.domain.planner.OnboardingContext;
import br.com.menthoros.backend.domain.planner.PlanningPolicy;
import br.com.menthoros.backend.domain.planner.ReviewMode;
import br.com.menthoros.backend.domain.planner.TrainingPhase;
import br.com.menthoros.backend.domain.planner.WeekPlanSkeleton;
import br.com.menthoros.backend.domain.planner.WeeklyLoadTarget;
import br.com.menthoros.backend.dto.input.DadosPlanoDto;
import br.com.menthoros.backend.dto.llm.PlanoSemanalLlmDto;
import br.com.menthoros.backend.dto.llm.TreinoPlanejadoLlmDto;
import br.com.menthoros.backend.dto.output.MetricasSemanaisMedias;
import br.com.menthoros.backend.dto.output.PlanoSemanalOutputDto;
import br.com.menthoros.backend.dto.output.PadroesTreino;
import br.com.menthoros.backend.dto.output.ResultadoAnalise;
import br.com.menthoros.backend.dto.output.TreinoRealizadoOutputDto;
import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.entity.PlanoMetaDados;
import br.com.menthoros.backend.entity.PlanoSemanal;
import br.com.menthoros.backend.entity.RevisaoSemanal;
import br.com.menthoros.backend.entity.TreinoPlanejado;
import br.com.menthoros.backend.entity.TreinoRealizado;
import br.com.menthoros.backend.enums.*;
import br.com.menthoros.backend.exception.DomainRuleViolationException;
import br.com.menthoros.backend.exception.LLMException;
import br.com.menthoros.backend.exception.ResourceNotFoundException;
import br.com.menthoros.backend.mapper.AtletaMapper;
import br.com.menthoros.backend.mapper.PlanoSemanalMapper;
import br.com.menthoros.backend.mapper.TreinoMapper;
import br.com.menthoros.backend.repository.AtletaRepository;
import br.com.menthoros.backend.repository.PlanoMetadadosRepository;
import br.com.menthoros.backend.repository.PlanoSemanalRepository;
import br.com.menthoros.backend.repository.TreinoRealizadoRepository;
import br.com.menthoros.backend.services.IaService;
import br.com.menthoros.backend.services.ProgressaoTreinoService;
import br.com.menthoros.backend.services.helper.RedistribuicaoTreinoHelper;
import br.com.menthoros.backend.services.helper.RegraGeracaoTreino;
import br.com.menthoros.backend.entity.Assessoria;
import br.com.menthoros.backend.multitenancy.TenantContext;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * This class tests the PlanoServiceImpl class using JUnit 5 and Mockito.
 * <p>
 * The @ExtendWith(MockitoExtension.class) annotation is used to enable
 * Mockito's annotations (@Mock, @InjectMocks, etc.) in JUnit 5 tests.
 */
@ExtendWith(MockitoExtension.class)
class PlanoServiceImplTest {

    @Mock
    private IaService iaService;
    @Mock
    private ProgressaoTreinoService progressaoTreinoService;
    @Mock
    private AtletaRepository atletaRepository;
    @Mock
    private AtletaMapper atletaMapper;
    @Mock
    private TreinoMapper treinoMapper;
    @Mock
    private PlanoSemanalMapper planoSemanalMapper;
    @Mock
    private PlanoMetadadosRepository planoMetadadosRepository;
    @Mock
    private PlanoSemanalRepository planoSemanalRepository;
    @Mock
    private TreinoRealizadoRepository treinoRealizadoRepository;
    @Mock
    private RedistribuicaoTreinoHelper redistribuicaoHelper;
    @Mock
    private RegraGeracaoTreino regraGeracaoTreino;
    @Mock
    private br.com.menthoros.backend.services.PlanoMetadadosService planoMetadadosService;
    @Mock
    private MetricasAlertaService metricasAlertaService;
    @Mock
    private MetricasAgregadasServiceImpl metricasAgregadasService;
    @Mock
    private br.com.menthoros.backend.services.helper.PlannerShadowService plannerShadowService;
    @Mock
    private br.com.menthoros.backend.services.onboarding.OnboardingService onboardingService;
    @Mock
    private br.com.menthoros.backend.services.PlanoReviewService planoReviewService;
    @Mock
    private br.com.menthoros.backend.services.prompt.WeeklyReviewPromptProvider weeklyReviewPromptProvider;
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock
    private br.com.menthoros.backend.repository.AiWorkoutAnalysisRepository aiWorkoutAnalysisRepository;
    // Real: o serviço lê getAthleteMessage().isEnabled() — mock devolveria null.
    @org.mockito.Spy
    private br.com.menthoros.backend.config.core.WorkoutAnalysisProperties workoutAnalysisProperties =
            new br.com.menthoros.backend.config.core.WorkoutAnalysisProperties();

    // Limiter real com spy (fair-llm-concurrency-per-tenant): pass-through de verdade, e o spy
    // permite afirmar que a fase 2 passa pela faixa interativa.
    private br.com.menthoros.backend.services.helper.LlmConcurrencyLimiter llmConcurrencyLimiter;

    // Loader e persister REAIS sobre os mesmos mocks (refactor-llm-call-outside-transaction): o
    // comportamento observavel do fluxo continua sendo testado pelo orquestrador, e as
    // asserções sobre repositorios/colaboradores seguem validas sem alteração.
    private br.com.menthoros.backend.services.helper.PlanGenerationContextLoader contextLoader;
    private br.com.menthoros.backend.services.helper.PlanGenerationPersister persister;
    @org.mockito.Mock
    private br.com.menthoros.backend.services.plano.ProvaNoPlanoService provaNoPlanoService;
    private PlanoServiceImpl planoService;
    private io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry;

    private UUID tenantId;

    @BeforeEach
    void setUpTenant() {
        contextLoader = new br.com.menthoros.backend.services.helper.PlanGenerationContextLoader(
                atletaRepository, planoMetadadosService, treinoRealizadoRepository, treinoMapper,
                planoSemanalRepository, planoSemanalMapper, progressaoTreinoService, weeklyReviewPromptProvider);
        org.mockito.Mockito.lenient()
                .when(provaNoPlanoService.garantirProvasNaSemana(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));
        meterRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        persister = new br.com.menthoros.backend.services.helper.PlanGenerationPersister(
                planoSemanalRepository, planoMetadadosRepository, treinoMapper, planoSemanalMapper,
                redistribuicaoHelper, metricasAlertaService, metricasAgregadasService, plannerShadowService,
                onboardingService, planoReviewService, eventPublisher, provaNoPlanoService, meterRegistry);
        org.springframework.test.util.ReflectionTestUtils.setField(persister, "autoApproveEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(persister, "migrateExistingEnabled", true);
        llmConcurrencyLimiter = org.mockito.Mockito.spy(
                new br.com.menthoros.backend.services.helper.LlmConcurrencyLimiter(4, 2, 1));
        planoService = new PlanoServiceImpl(iaService, llmConcurrencyLimiter, contextLoader, persister, planoSemanalRepository,
                treinoRealizadoRepository, planoSemanalMapper, eventPublisher, aiWorkoutAnalysisRepository,
                workoutAnalysisProperties, plannerShadowService,
                meterRegistry);

        tenantId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId);
        lenient().when(onboardingService.possuiBaseline(any(), any())).thenReturn(true);
        lenient().when(onboardingService.montarContexto(any(), any())).thenReturn(
                new OnboardingContext(
                        new AthleteBaseline(null, null),
                        0.0,
                        new PlanningPolicy(ReviewMode.MANDATORY_BLOCKING, 0.0, true),
                        new AthleteConstraints(List.of(), null, null, List.of())));
    }

    @AfterEach
    void tearDownTenant() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("computarSkeletonSeHabilitado — matriz fail-open (planner-engine-enforcement §4, Decisao 3)")
    class ComputarSkeletonSeHabilitado {

        private br.com.menthoros.backend.services.helper.PlanGenerationContext ctx() {
            var atleta = criarAtletaMock(UUID.randomUUID());
            var dados = new br.com.menthoros.backend.dto.input.DadosPlanoDto(
                    atleta, LocalDate.now(), null, Collections.emptyList(), criarPlanoMetaDadosMock());
            return new br.com.menthoros.backend.services.helper.PlanGenerationContext(
                    dados, null, LocalDate.of(2026, 9, 7), null, null);
        }

        private br.com.menthoros.backend.domain.planner.WeekPlanSkeleton invoke() throws Exception {
            var m = PlanoServiceImpl.class.getDeclaredMethod("computarSkeletonSeHabilitado",
                    br.com.menthoros.backend.services.helper.PlanGenerationContext.class);
            m.setAccessible(true);
            try {
                return (br.com.menthoros.backend.domain.planner.WeekPlanSkeleton) m.invoke(planoService, ctx());
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException re) throw re;
                throw e;
            }
        }

        private double fallbackCount() {
            var c = meterRegistry.find("planner.fallback_legacy.count").counter();
            return c == null ? 0.0 : c.count();
        }

        @Test
        @DisplayName("flag off: skeleton null, planner nem é chamado, sem métrica de fallback")
        void flagOff() throws Exception {
            org.springframework.test.util.ReflectionTestUtils.setField(planoService, "plannerEnabled", false);

            assertNull(invoke());
            verify(plannerShadowService, never()).computarSkeleton(any(), any(), any(), any());
            assertThat(fallbackCount()).isZero();
        }

        @Test
        @DisplayName("planner falha ANTES do LLM + fail-open=true: null (pipeline legado) + planner.fallback_legacy.count")
        void falhaComFailOpen() throws Exception {
            org.springframework.test.util.ReflectionTestUtils.setField(planoService, "plannerEnabled", true);
            org.springframework.test.util.ReflectionTestUtils.setField(planoService, "plannerFailOpen", true);
            when(plannerShadowService.computarSkeleton(any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("planner indisponível"));

            assertNull(invoke());
            assertThat(fallbackCount()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("planner falha ANTES do LLM + fail-open=false: erro de domínio, nada gerado, sem fallback")
        void falhaComFailClosed() {
            org.springframework.test.util.ReflectionTestUtils.setField(planoService, "plannerEnabled", true);
            org.springframework.test.util.ReflectionTestUtils.setField(planoService, "plannerFailOpen", false);
            when(plannerShadowService.computarSkeleton(any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("planner indisponível"));

            assertThatThrownBy(this::invoke)
                    .isInstanceOf(br.com.menthoros.backend.exception.DomainRuleViolationException.class);
            assertThat(fallbackCount()).isZero();
        }
    }

    private void mockMetricasAgregadasEAlertas(PlanoMetaDados metaDados) {
        when(metricasAgregadasService.calcularMetricasSemanais(any(), anyInt()))
                .thenReturn(new MetricasSemanaisMedias(BigDecimal.valueOf(30.0), 300, 4.0));
        when(metricasAgregadasService.calcularPadroesTreino(any()))
                .thenReturn(new PadroesTreino(0, 0));

        when(metricasAlertaService.analisarMetricas(any()))
                .thenReturn(new ResultadoAnalise(
                        "NORMAL",
                        "Continuar treinamento normalmente, respeitando os princípios de progressão.",
                        null,
                        false, false, false, false,
                        Collections.emptyList()
                ));
    }

    @Test
    @DisplayName("Deve gerar plano com atleta válido e modo de geração válido")
    void deveGerarPlanoComAtletaValidoEModoGeracaoValido() {
        // Given
        UUID atletaId = UUID.randomUUID();
        ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;

        Atleta atleta = criarAtletaMock(atletaId);
        PlanoMetaDados metaDados = criarPlanoMetaDadosMock();
        DadosPlanoDto dadosPlano = new DadosPlanoDto(atleta, LocalDate.now(), null, Collections.emptyList(), metaDados);

        PlanoSemanalLlmDto planoDto = criarPlanoSemanalLlmDto();
        List<TreinoPlanejadoLlmDto> treinos = criarTreinosCompletos();
        PlanoSemanal planoSalvo = criarPlanoSemanalMock();
        TreinoPlanejado treinoPlanejado = criarTreinoPlanejadoMock();

        mockMetricasAgregadasEAlertas(metaDados);

        // Mock dependencies
        when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
        when(planoMetadadosService.buscarOuCriarMetadados(atleta)).thenReturn(metaDados);
        when(treinoRealizadoRepository.findByAtletaIdAndDataTreinoBetween(eq(atletaId), any(LocalDate.class), any(LocalDate.class))).thenReturn(Collections.emptyList());
        when(planoSemanalRepository.findTopByAtletaIdOrderBySemanaInicioDesc(atletaId)).thenReturn(Optional.empty());
        when(planoSemanalRepository.findTopByAtletaIdAndSemanaInicioBeforeAndStatusOrderBySemanaInicioDesc(
                any(), any(), any())).thenReturn(Optional.empty());

        when(iaService.geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), eq(modoGeracao), any(), any(), any(), any())).thenReturn(planoDto);
        when(planoMetadadosRepository.findByIdAndTenantId(any(), any())).thenReturn(Optional.of(metaDados));

        when(planoSemanalMapper.toEntity(planoDto)).thenReturn(planoSalvo);
        when(treinoMapper.toEntity(any(TreinoPlanejadoLlmDto.class))).thenReturn(treinoPlanejado);
        when(planoSemanalRepository.save(any(PlanoSemanal.class))).thenReturn(planoSalvo);
        when(planoMetadadosRepository.save(any(PlanoMetaDados.class))).thenReturn(metaDados);

        try (MockedStatic<Hibernate> hibernateMock = mockStatic(Hibernate.class)) {
            hibernateMock.when(() -> Hibernate.initialize(any())).thenAnswer(invocation -> null);

            // When
            PlanoSemanal resultado = planoService.gerarPlanoTreino(atletaId, modoGeracao);

            // Then
            assertNotNull(resultado);
            assertEquals(planoSalvo, resultado);

            verify(atletaRepository).findByIdAndTenantId(atletaId, tenantId);
            verify(iaService).geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), eq(modoGeracao), any(), any(), any(), any());
            verify(planoSemanalRepository).save(any(PlanoSemanal.class));
            verify(planoMetadadosRepository, times(1)).save(any(PlanoMetaDados.class));
        }
    }

    @Test
    @DisplayName("Deve lançar exceção quando LLM retorna plano nulo")
    void deveLancarExcecaoQuandoLlmRetornaPlanoNulo() {
        // Given
        UUID atletaId = UUID.randomUUID();
        ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;

        Atleta atleta = criarAtletaMock(atletaId);
        PlanoMetaDados metaDados = criarPlanoMetaDadosMock();

        when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
        when(planoMetadadosService.buscarOuCriarMetadados(atleta)).thenReturn(metaDados);
        when(treinoRealizadoRepository.findByAtletaIdAndDataTreinoBetween(eq(atletaId), any(LocalDate.class), any(LocalDate.class))).thenReturn(Collections.emptyList());
        when(planoSemanalRepository.findTopByAtletaIdOrderBySemanaInicioDesc(atletaId)).thenReturn(Optional.empty());
        when(planoSemanalRepository.findTopByAtletaIdAndSemanaInicioBeforeAndStatusOrderBySemanaInicioDesc(
                any(), any(), any())).thenReturn(Optional.empty());

        when(iaService.geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), eq(modoGeracao), any(), any(), any(), any())).thenReturn(null);

        try (MockedStatic<Hibernate> hibernateMock = mockStatic(Hibernate.class)) {
            hibernateMock.when(() -> Hibernate.initialize(any())).thenAnswer(invocation -> null);

            // When & Then
            assertThrows(LLMException.class, () ->
                    planoService.gerarPlanoTreino(atletaId, modoGeracao));

            verify(iaService).geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), eq(modoGeracao), any(), any(), any(), any());
            verify(redistribuicaoHelper, never()).redistribuirTreinos(any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    @DisplayName("A chamada ao LLM da geração interativa passa pela faixa interativa do limiter")
    void geracaoInterativaPassaPeloLimiter() throws InterruptedException {
        UUID atletaId = UUID.randomUUID();
        stubsDeGeracaoCompleta(atletaId);
        when(planoSemanalRepository.save(any(PlanoSemanal.class))).thenReturn(criarPlanoSemanalMock());

        try (MockedStatic<Hibernate> hibernateMock = mockStatic(Hibernate.class)) {
            hibernateMock.when(() -> Hibernate.initialize(any())).thenAnswer(invocation -> null);

            planoService.gerarPlanoTreino(atletaId, ModoGeracaoPlano.PROXIMA_SEMANA);

            verify(llmConcurrencyLimiter).executarInterativo(any());
        }
    }

    @Test
    @DisplayName("Plano ativo na semana aborta ANTES de chamar o LLM (checagem cedo, design D3)")
    void planoAtivoNaSemanaAbortaAntesDoLlm() {
        // Given
        UUID atletaId = UUID.randomUUID();
        ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;

        Atleta atleta = criarAtletaMock(atletaId);
        PlanoMetaDados metaDados = criarPlanoMetaDadosMock();

        when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
        when(planoMetadadosService.buscarOuCriarMetadados(atleta)).thenReturn(metaDados);
        when(treinoRealizadoRepository.findByAtletaIdAndDataTreinoBetween(eq(atletaId), any(LocalDate.class), any(LocalDate.class))).thenReturn(Collections.emptyList());
        when(planoSemanalRepository.findTopByAtletaIdOrderBySemanaInicioDesc(atletaId)).thenReturn(Optional.empty());
        when(planoSemanalRepository.findTopByAtletaIdAndSemanaInicioBeforeAndStatusOrderBySemanaInicioDesc(
                any(), any(), any())).thenReturn(Optional.empty());
        when(planoSemanalRepository.existePlanoAtivoNaSemana(eq(atletaId), any(LocalDate.class), eq(tenantId)))
                .thenReturn(true);

        try (MockedStatic<Hibernate> hibernateMock = mockStatic(Hibernate.class)) {
            hibernateMock.when(() -> Hibernate.initialize(any())).thenAnswer(invocation -> null);

            // When & Then
            assertThrows(br.com.menthoros.backend.exception.PlanoJaExistenteException.class, () ->
                    planoService.gerarPlanoTreino(atletaId, modoGeracao));

            verifyNoInteractions(iaService);
            verify(planoSemanalRepository, never()).save(any(PlanoSemanal.class));
        }
    }

    @Test
    @DisplayName("Violação do índice de plano ativo no commit vira PlanoJaExistenteException (design D3)")
    void violacaoDoIndiceDePlanoAtivoViraPlanoJaExistente() {
        UUID atletaId = UUID.randomUUID();
        stubsDeGeracaoCompleta(atletaId);
        when(planoSemanalRepository.save(any(PlanoSemanal.class))).thenThrow(
                new org.springframework.dao.DataIntegrityViolationException("could not execute statement",
                        new RuntimeException("ERROR: duplicate key value violates unique constraint \""
                                + br.com.menthoros.backend.exception.PlanoJaExistenteException.INDICE_PLANO_ATIVO + "\"")));

        try (MockedStatic<Hibernate> hibernateMock = mockStatic(Hibernate.class)) {
            hibernateMock.when(() -> Hibernate.initialize(any())).thenAnswer(invocation -> null);

            assertThrows(br.com.menthoros.backend.exception.PlanoJaExistenteException.class, () ->
                    planoService.gerarPlanoTreino(atletaId, ModoGeracaoPlano.PROXIMA_SEMANA));
        }
    }

    @Test
    @DisplayName("Violação de OUTRA constraint no commit NÃO vira PlanoJaExistenteException (segue 409)")
    void violacaoDeOutraConstraintNaoEMascarada() {
        UUID atletaId = UUID.randomUUID();
        stubsDeGeracaoCompleta(atletaId);
        org.springframework.dao.DataIntegrityViolationException outra =
                new org.springframework.dao.DataIntegrityViolationException("could not execute statement",
                        new RuntimeException("ERROR: duplicate key value violates unique constraint \"uk_outra_tabela\""));
        when(planoSemanalRepository.save(any(PlanoSemanal.class))).thenThrow(outra);

        try (MockedStatic<Hibernate> hibernateMock = mockStatic(Hibernate.class)) {
            hibernateMock.when(() -> Hibernate.initialize(any())).thenAnswer(invocation -> null);

            org.springframework.dao.DataIntegrityViolationException lancada = assertThrows(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    () -> planoService.gerarPlanoTreino(atletaId, ModoGeracaoPlano.PROXIMA_SEMANA));
            assertSame(outra, lancada);
        }
    }

    /** Stubs do caminho feliz até o save do plano — compartilhados pelos testes de conflito. */
    private void stubsDeGeracaoCompleta(UUID atletaId) {
        Atleta atleta = criarAtletaMock(atletaId);
        PlanoMetaDados metaDados = criarPlanoMetaDadosMock();
        PlanoSemanalLlmDto planoDto = criarPlanoSemanalLlmDto();
        PlanoSemanal planoEntity = criarPlanoSemanalMock();
        TreinoPlanejado treinoPlanejado = criarTreinoPlanejadoMock();

        mockMetricasAgregadasEAlertas(metaDados);
        when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
        when(planoMetadadosService.buscarOuCriarMetadados(atleta)).thenReturn(metaDados);
        when(treinoRealizadoRepository.findByAtletaIdAndDataTreinoBetween(eq(atletaId), any(LocalDate.class), any(LocalDate.class))).thenReturn(Collections.emptyList());
        when(planoSemanalRepository.findTopByAtletaIdOrderBySemanaInicioDesc(atletaId)).thenReturn(Optional.empty());
        when(planoSemanalRepository.findTopByAtletaIdAndSemanaInicioBeforeAndStatusOrderBySemanaInicioDesc(
                any(), any(), any())).thenReturn(Optional.empty());
        when(iaService.geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), any(), any(), any(), any(), any())).thenReturn(planoDto);
        when(planoMetadadosRepository.findByIdAndTenantId(any(), any())).thenReturn(Optional.of(metaDados));
        when(planoSemanalMapper.toEntity(planoDto)).thenReturn(planoEntity);
        when(treinoMapper.toEntity(any(TreinoPlanejadoLlmDto.class))).thenReturn(treinoPlanejado);
        when(planoMetadadosRepository.save(any(PlanoMetaDados.class))).thenReturn(metaDados);
    }

    @Test
    @DisplayName("Deve lançar exceção quando não há treinos redistribuídos após redistribuição")
    void deveLancarExcecaoQuandoNaoHaTreinosRedistribuidosAposRedistribuicao() {
        // Given
        UUID atletaId = UUID.randomUUID();
        ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.SEMANA_ATUAL;

        Atleta atleta = criarAtletaMock(atletaId);
        PlanoMetaDados metaDados = criarPlanoMetaDadosMock();
        PlanoSemanalLlmDto planoDto = criarPlanoSemanalLlmDto();

        when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
        when(planoMetadadosService.buscarOuCriarMetadados(atleta)).thenReturn(metaDados);
        when(treinoRealizadoRepository.findByAtletaIdAndDataTreinoBetween(eq(atletaId), any(LocalDate.class), any(LocalDate.class))).thenReturn(Collections.emptyList());
        when(planoSemanalRepository.findTopByAtletaIdOrderBySemanaInicioDesc(atletaId)).thenReturn(Optional.empty());
        when(planoSemanalRepository.findTopByAtletaIdAndSemanaInicioBeforeAndStatusOrderBySemanaInicioDesc(
                any(), any(), any())).thenReturn(Optional.empty());

        when(iaService.geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), eq(modoGeracao), any(), any(), any(), any())).thenReturn(planoDto);
        when(redistribuicaoHelper.redistribuirTreinos(any(), any(), any(), any(), any(), eq(modoGeracao), any(), any()))
                .thenReturn(Collections.emptyList());

        try (MockedStatic<Hibernate> hibernateMock = mockStatic(Hibernate.class)) {
            hibernateMock.when(() -> Hibernate.initialize(any())).thenAnswer(invocation -> null);

            // When & Then
            DomainRuleViolationException exception = assertThrows(DomainRuleViolationException.class, () ->
                    planoService.gerarPlanoTreino(atletaId, modoGeracao));

            assertTrue(exception.getMessage().contains("Não foi possível gerar treinos"));

            verify(iaService).geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), eq(modoGeracao), any(), any(), any(), any());
            verify(redistribuicaoHelper).redistribuirTreinos(any(), any(), any(), any(), any(), eq(modoGeracao), any(), any());
        }
    }

    @Test
    @DisplayName("Deve lançar exceção com ID de atleta inválido")
    void deveLancarExcecaoComIdAtletaInvalido() {
        // Given
        UUID atletaId = UUID.randomUUID();
        ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;

        when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.empty());

        // When & Then
        assertThrows(RuntimeException.class, () ->
                planoService.gerarPlanoTreino(atletaId, modoGeracao));

        verify(atletaRepository).findByIdAndTenantId(atletaId, tenantId);
        verify(iaService, never()).geraPlanoSemanalAvancado(any(), any(), any(), eq(modoGeracao), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Deve lançar exceção quando LLM lança exceção durante geração")
    void deveLancarExcecaoQuandoLlmLancaExcecaoDuranteGeracao() {
        // Given
        UUID atletaId = UUID.randomUUID();
        ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;

        Atleta atleta = criarAtletaMock(atletaId);
        PlanoMetaDados metaDados = criarPlanoMetaDadosMock();

        when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
        when(planoMetadadosService.buscarOuCriarMetadados(atleta)).thenReturn(metaDados);
        when(treinoRealizadoRepository.findByAtletaIdAndDataTreinoBetween(eq(atletaId), any(LocalDate.class), any(LocalDate.class))).thenReturn(Collections.emptyList());
        when(planoSemanalRepository.findTopByAtletaIdOrderBySemanaInicioDesc(atletaId)).thenReturn(Optional.empty());
        when(planoSemanalRepository.findTopByAtletaIdAndSemanaInicioBeforeAndStatusOrderBySemanaInicioDesc(
                any(), any(), any())).thenReturn(Optional.empty());

        when(iaService.geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), eq(modoGeracao), any(), any(), any(), any()))
                .thenThrow(new LLMException("Erro na IA"));

        try (MockedStatic<Hibernate> hibernateMock = mockStatic(Hibernate.class)) {
            hibernateMock.when(() -> Hibernate.initialize(any())).thenAnswer(invocation -> null);

            // When & Then - should throw LLMException when LLM fails
            LLMException exception = assertThrows(LLMException.class, () ->
                    planoService.gerarPlanoTreino(atletaId, modoGeracao));

            assertEquals("Erro na IA", exception.getMessage());

            verify(iaService).geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), eq(modoGeracao), any(), any(), any(), any());
            verify(redistribuicaoHelper, never()).redistribuirTreinos(any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    @DisplayName("Deve funcionar com diferentes modos de geração")
    void deveFuncionarComDiferentesModosGeracao() {
        // Given
        UUID atletaId = UUID.randomUUID();
        ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.SEMANA_ATUAL;

        Atleta atleta = criarAtletaMock(atletaId);
        PlanoMetaDados metaDados = criarPlanoMetaDadosMock();
        PlanoSemanalLlmDto planoDto = criarPlanoSemanalLlmDto();
        List<TreinoPlanejadoLlmDto> treinosRedistribuidos = criarTreinosRedistribuidosMock();
        PlanoSemanal planoSalvo = criarPlanoSemanalMock();
        TreinoPlanejado treinoPlanejado = criarTreinoPlanejadoMock();

        mockMetricasAgregadasEAlertas(metaDados);

        when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
        when(planoMetadadosService.buscarOuCriarMetadados(atleta)).thenReturn(metaDados);
        when(treinoRealizadoRepository.findByAtletaIdAndDataTreinoBetween(eq(atletaId), any(LocalDate.class), any(LocalDate.class))).thenReturn(Collections.emptyList());
        when(planoSemanalRepository.findTopByAtletaIdOrderBySemanaInicioDesc(atletaId)).thenReturn(Optional.empty());
        when(planoSemanalRepository.findTopByAtletaIdAndSemanaInicioBeforeAndStatusOrderBySemanaInicioDesc(
                any(), any(), any())).thenReturn(Optional.empty());

        when(iaService.geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), eq(modoGeracao), any(), any(), any(), any())).thenReturn(planoDto);
        when(redistribuicaoHelper.redistribuirTreinos(any(), any(), any(), any(), any(), eq(modoGeracao), any(), any()))
                .thenReturn(treinosRedistribuidos);
        when(planoMetadadosRepository.findByIdAndTenantId(any(), any())).thenReturn(Optional.of(metaDados));

        when(planoSemanalMapper.toEntity(planoDto)).thenReturn(planoSalvo);
        when(treinoMapper.toEntity(any(TreinoPlanejadoLlmDto.class))).thenReturn(treinoPlanejado);
        when(planoSemanalRepository.save(any(PlanoSemanal.class))).thenReturn(planoSalvo);
        when(planoMetadadosRepository.save(any(PlanoMetaDados.class))).thenReturn(metaDados);

        try (MockedStatic<Hibernate> hibernateMock = mockStatic(Hibernate.class)) {
            hibernateMock.when(() -> Hibernate.initialize(any())).thenAnswer(invocation -> null);

            // When
            PlanoSemanal resultado = planoService.gerarPlanoTreino(atletaId, modoGeracao);

            // Then
            assertNotNull(resultado);
            assertEquals(planoSalvo, resultado);

            verify(redistribuicaoHelper).redistribuirTreinos(any(), any(), any(), any(), any(), eq(ModoGeracaoPlano.SEMANA_ATUAL), any(), any());
        }
    }

    @Test
    @DisplayName("Deve priorizar LONGO com base no histórico recente do atleta")
    void deveUsarHistoricoParaDefinirDiaDoLongo() {
        UUID atletaId = UUID.randomUUID();
        ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.SEMANA_ATUAL;

        Atleta atleta = criarAtletaMock(atletaId);
        atleta.setDiaPreferidoLongo(DiaSemana.SABADO);
        atleta.setDiasDisponiveis(List.of(DiaSemana.QUARTA, DiaSemana.DOMINGO));

        PlanoMetaDados metaDados = criarPlanoMetaDadosMock();
        PlanoSemanalLlmDto planoDto = criarPlanoSemanalLlmDto();
        List<TreinoPlanejadoLlmDto> treinosRedistribuidos = criarTreinosRedistribuidosMock();
        PlanoSemanal planoSalvo = criarPlanoSemanalMock();
        TreinoPlanejado treinoPlanejado = criarTreinoPlanejadoMock();

        TreinoRealizado longo1 = new TreinoRealizado();
        longo1.setDataTreino(LocalDate.of(2026, 4, 13));
        longo1.setDiaSemana(DiaSemana.DOMINGO);
        longo1.setTipoTreino(TipoTreino.LONGO);

        TreinoRealizado longo2 = new TreinoRealizado();
        longo2.setDataTreino(LocalDate.of(2026, 4, 6));
        longo2.setDiaSemana(DiaSemana.DOMINGO);
        longo2.setTipoTreino(TipoTreino.LONGO);

        mockMetricasAgregadasEAlertas(metaDados);

        when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
        when(planoMetadadosService.buscarOuCriarMetadados(atleta)).thenReturn(metaDados);
        when(treinoRealizadoRepository.findByAtletaIdAndDataTreinoBetween(eq(atletaId), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of(longo1, longo2));
        when(planoSemanalRepository.findTopByAtletaIdOrderBySemanaInicioDesc(atletaId)).thenReturn(Optional.empty());
        when(planoSemanalRepository.findTopByAtletaIdAndSemanaInicioBeforeAndStatusOrderBySemanaInicioDesc(
                any(), any(), any())).thenReturn(Optional.empty());

        when(treinoMapper.toOutputDto(longo1)).thenReturn(treinoRealizadoOutput(longo1));
        when(treinoMapper.toOutputDto(longo2)).thenReturn(treinoRealizadoOutput(longo2));
        when(iaService.geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), eq(modoGeracao), any(), any(), any(), any())).thenReturn(planoDto);
        when(redistribuicaoHelper.redistribuirTreinos(any(), any(), any(), any(), any(), eq(modoGeracao), eq(DiaSemana.DOMINGO), any()))
                .thenReturn(treinosRedistribuidos);
        when(planoMetadadosRepository.findByIdAndTenantId(any(), any())).thenReturn(Optional.of(metaDados));

        when(planoSemanalMapper.toEntity(planoDto)).thenReturn(planoSalvo);
        when(treinoMapper.toEntity(any(TreinoPlanejadoLlmDto.class))).thenReturn(treinoPlanejado);
        when(planoSemanalRepository.save(any(PlanoSemanal.class))).thenReturn(planoSalvo);
        when(planoMetadadosRepository.save(any(PlanoMetaDados.class))).thenReturn(metaDados);

        try (MockedStatic<Hibernate> hibernateMock = mockStatic(Hibernate.class)) {
            hibernateMock.when(() -> Hibernate.initialize(any())).thenAnswer(invocation -> null);

            PlanoSemanal resultado = planoService.gerarPlanoTreino(atletaId, modoGeracao);

            assertNotNull(resultado);
            verify(redistribuicaoHelper).redistribuirTreinos(any(), any(), any(), any(), any(), eq(modoGeracao), eq(DiaSemana.DOMINGO), any());
        }
    }

    @Test
    @DisplayName("D8: cancelado não conta em ultimosTreinos, NULL conta [task 7.7]")
    void deveExcluirCanceladoDeUltimosTreinos() {
        UUID atletaId = UUID.randomUUID();
        ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.SEMANA_ATUAL;

        Atleta atleta = criarAtletaMock(atletaId);
        atleta.setDiaPreferidoLongo(DiaSemana.SABADO);
        atleta.setDiasDisponiveis(List.of(DiaSemana.QUARTA, DiaSemana.DOMINGO));

        PlanoMetaDados metaDados = criarPlanoMetaDadosMock();
        PlanoSemanalLlmDto planoDto = criarPlanoSemanalLlmDto();
        List<TreinoPlanejadoLlmDto> treinosRedistribuidos = criarTreinosRedistribuidosMock();
        PlanoSemanal planoSalvo = criarPlanoSemanalMock();
        TreinoPlanejado treinoPlanejado = criarTreinoPlanejadoMock();

        TreinoRealizado cancelado = new TreinoRealizado();
        cancelado.setDataTreino(LocalDate.of(2026, 4, 13));
        cancelado.setDiaSemana(DiaSemana.DOMINGO);
        cancelado.setTipoTreino(TipoTreino.LONGO);
        cancelado.setStatusSincronizacao(StatusSincronizacao.CANCELADO);

        TreinoRealizado semStatus = new TreinoRealizado();
        semStatus.setDataTreino(LocalDate.of(2026, 4, 6));
        semStatus.setDiaSemana(DiaSemana.DOMINGO);
        semStatus.setTipoTreino(TipoTreino.LONGO);
        semStatus.setStatusSincronizacao(null);

        mockMetricasAgregadasEAlertas(metaDados);

        when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
        when(planoMetadadosService.buscarOuCriarMetadados(atleta)).thenReturn(metaDados);
        when(treinoRealizadoRepository.findByAtletaIdAndDataTreinoBetween(eq(atletaId), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(cancelado, semStatus));
        when(planoSemanalRepository.findTopByAtletaIdOrderBySemanaInicioDesc(atletaId)).thenReturn(Optional.empty());
        when(planoSemanalRepository.findTopByAtletaIdAndSemanaInicioBeforeAndStatusOrderBySemanaInicioDesc(
                any(), any(), any())).thenReturn(Optional.empty());

        when(treinoMapper.toOutputDto(semStatus)).thenReturn(treinoRealizadoOutput(semStatus));
        when(iaService.geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), eq(modoGeracao), any(), any(), any(), any())).thenReturn(planoDto);
        when(redistribuicaoHelper.redistribuirTreinos(any(), any(), any(), any(), any(), eq(modoGeracao), any(), any()))
                .thenReturn(treinosRedistribuidos);
        when(planoMetadadosRepository.findByIdAndTenantId(any(), any())).thenReturn(Optional.of(metaDados));

        when(planoSemanalMapper.toEntity(planoDto)).thenReturn(planoSalvo);
        when(treinoMapper.toEntity(any(TreinoPlanejadoLlmDto.class))).thenReturn(treinoPlanejado);
        when(planoSemanalRepository.save(any(PlanoSemanal.class))).thenReturn(planoSalvo);
        when(planoMetadadosRepository.save(any(PlanoMetaDados.class))).thenReturn(metaDados);

        try (MockedStatic<Hibernate> hibernateMock = mockStatic(Hibernate.class)) {
            hibernateMock.when(() -> Hibernate.initialize(any())).thenAnswer(invocation -> null);

            PlanoSemanal resultado = planoService.gerarPlanoTreino(atletaId, modoGeracao);

            assertNotNull(resultado);
            verify(treinoMapper, never()).toOutputDto(cancelado);
            verify(treinoMapper).toOutputDto(semStatus);
        }
    }

    @Test
    @DisplayName("Deve lançar exceção quando lista de treinos da LLM está vazia")
    void deveLancarExcecaoQuandoListaTreinosLlmEstaVazia() {
        // Given
        UUID atletaId = UUID.randomUUID();
        ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;

        Atleta atleta = criarAtletaMock(atletaId);
        PlanoMetaDados metaDados = criarPlanoMetaDadosMock();
        PlanoSemanalLlmDto planoDto = new PlanoSemanalLlmDto(
                22, 22, 50.0, 60.0, PlanoStatus.PLANEJADO.getValue(), "Teste Plano", Collections.emptyList()
        );

        when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
        when(planoMetadadosService.buscarOuCriarMetadados(atleta)).thenReturn(metaDados);
        when(treinoRealizadoRepository.findByAtletaIdAndDataTreinoBetween(eq(atletaId), any(LocalDate.class), any(LocalDate.class))).thenReturn(Collections.emptyList());
        when(planoSemanalRepository.findTopByAtletaIdOrderBySemanaInicioDesc(atletaId)).thenReturn(Optional.empty());
        when(planoSemanalRepository.findTopByAtletaIdAndSemanaInicioBeforeAndStatusOrderBySemanaInicioDesc(
                any(), any(), any())).thenReturn(Optional.empty());

        when(iaService.geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), eq(modoGeracao), any(), any(), any(), any())).thenReturn(planoDto);

        try (MockedStatic<Hibernate> hibernateMock = mockStatic(Hibernate.class)) {
            hibernateMock.when(() -> Hibernate.initialize(any())).thenAnswer(invocation -> null);

            // When & Then
            assertThrows(LLMException.class, () ->
                    planoService.gerarPlanoTreino(atletaId, modoGeracao));

            verify(iaService).geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), eq(modoGeracao), any(), any(), any(), any());
            verify(redistribuicaoHelper, never()).redistribuirTreinos(any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    @DisplayName("Deve lançar exceção quando atleta não tem dias disponíveis")
    void deveLancarExcecaoQuandoAtletaNaoTemDiasDisponiveis() {
        // Given
        UUID atletaId = UUID.randomUUID();
        ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.SEMANA_ATUAL;

        Atleta atleta = criarAtletaMock(atletaId);
        atleta.setDiasDisponiveis(Collections.emptyList()); // Sem dias disponíveis

        when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));

        // When & Then
        DomainRuleViolationException exception = assertThrows(DomainRuleViolationException.class, () ->
                planoService.gerarPlanoTreino(atletaId, modoGeracao));

        // Verifica que a mensagem de erro é sobre dias disponíveis
        assertTrue(exception.getMessage().contains("sem dias disponíveis"));

        // Verifica que a validação falhou antes de chamar serviços de IA ou redistribuição
        verify(atletaRepository).findByIdAndTenantId(atletaId, tenantId);
        verify(iaService, never()).geraPlanoSemanalAvancado(any(), any(), any(), eq(modoGeracao), any(), any(), any(), any());
        verify(redistribuicaoHelper, never()).redistribuirTreinos(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Deve lançar exceção quando atleta está inativo")
    void deveLancarExcecaoQuandoAtletaEstaInativo() {
        // Given
        UUID atletaId = UUID.randomUUID();
        ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;

        Atleta atleta = criarAtletaMock(atletaId);
        atleta.setAtivo(AtletaStatus.INATIVO); // Atleta inativo

        when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));

        // When & Then
        DomainRuleViolationException exception = assertThrows(DomainRuleViolationException.class, () ->
                planoService.gerarPlanoTreino(atletaId, modoGeracao));

        // Verifica que a mensagem de erro é sobre atleta inativo
        assertTrue(exception.getMessage().contains("atleta inativo"));

        // Verifica que a validação falhou antes de chamar serviços de IA
        verify(atletaRepository).findByIdAndTenantId(atletaId, tenantId);
        verify(iaService, never()).geraPlanoSemanalAvancado(any(), any(), any(), eq(modoGeracao), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Deve lançar exceção quando atleta não tem objetivo definido")
    void deveLancarExcecaoQuandoAtletaNaoTemObjetivo() {
        // Given
        UUID atletaId = UUID.randomUUID();
        ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;

        Atleta atleta = criarAtletaMock(atletaId);
        atleta.setObjetivo(null); // Sem objetivo

        when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));

        // When & Then
        DomainRuleViolationException exception = assertThrows(DomainRuleViolationException.class, () ->
                planoService.gerarPlanoTreino(atletaId, modoGeracao));

        // Verifica que a mensagem de erro é sobre objetivo
        assertTrue(exception.getMessage().contains("sem objetivo definido"));

        // Verifica que a validação falhou antes de chamar serviços de IA
        verify(atletaRepository).findByIdAndTenantId(atletaId, tenantId);
        verify(iaService, never()).geraPlanoSemanalAvancado(any(), any(), any(), eq(modoGeracao), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Deve lançar exceção quando atleta não tem nível de experiência definido")
    void deveLancarExcecaoQuandoAtletaNaoTemNivelExperiencia() {
        // Given
        UUID atletaId = UUID.randomUUID();
        ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;

        Atleta atleta = criarAtletaMock(atletaId);
        atleta.setNivelExperiencia(null); // Sem nível de experiência

        when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));

        // When & Then
        DomainRuleViolationException exception = assertThrows(DomainRuleViolationException.class, () ->
                planoService.gerarPlanoTreino(atletaId, modoGeracao));

        // Verifica que a mensagem de erro é sobre nível de experiência
        assertTrue(exception.getMessage().contains("sem nível de experiência"));

        // Verifica que a validação falhou antes de chamar serviços de IA
        verify(atletaRepository).findByIdAndTenantId(atletaId, tenantId);
        verify(iaService, never()).geraPlanoSemanalAvancado(any(), any(), any(), eq(modoGeracao), any(), any(), any(), any());
    }

    @Test
    @DisplayName("falha no ProgressaoTreinoService não impede geração do plano")
    void falhaNoProgressaoServiceNaoImpedeGeracao() {
        // Given
        UUID atletaId = UUID.randomUUID();
        ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;

        Atleta atleta = criarAtletaMock(atletaId);
        PlanoMetaDados metaDados = criarPlanoMetaDadosMock();
        PlanoSemanalLlmDto planoDto = criarPlanoSemanalLlmDto();
        PlanoSemanal planoSalvo = criarPlanoSemanalMock();
        TreinoPlanejado treinoPlanejado = criarTreinoPlanejadoMock();

        doThrow(new RuntimeException("falha simulada no serviço de progressão"))
                .when(progressaoTreinoService).calcularHistorico(atletaId);

        mockMetricasAgregadasEAlertas(metaDados);

        when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
        when(planoMetadadosService.buscarOuCriarMetadados(atleta)).thenReturn(metaDados);
        when(treinoRealizadoRepository.findByAtletaIdAndDataTreinoBetween(eq(atletaId), any(LocalDate.class), any(LocalDate.class))).thenReturn(Collections.emptyList());
        when(planoSemanalRepository.findTopByAtletaIdOrderBySemanaInicioDesc(atletaId)).thenReturn(Optional.empty());
        when(planoSemanalRepository.findTopByAtletaIdAndSemanaInicioBeforeAndStatusOrderBySemanaInicioDesc(
                any(), any(), any())).thenReturn(Optional.empty());

        when(iaService.geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), eq(modoGeracao), any(), any(), any(), any())).thenReturn(planoDto);
        when(planoMetadadosRepository.findByIdAndTenantId(any(), any())).thenReturn(Optional.of(metaDados));
        when(planoSemanalMapper.toEntity(planoDto)).thenReturn(planoSalvo);
        when(treinoMapper.toEntity(any(TreinoPlanejadoLlmDto.class))).thenReturn(treinoPlanejado);
        when(planoSemanalRepository.save(any(PlanoSemanal.class))).thenReturn(planoSalvo);
        when(planoMetadadosRepository.save(any(PlanoMetaDados.class))).thenReturn(metaDados);

        try (MockedStatic<Hibernate> hibernateMock = mockStatic(Hibernate.class)) {
            hibernateMock.when(() -> Hibernate.initialize(any())).thenAnswer(invocation -> null);

            // When
            PlanoSemanal resultado = planoService.gerarPlanoTreino(atletaId, modoGeracao);

            // Then — plano gerado normalmente mesmo sem contexto de progressão
            assertNotNull(resultado);
            verify(iaService).geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), eq(modoGeracao), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("aplicarAutoApproveSeElegivel (CA5, athlete-onboarding-baseline)")
    class AutoApproveCenarioA {

        @BeforeEach
        void habilitarAutoApprove() {
            org.springframework.test.util.ReflectionTestUtils.setField(persister, "autoApproveEnabled", true);
        }

        @Test
        @DisplayName("EXCEPTION_ONLY + skeleton sem risco -> auto-aprova o plano")
        void autoAprovaComExceptionOnlyESemRisco() {
            UUID atletaId = UUID.randomUUID();
            ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;
            configurarCenarioFelizDeGeracao(atletaId, modoGeracao);
            stubOnboardingContext(ReviewMode.EXCEPTION_ONLY);
            stubShadow(criarSkeleton(false, InjuryRiskLevel.SAFE));

            executarGeracaoDePlano(atletaId, modoGeracao);

            verify(planoReviewService).aprovarTransicao(any(PlanoSemanal.class), eq(tenantId), eq(OrigemAprovacao.AUTO_CONFIANCA_ALTA));
        }

        @Test
        @DisplayName("MANDATORY_NON_BLOCKING (Cenario B) -> mantem AGUARDANDO_REVISAO")
        void naoAutoAprovaComMandatoryNonBlocking() {
            UUID atletaId = UUID.randomUUID();
            ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;
            configurarCenarioFelizDeGeracao(atletaId, modoGeracao);
            stubOnboardingContext(ReviewMode.MANDATORY_NON_BLOCKING);
            stubShadow(criarSkeleton(false, InjuryRiskLevel.SAFE));

            executarGeracaoDePlano(atletaId, modoGeracao);

            verify(planoReviewService, never()).aprovarTransicao(any(), any(), any());
        }

        @Test
        @DisplayName("MANDATORY_BLOCKING (Cenario C) -> mantem AGUARDANDO_REVISAO")
        void naoAutoAprovaComMandatoryBlocking() {
            UUID atletaId = UUID.randomUUID();
            ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;
            configurarCenarioFelizDeGeracao(atletaId, modoGeracao);
            stubOnboardingContext(ReviewMode.MANDATORY_BLOCKING);
            stubShadow(criarSkeleton(false, InjuryRiskLevel.SAFE));

            executarGeracaoDePlano(atletaId, modoGeracao);

            verify(planoReviewService, never()).aprovarTransicao(any(), any(), any());
        }

        @Test
        @DisplayName("EXCEPTION_ONLY mas requiresCoachReview=true -> nao auto-aprova (score alto nao basta)")
        void naoAutoAprovaQuandoRequerRevisaoDoCoach() {
            UUID atletaId = UUID.randomUUID();
            ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;
            configurarCenarioFelizDeGeracao(atletaId, modoGeracao);
            stubOnboardingContext(ReviewMode.EXCEPTION_ONLY);
            stubShadow(criarSkeleton(true, InjuryRiskLevel.SAFE));

            executarGeracaoDePlano(atletaId, modoGeracao);

            verify(planoReviewService, never()).aprovarTransicao(any(), any(), any());
        }

        @Test
        @DisplayName("EXCEPTION_ONLY mas injuryRisk=HIGH_RISK -> nao auto-aprova (defesa em profundidade)")
        void naoAutoAprovaQuandoRiscoAlto() {
            UUID atletaId = UUID.randomUUID();
            ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;
            configurarCenarioFelizDeGeracao(atletaId, modoGeracao);
            stubOnboardingContext(ReviewMode.EXCEPTION_ONLY);
            stubShadow(criarSkeleton(false, InjuryRiskLevel.HIGH_RISK));

            executarGeracaoDePlano(atletaId, modoGeracao);

            verify(planoReviewService, never()).aprovarTransicao(any(), any(), any());
        }

        @Test
        @DisplayName("flag onboarding.auto-approve.enabled=false -> nunca auto-aprova mesmo elegivel")
        void naoAutoAprovaQuandoFlagDesabilitada() {
            org.springframework.test.util.ReflectionTestUtils.setField(persister, "autoApproveEnabled", false);
            UUID atletaId = UUID.randomUUID();
            ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;
            configurarCenarioFelizDeGeracao(atletaId, modoGeracao);
            stubOnboardingContext(ReviewMode.EXCEPTION_ONLY);
            stubShadow(criarSkeleton(false, InjuryRiskLevel.SAFE));

            executarGeracaoDePlano(atletaId, modoGeracao);

            verify(planoReviewService, never()).aprovarTransicao(any(), any(), any());
        }

        @Test
        @DisplayName("shadow sem resultado (Optional vazio) -> nao auto-aprova mesmo com EXCEPTION_ONLY")
        void naoAutoAprovaQuandoShadowVazio() {
            UUID atletaId = UUID.randomUUID();
            ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;
            configurarCenarioFelizDeGeracao(atletaId, modoGeracao);
            stubOnboardingContext(ReviewMode.EXCEPTION_ONLY);
            when(plannerShadowService.aplicarShadow(any(), any(), any(), any(), any(), anyBoolean(), any()))
                    .thenReturn(Optional.empty());

            executarGeracaoDePlano(atletaId, modoGeracao);

            verify(planoReviewService, never()).aprovarTransicao(any(), any(), any());
        }

        private void stubOnboardingContext(ReviewMode reviewMode) {
            when(onboardingService.montarContexto(any(), any())).thenReturn(
                    new OnboardingContext(
                            new AthleteBaseline(50.0, LocalDate.now()),
                            0.8,
                            new PlanningPolicy(reviewMode, 1.0, false),
                            new AthleteConstraints(List.of(), null, null, List.of())));
        }

        private void stubShadow(WeekPlanSkeleton skeleton) {
            when(plannerShadowService.aplicarShadow(any(), any(), any(), any(), any(), anyBoolean(), any()))
                    .thenReturn(Optional.of(skeleton));
        }

        private WeekPlanSkeleton criarSkeleton(boolean requiresCoachReview, InjuryRiskLevel risco) {
            return new WeekPlanSkeleton(
                    TrainingPhase.BUILD,
                    new WeeklyLoadTarget(300.0, 270.0, 330.0, "teste"),
                    List.of(),
                    new InjuryRiskAssessment(risco, risco == InjuryRiskLevel.HIGH_RISK, "motivo teste"),
                    new ConstraintValidationResult(true, List.of()),
                    requiresCoachReview,
                    requiresCoachReview ? "requer revisao do coach" : null,
                    LocalDate.now(),
                    "escopo-teste",
                    Optional.empty());
        }

        private void executarGeracaoDePlano(UUID atletaId, ModoGeracaoPlano modoGeracao) {
            try (MockedStatic<Hibernate> hibernateMock = mockStatic(Hibernate.class)) {
                hibernateMock.when(() -> Hibernate.initialize(any())).thenAnswer(invocation -> null);
                planoService.gerarPlanoTreino(atletaId, modoGeracao);
            }
        }
    }

    @Nested
    @DisplayName("resolverOnboardingContext (migracao de atletas legados, Seção 5.7)")
    class MigrateExisting {

        @Test
        @DisplayName("atleta legado + flag desabilitada -> nao calcula OnboardingContext")
        void naoCalculaContextoParaAtletaLegadoComFlagDesabilitada() {
            org.springframework.test.util.ReflectionTestUtils.setField(persister, "migrateExistingEnabled", false);
            UUID atletaId = UUID.randomUUID();
            ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;
            configurarCenarioFelizDeGeracao(atletaId, modoGeracao);
            when(onboardingService.possuiBaseline(atletaId, tenantId)).thenReturn(false);

            try (MockedStatic<Hibernate> hibernateMock = mockStatic(Hibernate.class)) {
                hibernateMock.when(() -> Hibernate.initialize(any())).thenAnswer(invocation -> null);
                planoService.gerarPlanoTreino(atletaId, modoGeracao);
            }

            verify(onboardingService, never()).montarContexto(any(), any());
            verify(planoReviewService, never()).aprovarTransicao(any(), any(), any());
        }

        @Test
        @DisplayName("atleta legado + flag habilitada -> calcula OnboardingContext normalmente")
        void calculaContextoParaAtletaLegadoComFlagHabilitada() {
            org.springframework.test.util.ReflectionTestUtils.setField(persister, "migrateExistingEnabled", true);
            UUID atletaId = UUID.randomUUID();
            ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;
            configurarCenarioFelizDeGeracao(atletaId, modoGeracao);
            when(onboardingService.possuiBaseline(atletaId, tenantId)).thenReturn(false);

            try (MockedStatic<Hibernate> hibernateMock = mockStatic(Hibernate.class)) {
                hibernateMock.when(() -> Hibernate.initialize(any())).thenAnswer(invocation -> null);
                planoService.gerarPlanoTreino(atletaId, modoGeracao);
            }

            verify(onboardingService).montarContexto(atletaId, tenantId);
        }

        @Test
        @DisplayName("atleta ja migrado (possui baseline) + flag desabilitada -> recalcula mesmo assim (CA3)")
        void recalculaParaAtletaJaMigradoMesmoComFlagDesabilitada() {
            org.springframework.test.util.ReflectionTestUtils.setField(persister, "migrateExistingEnabled", false);
            UUID atletaId = UUID.randomUUID();
            ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;
            configurarCenarioFelizDeGeracao(atletaId, modoGeracao);
            when(onboardingService.possuiBaseline(atletaId, tenantId)).thenReturn(true);

            try (MockedStatic<Hibernate> hibernateMock = mockStatic(Hibernate.class)) {
                hibernateMock.when(() -> Hibernate.initialize(any())).thenAnswer(invocation -> null);
                planoService.gerarPlanoTreino(atletaId, modoGeracao);
            }

            verify(onboardingService).montarContexto(atletaId, tenantId);
        }
    }

    @Nested
    @DisplayName("avaliarCalibracaoSeAplicavel (retrofit 10.4, athlete-onboarding-baseline)")
    class AvaliarCalibracaoNaGeracaoDePlano {

        @Test
        @DisplayName("skeleton presente -> chama avaliarCalibracaoSeAplicavel com o injuryRisk do shadow")
        void chamaAvaliarCalibracaoQuandoSkeletonPresente() {
            UUID atletaId = UUID.randomUUID();
            ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;
            configurarCenarioFelizDeGeracao(atletaId, modoGeracao);
            WeekPlanSkeleton skeleton = criarSkeletonCalibracao(InjuryRiskLevel.WARNING);
            when(plannerShadowService.aplicarShadow(any(), any(), any(), any(), any(), anyBoolean(), any()))
                    .thenReturn(Optional.of(skeleton));

            executarGeracaoDePlanoCalibracao(atletaId, modoGeracao);

            verify(onboardingService).avaliarCalibracaoSeAplicavel(
                    eq(atletaId), eq(tenantId), any(LocalDate.class), eq(InjuryRiskLevel.WARNING));
        }

        @Test
        @DisplayName("shadow vazio (Optional.empty) -> nao chama avaliarCalibracaoSeAplicavel")
        void naoChamaAvaliarCalibracaoQuandoShadowVazio() {
            UUID atletaId = UUID.randomUUID();
            ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;
            configurarCenarioFelizDeGeracao(atletaId, modoGeracao);
            when(plannerShadowService.aplicarShadow(any(), any(), any(), any(), any(), anyBoolean(), any()))
                    .thenReturn(Optional.empty());

            executarGeracaoDePlanoCalibracao(atletaId, modoGeracao);

            verify(onboardingService, never()).avaliarCalibracaoSeAplicavel(any(), any(), any(), any());
        }

        private WeekPlanSkeleton criarSkeletonCalibracao(InjuryRiskLevel risco) {
            return new WeekPlanSkeleton(
                    TrainingPhase.CALIBRATION,
                    new WeeklyLoadTarget(300.0, 270.0, 330.0, "teste"),
                    List.of(),
                    new InjuryRiskAssessment(risco, risco == InjuryRiskLevel.HIGH_RISK, "motivo teste"),
                    new ConstraintValidationResult(true, List.of()),
                    false,
                    null,
                    LocalDate.now(),
                    "escopo-teste",
                    Optional.empty());
        }

        private void executarGeracaoDePlanoCalibracao(UUID atletaId, ModoGeracaoPlano modoGeracao) {
            try (MockedStatic<Hibernate> hibernateMock = mockStatic(Hibernate.class)) {
                hibernateMock.when(() -> Hibernate.initialize(any())).thenAnswer(invocation -> null);
                planoService.gerarPlanoTreino(atletaId, modoGeracao);
            }
        }
    }

    private void configurarCenarioFelizDeGeracao(UUID atletaId, ModoGeracaoPlano modoGeracao) {
        Atleta atleta = criarAtletaMock(atletaId);
        PlanoMetaDados metaDados = criarPlanoMetaDadosMock();
        PlanoSemanalLlmDto planoDto = criarPlanoSemanalLlmDto();
        PlanoSemanal planoSalvo = criarPlanoSemanalMock();
        TreinoPlanejado treinoPlanejado = criarTreinoPlanejadoMock();

        mockMetricasAgregadasEAlertas(metaDados);

        when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
        when(planoMetadadosService.buscarOuCriarMetadados(atleta)).thenReturn(metaDados);
        when(treinoRealizadoRepository.findByAtletaIdAndDataTreinoBetween(eq(atletaId), any(LocalDate.class), any(LocalDate.class))).thenReturn(Collections.emptyList());
        when(planoSemanalRepository.findTopByAtletaIdOrderBySemanaInicioDesc(atletaId)).thenReturn(Optional.empty());
        when(planoSemanalRepository.findTopByAtletaIdAndSemanaInicioBeforeAndStatusOrderBySemanaInicioDesc(
                any(), any(), any())).thenReturn(Optional.empty());

        when(iaService.geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), eq(modoGeracao), any(), any(), any(), any())).thenReturn(planoDto);
        when(planoMetadadosRepository.findByIdAndTenantId(any(), any())).thenReturn(Optional.of(metaDados));

        when(planoSemanalMapper.toEntity(planoDto)).thenReturn(planoSalvo);
        when(treinoMapper.toEntity(any(TreinoPlanejadoLlmDto.class))).thenReturn(treinoPlanejado);
        when(planoSemanalRepository.save(any(PlanoSemanal.class))).thenReturn(planoSalvo);
        when(planoMetadadosRepository.save(any(PlanoMetaDados.class))).thenReturn(metaDados);
    }

    // Helper methods to create mock objects
    private Atleta criarAtletaMock(UUID atletaId) {
        Assessoria assessoria = new Assessoria();
        assessoria.setId(tenantId);

        Atleta atleta = new Atleta();
        atleta.setId(atletaId);
        atleta.setNome("João Silva");
        atleta.setEmail("joao@teste.com");
        atleta.setDiasDisponiveis(List.of(DiaSemana.SEGUNDA, DiaSemana.QUARTA, DiaSemana.SEXTA));
        atleta.setProvas(new ArrayList<>());
        atleta.setAtivo(AtletaStatus.ATIVO);
        atleta.setObjetivo("Teste objetivo");
        atleta.setNivelExperiencia(NivelExperiencia.INICIANTE);
        atleta.setAssessoria(assessoria);
        return atleta;
    }

    private PlanoMetaDados criarPlanoMetaDadosMock() {
        return PlanoMetaDados.builder()
                .id(UUID.randomUUID())
                .dataCriacao(LocalDateTime.now())
                .volumeSemanalMedio(BigDecimal.valueOf(50.0))
                .build();
    }

    private PlanoSemanalLlmDto criarPlanoSemanalLlmDto() {
        List<TreinoPlanejadoLlmDto> treinos = List.of(
                new TreinoPlanejadoLlmDto("SEGUNDA", "CONTINUO", "140-160% FCmáx", 100, 1.0, 7,
                        "Treino contínuo", "60", 10.0, "5:00-5:30/km", null),
                new TreinoPlanejadoLlmDto("TERCA", "INTERVALADO", "170-180% FCmáx", 120, 1.2, 8,
                        "Treino intervalado", "45", 12.0, "4:30-5:00/km", null),
                new TreinoPlanejadoLlmDto("QUINTA", "CONTINUO", "170-180% FCmáx", 120, 1.2, 8,
                        "Treino intervalado", "45", 12.0, "4:30-5:00/km", null),
                new TreinoPlanejadoLlmDto("SABADO", "LONGO", "170-180% FCmáx", 120, 1.2, 8,
                        "Treino intervalado", "45", 18.0, "4:30-5:00/km", null)
        );

        return new PlanoSemanalLlmDto(
                22, 22, 50.0, 60.0, PlanoStatus.PLANEJADO.getValue(), "Teste Plano", treinos
        );
    }

    private List<TreinoPlanejadoLlmDto> criarTreinosCompletos() {
        return List.of(
                new TreinoPlanejadoLlmDto("SEGUNDA", "CONTINUO", "140-160% FCmáx", 100, 1.0, 7,
                        "Treino contínuo", "60", 10.0, "5:00-5:30/km", null),
                new TreinoPlanejadoLlmDto("TERCA", "INTERVALADO", "170-180% FCmáx", 120, 1.2, 8,
                        "Treino intervalado", "45", 12.0, "4:30-5:00/km", null),
                new TreinoPlanejadoLlmDto("QUINTA", "CONTINUO", "170-180% FCmáx", 120, 1.2, 8,
                        "Treino intervalado", "45", 12.0, "4:30-5:00/km", null),
                new TreinoPlanejadoLlmDto("SABADO", "LONGO", "170-180% FCmáx", 120, 1.2, 8,
                        "Treino intervalado", "45", 18.0, "4:30-5:00/km", null)
        );
    }


    private List<TreinoPlanejadoLlmDto> criarTreinosRedistribuidosMock() {
        return List.of(
                new TreinoPlanejadoLlmDto("SEGUNDA", "CONTINUO", "140-160% FCmáx", 100, 1.0, 7,
                        "Treino contínuo", "60", 10.0, "5:00-5:30/km", null),
                new TreinoPlanejadoLlmDto("QUARTA", "INTERVALADO", "170-180% FCmáx", 120, 1.2, 8,
                        "Treino intervalado", "45", 12.0, "4:30-5:00/km", null)
        );
    }

    @Test
    @DisplayName("revisão injetada no prompt é a mesma gravada como consumida no plano")
    void revisaoDoPromptEhAMesmaGravadaNoPlano() {
        // Given
        UUID atletaId = UUID.randomUUID();
        ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;

        Atleta atleta = criarAtletaMock(atletaId);
        PlanoMetaDados metaDados = criarPlanoMetaDadosMock();
        PlanoSemanalLlmDto planoDto = criarPlanoSemanalLlmDto();
        PlanoSemanal planoSalvo = criarPlanoSemanalMock();
        TreinoPlanejado treinoPlanejado = criarTreinoPlanejadoMock();

        RevisaoSemanal revisao = new RevisaoSemanal();
        revisao.setId(UUID.randomUUID());

        mockMetricasAgregadasEAlertas(metaDados);

        when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
        when(planoMetadadosService.buscarOuCriarMetadados(atleta)).thenReturn(metaDados);
        when(treinoRealizadoRepository.findByAtletaIdAndDataTreinoBetween(eq(atletaId), any(LocalDate.class), any(LocalDate.class))).thenReturn(Collections.emptyList());
        when(planoSemanalRepository.findTopByAtletaIdOrderBySemanaInicioDesc(atletaId)).thenReturn(Optional.empty());
        when(planoSemanalRepository.findTopByAtletaIdAndSemanaInicioBeforeAndStatusOrderBySemanaInicioDesc(
                any(), any(), any())).thenReturn(Optional.empty());
        when(weeklyReviewPromptProvider.resolverParaGeracao(eq(atletaId), eq(tenantId), any(LocalDate.class)))
                .thenReturn(Optional.of(revisao));

        when(iaService.geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), eq(modoGeracao), any(), any(), any(), any()))
                .thenReturn(planoDto);
        when(planoMetadadosRepository.findByIdAndTenantId(any(), any())).thenReturn(Optional.of(metaDados));
        when(planoSemanalMapper.toEntity(planoDto)).thenReturn(planoSalvo);
        when(treinoMapper.toEntity(any(TreinoPlanejadoLlmDto.class))).thenReturn(treinoPlanejado);
        when(planoSemanalRepository.save(any(PlanoSemanal.class))).thenReturn(planoSalvo);
        when(planoMetadadosRepository.save(any(PlanoMetaDados.class))).thenReturn(metaDados);

        try (MockedStatic<Hibernate> hibernateMock = mockStatic(Hibernate.class)) {
            hibernateMock.when(() -> Hibernate.initialize(any())).thenAnswer(invocation -> null);

            // When
            PlanoSemanal resultado = planoService.gerarPlanoTreino(atletaId, modoGeracao);

            // Then — a resolução acontece UMA vez e o mesmo objeto alimenta prompt e vínculo
            verify(weeklyReviewPromptProvider, times(1))
                    .resolverParaGeracao(eq(atletaId), eq(tenantId), any(LocalDate.class));

            ArgumentCaptor<RevisaoSemanal> capturada = ArgumentCaptor.forClass(RevisaoSemanal.class);
            verify(iaService).geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), eq(modoGeracao), any(),
                    capturada.capture(), any(), any());

            assertSame(revisao, capturada.getValue());
            assertSame(revisao, resultado.getConsumedReview());
            assertEquals(ConsumedReviewOutcome.PENDING, resultado.getConsumedReviewOutcome());
        }
    }

    @Test
    @DisplayName("prompt é montado para a mesma semana em que o plano é persistido")
    void semanaDoPromptEhAMesmaDoPlanoPersistido() {
        // Given — atleta já tem plano 3 semanas à frente, então a próxima semana NÃO é a de
        // LocalDate.now().plusWeeks(1): é a do último plano + 1.
        UUID atletaId = UUID.randomUUID();
        ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;

        Atleta atleta = criarAtletaMock(atletaId);
        PlanoMetaDados metaDados = criarPlanoMetaDadosMock();
        PlanoSemanalLlmDto planoDto = criarPlanoSemanalLlmDto();
        PlanoSemanal planoSalvo = criarPlanoSemanalMock();
        TreinoPlanejado treinoPlanejado = criarTreinoPlanejadoMock();

        LocalDate semanaUltimoPlano = LocalDate.now()
                .with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY))
                .plusWeeks(3);
        PlanoSemanal ultimoPlano = criarPlanoSemanalMock();
        ultimoPlano.setSemanaInicio(semanaUltimoPlano);
        LocalDate semanaEsperada = semanaUltimoPlano.plusWeeks(1);

        mockMetricasAgregadasEAlertas(metaDados);

        when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
        when(planoMetadadosService.buscarOuCriarMetadados(atleta)).thenReturn(metaDados);
        when(treinoRealizadoRepository.findByAtletaIdAndDataTreinoBetween(eq(atletaId), any(LocalDate.class), any(LocalDate.class))).thenReturn(Collections.emptyList());
        when(planoSemanalRepository.findTopByAtletaIdOrderBySemanaInicioDesc(atletaId))
                .thenReturn(Optional.of(ultimoPlano));
        when(planoSemanalRepository.findTopByAtletaIdAndSemanaInicioBeforeAndStatusOrderBySemanaInicioDesc(
                any(), any(), any())).thenReturn(Optional.empty());

        when(iaService.geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), eq(modoGeracao), any(), any(), any(), any()))
                .thenReturn(planoDto);
        when(planoMetadadosRepository.findByIdAndTenantId(any(), any())).thenReturn(Optional.of(metaDados));
        when(planoSemanalMapper.toEntity(planoDto)).thenReturn(planoSalvo);
        when(treinoMapper.toEntity(any(TreinoPlanejadoLlmDto.class))).thenReturn(treinoPlanejado);
        when(planoSemanalRepository.save(any(PlanoSemanal.class))).thenReturn(planoSalvo);
        when(planoMetadadosRepository.save(any(PlanoMetaDados.class))).thenReturn(metaDados);

        try (MockedStatic<Hibernate> hibernateMock = mockStatic(Hibernate.class)) {
            hibernateMock.when(() -> Hibernate.initialize(any())).thenAnswer(invocation -> null);

            // When
            PlanoSemanal resultado = planoService.gerarPlanoTreino(atletaId, modoGeracao);

            // Then — a semana enviada ao prompt é a mesma persistida e a mesma usada na janela D11
            ArgumentCaptor<LocalDate> semanaPrompt = ArgumentCaptor.forClass(LocalDate.class);
            verify(iaService).geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), eq(modoGeracao), any(),
                    any(), semanaPrompt.capture(), any());
            verify(weeklyReviewPromptProvider).resolverParaGeracao(atletaId, tenantId, semanaEsperada);

            assertEquals(semanaEsperada, semanaPrompt.getValue());
            assertEquals(semanaEsperada, resultado.getSemanaInicio());
        }
    }

    @Test
    @DisplayName("sem revisão consumível o prompt recebe null e o plano nasce NOT_CONSUMED")
    void semRevisaoConsumivelPromptRecebeNull() {
        // Given
        UUID atletaId = UUID.randomUUID();
        ModoGeracaoPlano modoGeracao = ModoGeracaoPlano.PROXIMA_SEMANA;

        Atleta atleta = criarAtletaMock(atletaId);
        PlanoMetaDados metaDados = criarPlanoMetaDadosMock();
        PlanoSemanalLlmDto planoDto = criarPlanoSemanalLlmDto();
        PlanoSemanal planoSalvo = criarPlanoSemanalMock();
        TreinoPlanejado treinoPlanejado = criarTreinoPlanejadoMock();

        mockMetricasAgregadasEAlertas(metaDados);

        when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
        when(planoMetadadosService.buscarOuCriarMetadados(atleta)).thenReturn(metaDados);
        when(treinoRealizadoRepository.findByAtletaIdAndDataTreinoBetween(eq(atletaId), any(LocalDate.class), any(LocalDate.class))).thenReturn(Collections.emptyList());
        when(planoSemanalRepository.findTopByAtletaIdOrderBySemanaInicioDesc(atletaId)).thenReturn(Optional.empty());
        when(planoSemanalRepository.findTopByAtletaIdAndSemanaInicioBeforeAndStatusOrderBySemanaInicioDesc(
                any(), any(), any())).thenReturn(Optional.empty());
        when(weeklyReviewPromptProvider.resolverParaGeracao(eq(atletaId), eq(tenantId), any(LocalDate.class)))
                .thenReturn(Optional.empty());

        when(iaService.geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), eq(modoGeracao), any(), any(), any(), any()))
                .thenReturn(planoDto);
        when(planoMetadadosRepository.findByIdAndTenantId(any(), any())).thenReturn(Optional.of(metaDados));
        when(planoSemanalMapper.toEntity(planoDto)).thenReturn(planoSalvo);
        when(treinoMapper.toEntity(any(TreinoPlanejadoLlmDto.class))).thenReturn(treinoPlanejado);
        when(planoSemanalRepository.save(any(PlanoSemanal.class))).thenReturn(planoSalvo);
        when(planoMetadadosRepository.save(any(PlanoMetaDados.class))).thenReturn(metaDados);

        try (MockedStatic<Hibernate> hibernateMock = mockStatic(Hibernate.class)) {
            hibernateMock.when(() -> Hibernate.initialize(any())).thenAnswer(invocation -> null);

            // When
            PlanoSemanal resultado = planoService.gerarPlanoTreino(atletaId, modoGeracao);

            // Then
            verify(iaService).geraPlanoSemanalAvancado(eq(atleta), eq(metaDados), any(), eq(modoGeracao), any(),
                    isNull(), any(), any());
            assertNull(resultado.getConsumedReview());
            assertEquals(ConsumedReviewOutcome.NOT_CONSUMED, resultado.getConsumedReviewOutcome());
            verifyNoInteractions(eventPublisher);
        }
    }

    private PlanoSemanal criarPlanoSemanalMock() {
        PlanoSemanal plano = new PlanoSemanal();
        plano.setId(UUID.randomUUID());
        plano.setSemanaInicio(LocalDate.now().plusDays(1));
        plano.setSemanaFim(LocalDate.now().plusDays(7));
        plano.setStatus(PlanoStatus.ATIVO);
        plano.setTreinosPlanejados(new ArrayList<>());
        plano.setVolumePlanejadoKm(BigDecimal.valueOf(50.0));
        plano.setVolumeAlvoKm(BigDecimal.valueOf(50.0));
        return plano;
    }

    private TreinoRealizadoOutputDto treinoRealizadoOutput(TreinoRealizado treino) {
        return new TreinoRealizadoOutputDto(
                UUID.randomUUID(),
                treino.getDataTreino(),
                treino.getDiaSemana(),
                treino.getTipoTreino(),
                null, // 5 descricao
                null, // 6 zonaAlvo
                null, // 7 duracaoMin
                null, // 8 distanciaKm
                null, // 9 ritmoAlvo
                null, // 10 paceMedia
                null, // 11 elevacaoGanhoMetros
                null, // 12 elevacaoPerdaMetros
                null, // 13 observacao
                null, // 14 fcMedia
                null, // 15 fcMax
                null, // 16 cadenciaMedia
                null, // 17 potenciaMedia
                null, // 18 velocidadeMedia
                null, // 19 percepcaoEsforco
                null, // 20 tssCalculado
                null, // 21 metodoCalculoTss
                null, // 22 intensidadeReal
                null, // 23 runningDynamics
                null, // 24 decouplingPercentual
                null, // 25 decoupling
                null, // 26 serieEficiencia
                null, // 27 feedbackAtleta
                null, // 28 qualidadeSonoNoiteAnterior
                null, // 29 nivelEstresse
                null, // 30 nivelDor
                null, // 31 nivelFadiga
                null, // 32 nivelRecuperacao
                null, // 33 fonteDados
                null, // 34 status
                null, // 35 externalId
                null, // 36 etapasRealizadas
                null, null, null  // 37 sugestaoReclassificacao + sensacoes, feedbackRegistradoEm
        );
    }

    private TreinoPlanejado criarTreinoPlanejadoMock() {
        TreinoPlanejado treino = new TreinoPlanejado();
        treino.setId(UUID.randomUUID());
        treino.setTipoTreino(TipoTreino.CONTINUO);
        treino.setDescricao("Treino contínuo mock");
        treino.setDistanciaKm(BigDecimal.valueOf(10.0));
        treino.setDataTreino(LocalDate.now().plusDays(1));
        return treino;
    }

    private TreinoRealizado criarTreinoRealizadoComDistancia(BigDecimal distanciaKm) {
        TreinoRealizado treino = new TreinoRealizado();
        treino.setDistanciaKm(distanciaKm);
        return treino;
    }

    private PlanoSemanalOutputDto planoSemanalOutputDtoStub(double volumeRealizadoKm) {
        return PlanoSemanalOutputDto.builder()
                .id(UUID.randomUUID().toString())
                .volumeRealizadoKm(volumeRealizadoKm)
                .build();
    }

    @Nested
    @DisplayName("buscarPlanoPorAtleta")
    class BuscarPlanoPorAtleta {

        private br.com.menthoros.backend.dto.output.TreinoPlanejadoOutputDto treinoDto(UUID realizadoId) {
            return br.com.menthoros.backend.dto.output.TreinoPlanejadoOutputDto.builder()
                    .id(UUID.randomUUID())
                    .treinoRealizadoId(realizadoId)
                    .build();
        }

        private br.com.menthoros.backend.entity.AnaliseWorkout analiseComBloco(UUID realizadoId) {
            var a = new br.com.menthoros.backend.entity.AnaliseWorkout();
            a.setTreinoRealizadoId(realizadoId);
            a.setStatus(br.com.menthoros.backend.enums.AnaliseStatus.COMPLETED);
            a.setAtletaComoFoi("Saiu como planejado.");
            return a;
        }

        @Test
        @DisplayName("marca analiseAtletaDisponivel só no treino com bloco do atleta pronto")
        void marcaFlagSoNoTreinoAnalisado() {
            UUID atletaId = UUID.randomUUID();
            UUID realizadoAnalisado = UUID.randomUUID();
            UUID realizadoSemAnalise = UUID.randomUUID();
            PlanoSemanal plano = criarPlanoSemanalMock();

            when(planoSemanalRepository.findAtivosPorAtleta(atletaId, tenantId)).thenReturn(List.of(plano));
            when(treinoRealizadoRepository.findByAtletaIdAndTenantIdAndDataTreinoBetween(
                    any(), any(), any(), any())).thenReturn(List.of());
            when(planoSemanalMapper.toOutputDto(plano)).thenReturn(planoSemanalOutputDtoStub(0.0).toBuilder()
                    .treinosPlanejados(List.of(
                            treinoDto(realizadoAnalisado), treinoDto(realizadoSemAnalise), treinoDto(null)))
                    .build());
            when(aiWorkoutAnalysisRepository.findByTreinoRealizadoIdInAndTenantId(
                    List.of(realizadoAnalisado, realizadoSemAnalise), tenantId))
                    .thenReturn(List.of(analiseComBloco(realizadoAnalisado)));

            PlanoSemanalOutputDto resultado = planoService.buscarPlanoPorAtleta(atletaId, false);

            assertEquals(true, resultado.treinosPlanejados().get(0).analiseAtletaDisponivel());
            assertEquals(false, resultado.treinosPlanejados().get(1).analiseAtletaDisponivel());
            assertEquals(false, resultado.treinosPlanejados().get(2).analiseAtletaDisponivel());
        }

        @Test
        @DisplayName("kill switch desligado: todos os treinos sem flag e nenhuma consulta às análises")
        void killSwitchDesligadoTodosFalse() {
            UUID atletaId = UUID.randomUUID();
            workoutAnalysisProperties.getAthleteMessage().setEnabled(false);
            PlanoSemanal plano = criarPlanoSemanalMock();

            when(planoSemanalRepository.findAtivosPorAtleta(atletaId, tenantId)).thenReturn(List.of(plano));
            when(treinoRealizadoRepository.findByAtletaIdAndTenantIdAndDataTreinoBetween(
                    any(), any(), any(), any())).thenReturn(List.of());
            when(planoSemanalMapper.toOutputDto(plano)).thenReturn(planoSemanalOutputDtoStub(0.0).toBuilder()
                    .treinosPlanejados(List.of(treinoDto(UUID.randomUUID())))
                    .build());

            PlanoSemanalOutputDto resultado = planoService.buscarPlanoPorAtleta(atletaId, false);

            assertEquals(false, resultado.treinosPlanejados().get(0).analiseAtletaDisponivel());
            org.mockito.Mockito.verifyNoInteractions(aiWorkoutAnalysisRepository);
        }

        @Test
        @DisplayName("análise FAILED ou sem bloco não liga a flag")
        void analiseSemBlocoNaoLigaFlag() {
            UUID atletaId = UUID.randomUUID();
            UUID realizadoId = UUID.randomUUID();
            PlanoSemanal plano = criarPlanoSemanalMock();

            var semBloco = analiseComBloco(realizadoId);
            semBloco.setAtletaComoFoi(null);

            when(planoSemanalRepository.findAtivosPorAtleta(atletaId, tenantId)).thenReturn(List.of(plano));
            when(treinoRealizadoRepository.findByAtletaIdAndTenantIdAndDataTreinoBetween(
                    any(), any(), any(), any())).thenReturn(List.of());
            when(planoSemanalMapper.toOutputDto(plano)).thenReturn(planoSemanalOutputDtoStub(0.0).toBuilder()
                    .treinosPlanejados(List.of(treinoDto(realizadoId)))
                    .build());
            when(aiWorkoutAnalysisRepository.findByTreinoRealizadoIdInAndTenantId(List.of(realizadoId), tenantId))
                    .thenReturn(List.of(semBloco));

            PlanoSemanalOutputDto resultado = planoService.buscarPlanoPorAtleta(atletaId, false);

            assertEquals(false, resultado.treinosPlanejados().get(0).analiseAtletaDisponivel());
        }

        @Test
        @DisplayName("recalcula volumeRealizadoKm somando treinos reais na janela da semana, ignorando o campo persistido")
        void recalculaVolumeRealizadoSomandoTreinosDaSemana() {
            UUID atletaId = UUID.randomUUID();
            PlanoSemanal plano = criarPlanoSemanalMock();
            // Campo persistido propositalmente desatualizado/congelado — deve ser ignorado.
            plano.setVolumeRealizadoKm(BigDecimal.ZERO);

            when(planoSemanalRepository.findAtivosPorAtleta(atletaId, tenantId))
                    .thenReturn(List.of(plano));
            when(treinoRealizadoRepository.findByAtletaIdAndTenantIdAndDataTreinoBetween(
                    atletaId, tenantId, plano.getSemanaInicio(), plano.getSemanaFim()))
                    .thenReturn(List.of(
                            criarTreinoRealizadoComDistancia(BigDecimal.valueOf(10.0)),
                            criarTreinoRealizadoComDistancia(BigDecimal.valueOf(5.5))));
            when(planoSemanalMapper.toOutputDto(plano)).thenReturn(planoSemanalOutputDtoStub(0.0));

            PlanoSemanalOutputDto resultado = planoService.buscarPlanoPorAtleta(atletaId, false);

            assertEquals(15.5, resultado.volumeRealizadoKm());
        }

        @Test
        @DisplayName("retorna volumeRealizadoKm zero quando nao ha treinos realizados na semana")
        void retornaZeroQuandoSemTreinosNaSemana() {
            UUID atletaId = UUID.randomUUID();
            PlanoSemanal plano = criarPlanoSemanalMock();

            when(planoSemanalRepository.findAtivosPorAtleta(atletaId, tenantId))
                    .thenReturn(List.of(plano));
            when(treinoRealizadoRepository.findByAtletaIdAndTenantIdAndDataTreinoBetween(
                    atletaId, tenantId, plano.getSemanaInicio(), plano.getSemanaFim()))
                    .thenReturn(List.of());
            when(planoSemanalMapper.toOutputDto(plano)).thenReturn(planoSemanalOutputDtoStub(99.0));

            PlanoSemanalOutputDto resultado = planoService.buscarPlanoPorAtleta(atletaId, false);

            assertEquals(0.0, resultado.volumeRealizadoKm());
        }

        @Test
        @DisplayName("trata distanciaKm nula como zero na soma")
        void trataDistanciaNulaComoZero() {
            UUID atletaId = UUID.randomUUID();
            PlanoSemanal plano = criarPlanoSemanalMock();

            when(planoSemanalRepository.findAtivosPorAtleta(atletaId, tenantId))
                    .thenReturn(List.of(plano));
            when(treinoRealizadoRepository.findByAtletaIdAndTenantIdAndDataTreinoBetween(
                    atletaId, tenantId, plano.getSemanaInicio(), plano.getSemanaFim()))
                    .thenReturn(List.of(
                            criarTreinoRealizadoComDistancia(null),
                            criarTreinoRealizadoComDistancia(BigDecimal.valueOf(7.0))));
            when(planoSemanalMapper.toOutputDto(plano)).thenReturn(planoSemanalOutputDtoStub(0.0));

            PlanoSemanalOutputDto resultado = planoService.buscarPlanoPorAtleta(atletaId, false);

            assertEquals(7.0, resultado.volumeRealizadoKm());
        }

        @Test
        @DisplayName("D8: treino CANCELADO não conta no volumeRealizadoKm; status null conta [achado /qa Bloco 2]")
        void treinoCanceladoNaoContaNoVolumeRealizado() {
            UUID atletaId = UUID.randomUUID();
            PlanoSemanal plano = criarPlanoSemanalMock();

            TreinoRealizado cancelado = criarTreinoRealizadoComDistancia(BigDecimal.valueOf(10.0));
            cancelado.setStatusSincronizacao(StatusSincronizacao.CANCELADO);
            TreinoRealizado semStatus = criarTreinoRealizadoComDistancia(BigDecimal.valueOf(5.5));
            semStatus.setStatusSincronizacao(null);

            when(planoSemanalRepository.findAtivosPorAtleta(atletaId, tenantId))
                    .thenReturn(List.of(plano));
            when(treinoRealizadoRepository.findByAtletaIdAndTenantIdAndDataTreinoBetween(
                    atletaId, tenantId, plano.getSemanaInicio(), plano.getSemanaFim()))
                    .thenReturn(List.of(cancelado, semStatus));
            when(planoSemanalMapper.toOutputDto(plano)).thenReturn(planoSemanalOutputDtoStub(0.0));

            PlanoSemanalOutputDto resultado = planoService.buscarPlanoPorAtleta(atletaId, false);

            assertEquals(5.5, resultado.volumeRealizadoKm());
        }

        @Test
        @DisplayName("apenasAprovados=true busca o plano aprovado mais recente e recalcula o volume")
        void apenasAprovadosBuscaPlanoAprovadoMaisRecente() {
            UUID atletaId = UUID.randomUUID();
            PlanoSemanal plano = criarPlanoSemanalMock();

            when(planoSemanalRepository.findVisiveisParaAtletaOrderBySemanaInicioDesc(atletaId, tenantId))
                    .thenReturn(List.of(plano));
            when(treinoRealizadoRepository.findByAtletaIdAndTenantIdAndDataTreinoBetween(
                    atletaId, tenantId, plano.getSemanaInicio(), plano.getSemanaFim()))
                    .thenReturn(List.of(criarTreinoRealizadoComDistancia(BigDecimal.valueOf(9.0))));
            when(planoSemanalMapper.toOutputDto(plano)).thenReturn(planoSemanalOutputDtoStub(0.0));

            PlanoSemanalOutputDto resultado = planoService.buscarPlanoPorAtleta(atletaId, true);

            assertEquals(9.0, resultado.volumeRealizadoKm());
            verify(planoSemanalRepository, never()).findAtivosPorAtleta(any(), any());
        }

        @Test
        @DisplayName("apenasAprovados=true lanca ResourceNotFoundException quando nao ha plano aprovado")
        void apenasAprovadosLancaExcecaoQuandoNaoHaPlanoAprovado() {
            UUID atletaId = UUID.randomUUID();

            when(planoSemanalRepository.findVisiveisParaAtletaOrderBySemanaInicioDesc(atletaId, tenantId))
                    .thenReturn(List.of());

            assertThrows(ResourceNotFoundException.class,
                    () -> planoService.buscarPlanoPorAtleta(atletaId, true));
            verifyNoInteractions(treinoRealizadoRepository);
        }

        @Test
        @DisplayName("apenasAprovados=false lanca ResourceNotFoundException quando atleta nao tem plano")
        void apenasAprovadosFalseLancaExcecaoQuandoAtletaNaoTemPlano() {
            UUID atletaId = UUID.randomUUID();

            when(planoSemanalRepository.findAtivosPorAtleta(atletaId, tenantId))
                    .thenReturn(List.of());

            assertThrows(ResourceNotFoundException.class,
                    () -> planoService.buscarPlanoPorAtleta(atletaId, false));
            verifyNoInteractions(treinoRealizadoRepository);
        }
    }
}
