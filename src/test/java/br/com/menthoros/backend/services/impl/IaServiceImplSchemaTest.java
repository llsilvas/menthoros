package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.repository.AtletaRepository;
import br.com.menthoros.backend.routing.ModelRouter;
import br.com.menthoros.backend.services.helper.LlmUsageLogger;
import br.com.menthoros.backend.services.helper.PaceValidator;
import br.com.menthoros.backend.services.helper.PlanoEstruturaReparador;
import br.com.menthoros.backend.services.helper.PlanoResilienceService;
import br.com.menthoros.backend.services.helper.RegraGeracaoTreino;
import br.com.menthoros.backend.services.helper.TreinoHistoricoProvider;
import br.com.menthoros.backend.services.helper.ZonaTreinoService;
import br.com.menthoros.backend.services.prompt.PaceHistoricoFormatter;
import br.com.menthoros.backend.services.prompt.PlanoTreinoPromptBuilder;
import br.com.menthoros.backend.services.quality.PlanQualityChecker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cobre só a construção do JSON schema enviado à OpenAI — não o fluxo completo de geração de
 * plano (God class {@code IaServiceImpl}, decomposição rastreada em
 * {@code refactor-iaservice-decomposition}; os 12 colaboradores aqui viram mocks vazios porque
 * {@code buildSchemaTightInlineOrDefs} não usa nenhum deles, é reflexão pura sobre
 * {@code PlanoSemanalLlmDto.class}).
 */
@ExtendWith(MockitoExtension.class)
class IaServiceImplSchemaTest {

    @Mock private ModelRouter modelRouter;
    @Mock private PlanoTreinoPromptBuilder promptBuilder;
    @Mock private AtletaRepository atletaRepository;
    @Mock private RegraGeracaoTreino regraGeracaoTreino;
    @Mock private TreinoHistoricoProvider treinoHistoricoProvider;
    @Mock private PaceHistoricoFormatter paceHistoricoFormatter;
    @Mock private PaceValidator paceValidator;
    @Mock private ZonaTreinoService zonaTreinoService;
    @Mock private PlanQualityChecker planQualityChecker;
    @Mock private PlanoEstruturaReparador estruturaReparador;
    @Mock private PlanoResilienceService planoResilienceService;
    @Mock private LlmUsageLogger llmUsageLogger;
    @Mock private br.com.menthoros.backend.services.helper.PlannerShadowService plannerShadowService;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    @DisplayName("buildSchemaTightInlineOrDefs — provaId/descricao/zonaAlvo nunca vão no schema da LLM")
    void schemaOmiteCamposQueNuncaVemDoLlm() throws Exception {
        // prova-no-plano-semanal: os três só são preenchidos pelo ProvaNoPlanoService, no servidor,
        // depois da resposta da LLM. Se aparecerem no schema strict:true, a OpenAI é forçada a
        // devolver um valor para eles em TODO treino — e para provaId (tipo UUID, sem anyOf de
        // null) isso produz o sentinel 00000000-0000-0000-0000-000000000000, que quebra a FK de
        // tb_treino_planejado.prova_id ao persistir um treino comum (bug real, corrigido aqui).
        IaServiceImpl service = new IaServiceImpl(modelRouter, promptBuilder, atletaRepository,
                regraGeracaoTreino, treinoHistoricoProvider, paceHistoricoFormatter, paceValidator,
                zonaTreinoService, planQualityChecker, estruturaReparador, planoResilienceService,
                meterRegistry, llmUsageLogger, plannerShadowService);

        Method build = IaServiceImpl.class.getDeclaredMethod("buildSchemaTightInlineOrDefs");
        build.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) build.invoke(service);

        Map<String, Object> treinoProps = treinoItemProperties(schema);

        assertThat(treinoProps).doesNotContainKeys("provaId", "descricao", "zonaAlvo");

        @SuppressWarnings("unchecked")
        Map<String, Object> treinoItems = (Map<String, Object>) treinos(schema).get("items");
        @SuppressWarnings("unchecked")
        Collection<String> required = (Collection<String>) treinoItems.get("required");
        assertThat(required).doesNotContain("provaId", "descricao", "zonaAlvo");
    }

    @Test
    @DisplayName("buildSchemaTightInlineOrDefs — campos que a LLM de fato preenche continuam no schema")
    void schemaMantemCamposReaisDaLlm() throws Exception {
        IaServiceImpl service = new IaServiceImpl(modelRouter, promptBuilder, atletaRepository,
                regraGeracaoTreino, treinoHistoricoProvider, paceHistoricoFormatter, paceValidator,
                zonaTreinoService, planQualityChecker, estruturaReparador, planoResilienceService,
                meterRegistry, llmUsageLogger, plannerShadowService);

        Method build = IaServiceImpl.class.getDeclaredMethod("buildSchemaTightInlineOrDefs");
        build.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) build.invoke(service);

        Map<String, Object> treinoProps = treinoItemProperties(schema);

        assertThat(treinoProps).containsKeys(
                "diaSemana", "tipoTreino", "duracaoMin", "distanciaKm", "ritmoAlvo", "etapas");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> treinos(Map<String, Object> schema) {
        Map<String, Object> planoProps = (Map<String, Object>) schema.get("properties");
        return (Map<String, Object>) planoProps.get("treinosPlanejados");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> treinoItemProperties(Map<String, Object> schema) {
        Map<String, Object> treinoItems = (Map<String, Object>) treinos(schema).get("items");
        return (Map<String, Object>) treinoItems.get("properties");
    }
}
