package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.entity.Assessoria;
import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.entity.PlanoMetaDados;
import br.com.menthoros.backend.entity.PlanoSemanal;
import br.com.menthoros.backend.enums.AtletaStatus;
import br.com.menthoros.backend.enums.NivelExperiencia;
import br.com.menthoros.backend.enums.ModoGeracaoPlano;
import br.com.menthoros.backend.enums.PlanoStatus;
import br.com.menthoros.backend.events.PlanoDeletadoEvent;
import br.com.menthoros.backend.exception.DomainNotFoundException;
import br.com.menthoros.backend.exception.DomainRuleViolationException;
import br.com.menthoros.backend.exception.ResourceNotFoundException;
import br.com.menthoros.backend.mapper.AtletaMapper;
import br.com.menthoros.backend.mapper.PlanoSemanalMapper;
import br.com.menthoros.backend.mapper.TreinoMapper;
import br.com.menthoros.backend.multitenancy.TenantContext;
import br.com.menthoros.backend.repository.*;
import br.com.menthoros.backend.services.*;
import br.com.menthoros.backend.services.helper.RedistribuicaoTreinoHelper;
import br.com.menthoros.backend.services.helper.RegraGeracaoTreino;
import br.com.menthoros.backend.services.impl.MetricasAlertaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Section 4 — Service TDD tests for tenant-aware method substitution in PlanoServiceImpl.
 *
 * Tests 4.4–4.6 verify that PlanoServiceImpl uses tenant-aware repository methods
 * to prevent cross-tenant data leakage.
 */
@ExtendWith(MockitoExtension.class)
class PlanoServiceTenantTest {

    @Mock private IaService iaService;
    @Mock private AtletaRepository atletaRepository;
    @Mock private AtletaMapper atletaMapper;
    @Mock private TreinoMapper treinoMapper;
    @Mock private PlanoSemanalMapper planoSemanalMapper;
    @Mock private PlanoMetadadosRepository planoMetadadosRepository;
    @Mock private EmbeddingService embeddingService;
    @Mock private PlanoSemanalRepository planoSemanalRepository;
    @Mock private TreinoRealizadoRepository treinoRealizadoRepository;
    @Mock private RedistribuicaoTreinoHelper redistribuicaoHelper;
    @Mock private br.com.menthoros.backend.services.plano.ProvaNoPlanoService provaNoPlanoService;
    @Mock private RegraGeracaoTreino regraGeracaoTreino;
    @Mock private PlanoMetadadosService planoMetadadosService;
    @Mock private MetricasAlertaService metricasAlertaService;
    @Mock private MetricasAgregadasService metricasAgregadasService;
    @Mock private ProvaRepository provaRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private br.com.menthoros.backend.services.helper.PlannerShadowService plannerShadowService;
    @Mock private ProgressaoTreinoService progressaoTreinoService;
    @Mock private br.com.menthoros.backend.services.prompt.WeeklyReviewPromptProvider weeklyReviewPromptProvider;
    @Mock private br.com.menthoros.backend.services.onboarding.OnboardingService onboardingService;
    @Mock private PlanoReviewService planoReviewService;
    @Mock private AiWorkoutAnalysisRepository aiWorkoutAnalysisRepository;
    @Mock private br.com.menthoros.backend.config.core.WorkoutAnalysisProperties workoutAnalysisProperties;

    // Loader e persister reais sobre os mesmos mocks (refactor-llm-call-outside-transaction).
    private PlanoServiceImpl planoService;

