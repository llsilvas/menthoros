package br.com.menthoros.backend.services.helper;

import br.com.menthoros.backend.domain.compliance.SkeletonComplianceChecker;
import br.com.menthoros.backend.domain.planner.ConstraintValidator;
import br.com.menthoros.backend.domain.planner.InjuryPolicyResolver;
import br.com.menthoros.backend.domain.planner.InjuryRiskEvaluator;
import br.com.menthoros.backend.domain.planner.LoadTargetResolver;
import br.com.menthoros.backend.domain.planner.PeriodizationPlanner;
import br.com.menthoros.backend.domain.planner.PlannerEngine;
import br.com.menthoros.backend.domain.planner.TaperStrategy;
import br.com.menthoros.backend.dto.DecisaoProgressao;
import br.com.menthoros.backend.dto.ProgressaoHistoricoResumo;
import br.com.menthoros.backend.dto.input.DadosPlanoDto;
import br.com.menthoros.backend.dto.llm.PlanoSemanalLlmDto;
import br.com.menthoros.backend.dto.llm.TreinoPlanejadoLlmDto;
import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.entity.PlanoSemanal;
import br.com.menthoros.backend.entity.Prova;
import br.com.menthoros.backend.entity.TreinoPlanejado;
import br.com.menthoros.backend.enums.DiaSemana;
import br.com.menthoros.backend.enums.DistanciaProva;
import br.com.menthoros.backend.enums.EstadoProgressao;
import br.com.menthoros.backend.enums.NivelExperiencia;
import br.com.menthoros.backend.enums.ProvaStatus;
import br.com.menthoros.backend.enums.TipoTreino;
import br.com.menthoros.backend.services.ProgressaoTreinoService;
import br.com.menthoros.backend.services.prompt.PeriodizacaoPromptFormatter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * design.md Decisao 10 (shadow) + CA11 (isolamento de erro) + CA12 (shadow nao altera o plano)
 * + CA16 (divergencia de fase). Colaboradores de dominio (PlannerEngine e afins) sao
 * instancias reais — este teste exercita a integracao de verdade, nao mocka o motor.
 */
@ExtendWith(MockitoExtension.class)
class PlannerShadowServiceTest {

    @Mock
    private ProgressaoTreinoService progressaoTreinoService;
    @Mock
    private PeriodizacaoPromptFormatter periodizacaoPromptFormatter;

    private SimpleMeterRegistry meterRegistry;
    private final LocalDate semanaInicio = LocalDate.of(2026, 3, 9);

    private PlannerShadowService shadowHabilitado;
    private PlannerShadowService shadowDesabilitado;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        PlannerEngine engine = new PlannerEngine(
                new PeriodizationPlanner(), new LoadTargetResolver(), new TaperStrategy(),
                new InjuryRiskEvaluator(), new InjuryPolicyResolver(), new ConstraintValidator());
        SkeletonComplianceChecker checker = new SkeletonComplianceChecker();
        ObjectMapper objectMapper = new ObjectMapper();

