package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.domain.compliance.PlannerViolation;
import br.com.menthoros.backend.domain.compliance.PlannerViolationKey;
import br.com.menthoros.backend.domain.planner.WeekPlanSkeleton;
import br.com.menthoros.backend.dto.llm.PlanoSemanalLlmDto;
import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.exception.LLMException;
import br.com.menthoros.backend.services.helper.LlmUsageLogger;
import br.com.menthoros.backend.services.helper.PlannerShadowService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Estagio 1 do enforcement (planner-engine-enforcement secao 4): compliance PRE-redistribuicao no
 * {@code validar} do {@code gerarComResiliencia}. Testa o metodo privado {@code aplicarComplianceEstagio1}
 * por reflexao — o fluxo completo de {@code geraPlanoSemanalAvancado} exige fixtures do LLM inviaveis
 * em unit test (God class, decomposicao rastreada em refactor-iaservice-decomposition).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IaServiceImpl — compliance estagio 1 (PRE)")
class IaServiceImplComplianceEstagio1Test {

    private IaServiceImpl service;
    private PlannerShadowService plannerShadowService;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        plannerShadowService = mock(PlannerShadowService.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new IaServiceImpl(
                mock(br.com.menthoros.backend.routing.ModelRouter.class),
                mock(br.com.menthoros.backend.services.prompt.PlanoTreinoPromptBuilder.class),
                mock(br.com.menthoros.backend.repository.AtletaRepository.class),
                mock(br.com.menthoros.backend.services.helper.RegraGeracaoTreino.class),
                mock(br.com.menthoros.backend.services.helper.TreinoHistoricoProvider.class),
                mock(br.com.menthoros.backend.services.prompt.PaceHistoricoFormatter.class),
                mock(br.com.menthoros.backend.services.helper.PaceValidator.class),
                mock(br.com.menthoros.backend.services.helper.ZonaTreinoService.class),
                mock(br.com.menthoros.backend.services.quality.PlanQualityChecker.class),
                mock(br.com.menthoros.backend.services.helper.PlanoEstruturaReparador.class),
                mock(br.com.menthoros.backend.services.helper.PlanoResilienceService.class),
                meterRegistry,
                new LlmUsageLogger(),
                plannerShadowService
        );
    }

    private static WeekPlanSkeleton skeletonMinimo() {
        // Campos irrelevantes ao teste ficam null; checkPreRedistribution é mockado e ignora o conteudo.
        return new WeekPlanSkeleton(null, null, List.of(), null, null, false, null,
                LocalDate.of(2026, 9, 7), null, Optional.empty());
    }

    private static PlanoSemanalLlmDto plano() {
        return new PlanoSemanalLlmDto(30.0, 30.0, null, null, "ATIVO", "base aerobica", List.of());
    }

    private PlanoSemanalLlmDto invoke(PlanoSemanalLlmDto validado, WeekPlanSkeleton skeleton) throws Exception {
        Method m = IaServiceImpl.class.getDeclaredMethod("aplicarComplianceEstagio1",
                PlanoSemanalLlmDto.class, Atleta.class, WeekPlanSkeleton.class, LocalDate.class);
        m.setAccessible(true);
        try {
            return (PlanoSemanalLlmDto) m.invoke(service, validado, new Atleta(), skeleton, LocalDate.of(2026, 9, 7));
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    private double failureCount() {
        return meterRegistry.find("planner.compliance.failure.count").tag("stage", "PRE").counter() == null
                ? 0.0
                : meterRegistry.find("planner.compliance.failure.count").tag("stage", "PRE").counter().count();
    }

    @Nested
    @DisplayName("flag off (skeleton null)")
    class SkeletonNulo {

        @Test
        @DisplayName("no-op: devolve o plano validado, nao chama compliance nem emite metrica")
        void noOp() throws Exception {
            PlanoSemanalLlmDto validado = plano();

            PlanoSemanalLlmDto resultado = invoke(validado, null);

            assertThat(resultado).isSameAs(validado);
            verifyNoInteractions(plannerShadowService);
            assertThat(failureCount()).isZero();
        }
    }

    @Nested
    @DisplayName("flag on (skeleton presente)")
    class SkeletonPresente {

        @Test
        @DisplayName("sem violacao: devolve o plano validado, sem metrica")
        void semViolacao() throws Exception {
            when(plannerShadowService.checkPreRedistribution(any(), any(), any(), any()))
                    .thenReturn(List.of());
            PlanoSemanalLlmDto validado = plano();

            PlanoSemanalLlmDto resultado = invoke(validado, skeletonMinimo());

            assertThat(resultado).isSameAs(validado);
            assertThat(failureCount()).isZero();
        }

        @Test
        @DisplayName("com violacao: lanca LLMException com os motivos e emite planner.compliance.failure.count{stage=PRE}")
        void comViolacao() {
            when(plannerShadowService.checkPreRedistribution(any(), any(), any(), any()))
                    .thenReturn(List.of(
                            new PlannerViolation(PlannerViolationKey.FASE_DIVERGENTE, "fase esperada BASE, veio BUILD"),
                            new PlannerViolation(PlannerViolationKey.TSS_FORA_DA_FAIXA, "TSS 600 fora de 350±10%")));

            assertThatThrownBy(() -> invoke(plano(), skeletonMinimo()))
                    .isInstanceOf(LLMException.class)
                    .hasMessageContaining("FASE_DIVERGENTE")
                    .hasMessageContaining("TSS_FORA_DA_FAIXA")
                    .hasMessageContaining("fase esperada BASE");

            assertThat(failureCount()).isEqualTo(1.0);
        }
    }
}