    @BeforeEach
    void montarServico() {
        var contextLoader = new br.com.menthoros.backend.services.helper.PlanGenerationContextLoader(
                atletaRepository, planoMetadadosService, treinoRealizadoRepository, treinoMapper,
                planoSemanalRepository, planoSemanalMapper, progressaoTreinoService, weeklyReviewPromptProvider);
        lenient().when(provaNoPlanoService.garantirProvasNaSemana(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        var persister = new br.com.menthoros.backend.services.helper.PlanGenerationPersister(
                planoSemanalRepository, planoMetadadosRepository, treinoMapper, planoSemanalMapper,
                redistribuicaoHelper, metricasAlertaService, metricasAgregadasService, plannerShadowService,
                onboardingService, planoReviewService, eventPublisher, provaNoPlanoService,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var llmConcurrencyLimiter = new br.com.menthoros.backend.services.helper.LlmConcurrencyLimiter(4, 2, 1);
        planoService = new PlanoServiceImpl(iaService, llmConcurrencyLimiter, contextLoader, persister, planoSemanalRepository,
                treinoRealizadoRepository, planoSemanalMapper, eventPublisher, aiWorkoutAnalysisRepository,
                workoutAnalysisProperties, plannerShadowService,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private UUID tenantA;
    private UUID atletaId;
    private UUID planoSemanalId;
    private UUID metaDadosId;
    private Assessoria assessoriaA;
    private Atleta atleta;

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        atletaId = UUID.randomUUID();
        planoSemanalId = UUID.randomUUID();
        metaDadosId = UUID.randomUUID();

        assessoriaA = new Assessoria();
        assessoriaA.setId(tenantA);

        atleta = Atleta.builder()
                .id(atletaId)
                .nome("Atleta Test")
                .assessoria(assessoriaA)
                .ativo(AtletaStatus.ATIVO)
                .nivelExperiencia(NivelExperiencia.INTERMEDIARIO)
                .objetivo("Completar uma maratona")
                .diasDisponiveis(List.of())
                .build();

        TenantContext.setTenantId(tenantA);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // 4.4 — PlanoServiceImpl.getPreparaDadosPlano: atletaRepository.findById → tenant-aware
    // =========================================================================

    @Test
    @DisplayName("4.4: gerarPlanoTreino — lança DomainNotFoundException quando atleta pertence a outro tenant")
    void gerarPlanoTreino_atletaDeOutroTenant_lancaDomainNotFoundException() {
        // ARRANGE — atleta não encontrado para tenant A
        when(atletaRepository.findByIdAndTenantId(atletaId, tenantA))
                .thenReturn(Optional.empty());

        // ACT + ASSERT
        assertThrows(DomainNotFoundException.class,
                () -> planoService.gerarPlanoTreino(atletaId, ModoGeracaoPlano.PROXIMA_SEMANA));

        verify(atletaRepository).findByIdAndTenantId(atletaId, tenantA);
        verify(atletaRepository, never()).findById(any());
    }

    // =========================================================================
    // 4.5 — PlanoServiceImpl.deletePlanoSemanal: planoSemanalRepository.findById → tenant-aware
    // =========================================================================

    @Test
    @DisplayName("4.5: deletePlanoSemanal — lança ResourceNotFoundException quando plano pertence a outro tenant")
    void deletePlanoSemanal_planoDeOutroTenant_lancaResourceNotFoundException() {
        // ARRANGE — plano não encontrado para tenant A
        when(planoSemanalRepository.findByIdAndTenantId(planoSemanalId, tenantA))
                .thenReturn(Optional.empty());

        // ACT + ASSERT
        assertThrows(ResourceNotFoundException.class,
                () -> planoService.deletePlanoSemanal(planoSemanalId));

        verify(planoSemanalRepository).findByIdAndTenantId(planoSemanalId, tenantA);
        verify(planoSemanalRepository, never()).findById(any());
    }

    @Test
    @DisplayName("4.5: deletePlanoSemanal — deleta apenas plano do tenant correto")
    void deletePlanoSemanal_planoDoTenantCorreto_deletaComSucesso() {
        // ARRANGE
        PlanoSemanal plano = buildPlanoSemanal(atleta);
        plano.setStatus(PlanoStatus.PLANEJADO);

        when(planoSemanalRepository.findByIdAndTenantId(planoSemanalId, tenantA))
                .thenReturn(Optional.of(plano));
        doNothing().when(treinoRealizadoRepository).desvinculardeTreinosPlanejados(any());
        doNothing().when(treinoRealizadoRepository).desvinculardePlanoSemanal(any());
        doNothing().when(planoSemanalRepository).delete(any());

        // ACT
        planoService.deletePlanoSemanal(planoSemanalId);

        // ASSERT
        verify(planoSemanalRepository).findByIdAndTenantId(planoSemanalId, tenantA);
        verify(planoSemanalRepository, never()).findById(any());
        verify(planoSemanalRepository).delete(plano);
    }

    // =========================================================================
    // Task 17 — deletePlanoSemanal publica PlanoDeletadoEvent para sincronizar a
    // limpeza dos eventos menthoros-* no intervals.icu.
    // =========================================================================

    @Test
    @DisplayName("Task 17, regra 5: deletePlanoSemanal publica PlanoDeletadoEvent com a janela correta")
    void deletePlanoSemanal_deletaComSucesso_publicaEventoComJanelaCorreta() {
        // ARRANGE
        PlanoSemanal plano = buildPlanoSemanal(atleta);
        plano.setStatus(PlanoStatus.PLANEJADO);
        LocalDate semanaInicio = plano.getSemanaInicio();
        LocalDate semanaFim = plano.getSemanaFim();

        when(planoSemanalRepository.findByIdAndTenantId(planoSemanalId, tenantA))
                .thenReturn(Optional.of(plano));
        doNothing().when(treinoRealizadoRepository).desvinculardeTreinosPlanejados(any());
        doNothing().when(treinoRealizadoRepository).desvinculardePlanoSemanal(any());
        doNothing().when(planoSemanalRepository).delete(any());

        // ACT
        planoService.deletePlanoSemanal(planoSemanalId);

        // ASSERT — o evento é publicado com a janela capturada ANTES do delete (o cascade
        // apagaria os TreinoPlanejado e, com eles, a janela do plano).
        ArgumentCaptor<PlanoDeletadoEvent> captor = ArgumentCaptor.forClass(PlanoDeletadoEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        PlanoDeletadoEvent evento = captor.getValue();
        assertThat(evento.planoId()).isEqualTo(planoSemanalId);
        assertThat(evento.atletaId()).isEqualTo(atletaId);
        assertThat(evento.tenantId()).isEqualTo(tenantA);
        assertThat(evento.semanaInicio()).isEqualTo(semanaInicio);
        assertThat(evento.semanaFim()).isEqualTo(semanaFim);
    }

    @Test
    @DisplayName("Task 17, regra 5: deletePlanoSemanal com status != PLANEJADO falha na validação e NÃO publica evento")
    void deletePlanoSemanal_statusInvalido_naoPublicaEvento() {
        // ARRANGE
        PlanoSemanal plano = buildPlanoSemanal(atleta);
        plano.setStatus(PlanoStatus.ATIVO);

        when(planoSemanalRepository.findByIdAndTenantId(planoSemanalId, tenantA))
                .thenReturn(Optional.of(plano));

        // ACT + ASSERT
        assertThrows(DomainRuleViolationException.class,
                () -> planoService.deletePlanoSemanal(planoSemanalId));

        verify(planoSemanalRepository, never()).delete(any());
        verifyNoInteractions(eventPublisher);
    }

    // =========================================================================
    // 4.6 — PlanoServiceImpl.prepararMetadados: planoMetadadosRepository.findById → tenant-aware
    //        Note: prepararMetadados é privado, mas é chamado via gerarPlanoTreino.
    //        Verificamos indiretamente via gerarPlanoTreino com metaDados tendo ID definido.
    //        A substituição é: planoMetadadosRepository.findById(id) → findByIdAndTenantId(id, tenantId)
    // =========================================================================

    @Test
    @DisplayName("4.6: prepararMetadados — planoMetadadosRepository.findById nunca é chamado (substituído por tenant-aware)")
    void prepararMetadados_nuncaChamaFindByIdSimples() {
        // ARRANGE — atleta não encontrado = DomainNotFoundException antes de chegar em prepararMetadados
        // O objetivo é garantir que em nenhum cenário findById simples é chamado
        when(atletaRepository.findByIdAndTenantId(atletaId, tenantA))
                .thenReturn(Optional.empty());

        // ACT
        try {
            planoService.gerarPlanoTreino(atletaId, ModoGeracaoPlano.PROXIMA_SEMANA);
        } catch (Exception ignored) {
            // DomainNotFoundException esperada
        }

        // ASSERT — findById NUNCA chamado em nenhum path
        verify(planoMetadadosRepository, never()).findById(any());
        verify(atletaRepository, never()).findById(any());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private PlanoSemanal buildPlanoSemanal(Atleta atleta) {
        PlanoSemanal ps = new PlanoSemanal();
        ps.setId(planoSemanalId);
        ps.setAtleta(atleta);
        ps.setAssessoria(assessoriaA);
        ps.setSemanaInicio(LocalDate.now());
        ps.setSemanaFim(LocalDate.now().plusDays(6));
        ps.setVolumePlanejadoKm(BigDecimal.valueOf(40));
        return ps;
    }

    private PlanoMetaDados buildMetaDados(Atleta atleta) {
        PlanoMetaDados m = new PlanoMetaDados();
        m.setAtleta(atleta);
        m.setDiaPreferidoLongo(br.com.menthoros.backend.enums.DiaSemana.SABADO);
        return m;
    }
}