        shadowHabilitado = new PlannerShadowService(engine, checker, progressaoTreinoService,
                periodizacaoPromptFormatter, meterRegistry, objectMapper, true, 30);
        shadowDesabilitado = new PlannerShadowService(engine, checker, progressaoTreinoService,
                periodizacaoPromptFormatter, meterRegistry, objectMapper, false, 30);
    }

    @Nested
    @DisplayName("aplicarShadow — shadow=false (default)")
    class ShadowDesabilitado {

        @Test
        @DisplayName("planner_enabled=false persistido, nenhum outro campo de auditoria calculado")
        void shadowDesabilitadoSoMarcaPlannerEnabledFalse() {
            PlanoSemanal plano = planoBase();

            shadowDesabilitado.aplicarShadow(plano, planoGerado(), dadosPlano(atletaBase()), decisaoNeutra(), semanaInicio, false);

            assertThat(plano.getPlannerEnabled()).isFalse();
            assertThat(plano.getPlannerVersion()).isNull();
            assertThat(plano.getPlannerPhase()).isNull();
            assertThat(meterRegistry.getMeters()).isEmpty();
        }
    }

    @Nested
    @DisplayName("aplicarShadow — shadow=true (CA10, CA12)")
    class ShadowHabilitado {

        @Test
        @DisplayName("persiste auditoria completa sem alterar os treinos ja redistribuidos (CA12)")
        void persisteAuditoriaSemAlterarPlano() {
            when(progressaoTreinoService.calcularHistorico(any())).thenReturn(historico(-2.0, 45.0, 0));
            when(periodizacaoPromptFormatter.determinarFasePreparacao(anyInt())).thenReturn("BUILD");
            Atleta atleta = atletaBase();
            PlanoSemanal plano = planoBase();
            List<TreinoPlanejado> treinosOriginais = plano.getTreinosPlanejados();

            shadowHabilitado.aplicarShadow(plano, planoGerado(), dadosPlano(atleta), decisaoNeutra(), semanaInicio, false);

            assertThat(plano.getPlannerEnabled()).isFalse();
            assertThat(plano.getPlannerVersion()).isEqualTo("planner-v1");
            assertThat(plano.getPlannerPhase()).isEqualTo("BUILD"); // prova a 70 dias -> BUILD (deterministico)
            assertThat(plano.getPlannerComplianceStatus()).isEqualTo("VIOLATIONS_DETECTED"); // TSS gerado (60) fora da faixa do alvo (~315)
            assertThat(plano.getPlannerSkeletonHash()).hasSize(64); // SHA-256 hex
            assertThat(plano.getPlannerMetadataJson()).contains("\"phase\"");
            assertThat(plano.getTreinosPlanejados()).isSameAs(treinosOriginais); // shadow nao mutou os treinos
        }

        @Test
        @DisplayName("registra planner.generated.count com as tags de fase, versao e batch")
        void registraMetricaDeGeracao() {
            when(progressaoTreinoService.calcularHistorico(any())).thenReturn(historico(-2.0, 45.0, 0));
            when(periodizacaoPromptFormatter.determinarFasePreparacao(anyInt())).thenReturn("BUILD");

            shadowHabilitado.aplicarShadow(planoBase(), planoGerado(), dadosPlano(atletaBase()), decisaoNeutra(), semanaInicio, true);

            assertThat(meterRegistry.find("planner.generated.count").counter()).isNotNull();
            assertThat(meterRegistry.find("planner.generated.count").tag("batch", "true").counter()).isNotNull();
            assertThat(meterRegistry.find("planner.generated.count").tag("plannerVersion", "planner-v1").counter()).isNotNull();
        }

        @Test
        @DisplayName("TSB abaixo de -30 registra planner.requires_coach_review.count com o motivo fisiologico")
        void registraMetricaDeReviewQuandoTsbBaixo() {
            when(progressaoTreinoService.calcularHistorico(any())).thenReturn(historico(-35.0, 45.0, 0));
            when(periodizacaoPromptFormatter.determinarFasePreparacao(anyInt())).thenReturn("BUILD");

            PlanoSemanal plano = planoBase();
            shadowHabilitado.aplicarShadow(plano, planoGerado(), dadosPlano(atletaBase()), decisaoNeutra(), semanaInicio, false);

            assertThat(meterRegistry.find("planner.requires_coach_review.count")
                    .tag("reason", "TSB abaixo de -30 (fadiga acumulada alta)").counter()).isNotNull();
            assertThat(plano.getPlannerMetadataJson()).contains("TSB abaixo de -30");
        }

        @Test
        @DisplayName("lesao ativa registra planner.requires_coach_review.count com o motivo de lesao, nao 'OUTRO'")
        void registraMetricaDeReviewComMotivoDeLesao() {
            when(progressaoTreinoService.calcularHistorico(any())).thenReturn(historico(-2.0, 45.0, 0));
            when(periodizacaoPromptFormatter.determinarFasePreparacao(anyInt())).thenReturn("RECOVERY");
            Atleta atletaComLesao = atletaBase().toBuilder().temLesao(true).build();

            PlanoSemanal plano = planoBase();
            shadowHabilitado.aplicarShadow(plano, planoGerado(), dadosPlano(atletaComLesao), decisaoNeutra(), semanaInicio, false);

            assertThat(meterRegistry.find("planner.requires_coach_review.count")
                    .tag("reason", "INJURY_ACTIVE").counter()).isNotNull();
            assertThat(meterRegistry.find("planner.requires_coach_review.count")
                    .tag("reason", "OUTRO").counter()).isNull();
            assertThat(plano.getPlannerMetadataJson()).contains("INJURY_ACTIVE");
        }

        @Test
        @DisplayName("TSS do plano gerado fora da faixa registra planner.compliance.hypothetical_failure.count")
        void registraMetricaDeComplianceQuandoTssForaDaFaixa() {
            when(progressaoTreinoService.calcularHistorico(any())).thenReturn(historico(-2.0, 45.0, 0));
            when(periodizacaoPromptFormatter.determinarFasePreparacao(anyInt())).thenReturn("BUILD");
            // CTL 45 -> alvo semanal ~315 TSS; planoGerado() tem so 60 TSS -> fora da faixa +-10%.

            shadowHabilitado.aplicarShadow(planoBase(), planoGerado(), dadosPlano(atletaBase()), decisaoNeutra(), semanaInicio, false);

            // TSS_FORA_DA_FAIXA e check do estagio 2 (soft) desde a calibracao 2026-09-11 -> tag stage=POST.
            assertThat(meterRegistry.find("planner.compliance.hypothetical_failure.count")
                    .tag("reason", "TSS_FORA_DA_FAIXA").tag("stage", "POST").counter()).isNotNull();
        }
    }

    @Nested
    @DisplayName("aplicarShadow — divergencia de fase (CA16)")
    class DivergenciaDeFase {

        @Test
        @DisplayName("planner e formatter concordando na fase nao incrementa a divergencia")
        void semDivergenciaNaoIncrementaContador() {
            when(progressaoTreinoService.calcularHistorico(any())).thenReturn(historico(-2.0, 45.0, 0));
            // atletaBase() tem prova a 70 dias -> PlannerEngine resolve BUILD; formatter concorda.
            when(periodizacaoPromptFormatter.determinarFasePreparacao(anyInt())).thenReturn("BUILD");

            shadowHabilitado.aplicarShadow(planoBase(), planoGerado(), dadosPlano(atletaBase()), decisaoNeutra(), semanaInicio, false);

            assertThat(meterRegistry.find("planner.phase.divergence.count").counter()).isNull();
        }

        @Test
        @DisplayName("planner e formatter divergindo na fase incrementa planner.phase.divergence.count")
        void comDivergenciaIncrementaContador() {
            when(progressaoTreinoService.calcularHistorico(any())).thenReturn(historico(-2.0, 45.0, 0));
            // PlannerEngine resolve BUILD (70 dias); formatter (mock) diz TAPER -> divergencia.
            when(periodizacaoPromptFormatter.determinarFasePreparacao(anyInt())).thenReturn("TAPER");

            shadowHabilitado.aplicarShadow(planoBase(), planoGerado(), dadosPlano(atletaBase()), decisaoNeutra(), semanaInicio, false);

            assertThat(meterRegistry.find("planner.phase.divergence.count")
                    .tag("plannerPhase", "BUILD").tag("formatterPhase", "TAPER").counter()).isNotNull();
        }
    }

    @Nested
    @DisplayName("aplicarShadow — isolamento de erro (CA11)")
    class IsolamentoDeErro {

        @Test
        @DisplayName("excecao dentro do shadow nao propaga; planner.shadow.error.count incrementa")
        void excecaoNoShadowNaoPropaga() {
            when(progressaoTreinoService.calcularHistorico(any())).thenThrow(new RuntimeException("falha simulada"));
            PlanoSemanal plano = planoBase();

            shadowHabilitado.aplicarShadow(plano, planoGerado(), dadosPlano(atletaBase()), decisaoNeutra(), semanaInicio, false);

            assertThat(plano.getPlannerEnabled()).isFalse(); // marcado antes do try — sempre persiste
            assertThat(plano.getPlannerVersion()).isNull(); // shadow abortou antes de calcular
            assertThat(meterRegistry.find("planner.shadow.error.count").counter()).isNotNull();
            assertThat(meterRegistry.find("planner.shadow.error.count").tag("reason", "RuntimeException").counter()).isNotNull();
        }
    }

    // --- Fixtures ---

    private Atleta atletaBase() {
        Prova provaAlvo = Prova.builder()
                .dataProva(semanaInicio.plusDays(70))
                .distancia(DistanciaProva.KM_21)
                .provaAlvo(true)
                .statusProva(ProvaStatus.CONFIRMADA)
                .build();
        return Atleta.builder()
                .id(UUID.randomUUID())
                .nivelExperiencia(NivelExperiencia.INTERMEDIARIO)
                .temLesao(false)
                .diasDisponiveis(List.of(DiaSemana.SEGUNDA, DiaSemana.QUARTA, DiaSemana.SABADO))
                .provas(List.of(provaAlvo))
                .build();
    }

    private DadosPlanoDto dadosPlano(Atleta atleta) {
        return new DadosPlanoDto(atleta, semanaInicio, null, List.of(), null);
    }

    private DecisaoProgressao decisaoNeutra() {
        return new DecisaoProgressao(EstadoProgressao.MANTER, 0.0, 0, false, "teste");
    }

    private ProgressaoHistoricoResumo historico(double tsbAtual, double ctlAtual, int semanasProgressaoContinua) {
        return new ProgressaoHistoricoResumo(0, 0, 0.0, 0.0, 0.0, 0, 0, null, tsbAtual, ctlAtual, 0.0, semanasProgressaoContinua);
    }

    private PlanoSemanalLlmDto planoGerado() {
        TreinoPlanejadoLlmDto treino = new TreinoPlanejadoLlmDto(
                "SEGUNDA", "REGENERATIVO", null, 60, 0.7, 4, null, "45:00", 8.0, null, List.of());
        return PlanoSemanalLlmDto.builder()
                .volumePlanejadoKm(40.0)
                .volumeAlvoKm(42.0)
                .treinosPlanejados(List.of(treino))
                .build();
    }

    private PlanoSemanal planoBase() {
        TreinoPlanejado treino = new TreinoPlanejado();
        treino.setDataTreino(semanaInicio);
        treino.setTipoTreino(TipoTreino.REGENERATIVO);
        treino.setTssPlanejado(60);

        return PlanoSemanal.builder()
                .id(UUID.randomUUID())
                .semanaInicio(semanaInicio)
                .semanaFim(semanaInicio.plusDays(6))
                .treinosPlanejados(List.of(treino))
                .build();
    }
}
