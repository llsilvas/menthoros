package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.dto.llm.EtapaTreinoLlmDto;
import br.com.menthoros.backend.dto.llm.TreinoPlanejadoLlmDto;
import br.com.menthoros.backend.services.helper.LlmUsageLogger;
import br.com.menthoros.backend.services.helper.ZonaTreinoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Bug: REGENERATIVO substituído por lesão + reparo estrutural nasce com etapas só de duração
 * (PRINCIPAL sem distância) e {@code corrigirEtapaTemporal} não deriva a PRINCIPAL → treino com 0 km.
 * {@code garantirDistanciaContinuo} deriva distância = duração/pace para tipos contínuos.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IaServiceImpl — garantirDistanciaContinuo (distância zerada em treino contínuo)")
class IaServiceImplDistanciaContinuoTest {

    private IaServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IaServiceImpl(
                mock(br.com.menthoros.backend.routing.ModelRouter.class),
                mock(br.com.menthoros.backend.services.prompt.PlanoTreinoPromptBuilder.class),
                mock(br.com.menthoros.backend.repository.AtletaRepository.class),
                mock(br.com.menthoros.backend.services.helper.RegraGeracaoTreino.class),
                mock(br.com.menthoros.backend.services.helper.TreinoHistoricoProvider.class),
                mock(br.com.menthoros.backend.services.prompt.PaceHistoricoFormatter.class),
                mock(br.com.menthoros.backend.services.helper.PaceValidator.class),
                mock(ZonaTreinoService.class),
                mock(br.com.menthoros.backend.services.quality.PlanQualityChecker.class),
                mock(br.com.menthoros.backend.services.helper.PlanoEstruturaReparador.class),
                mock(br.com.menthoros.backend.services.helper.PlanoResilienceService.class),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                new LlmUsageLogger(),
                mock(br.com.menthoros.backend.services.helper.PlannerShadowService.class)
        );
    }

    private TreinoPlanejadoLlmDto invoke(TreinoPlanejadoLlmDto treino, BigDecimal paceLimiar) throws Exception {
        Method m = IaServiceImpl.class.getDeclaredMethod("garantirDistanciaContinuo",
                TreinoPlanejadoLlmDto.class, BigDecimal.class);
        m.setAccessible(true);
        return (TreinoPlanejadoLlmDto) m.invoke(service, treino, paceLimiar);
    }

    private static EtapaTreinoLlmDto etapa(int ordem, String tipo, Integer duracaoMin, Double dist) {
        return new EtapaTreinoLlmDto(ordem, tipo, tipo, duracaoMin, dist, "113-128 bpm", 1, null);
    }

    private static TreinoPlanejadoLlmDto regenerativoSemDistancia() {
        // AQUEC 10 + PRINCIPAL 20 + DESAQUEC 5, todas sem distância (como o reparo sintetiza)
        return new TreinoPlanejadoLlmDto("SEGUNDA", "REGENERATIVO", "113-128 bpm", 14, null, 3,
                "regenerativo", "35:00", 0.0, "7:00-7:30/km",
                List.of(etapa(1, "AQUECIMENTO", 10, null),
                        etapa(2, "PRINCIPAL", 20, 0.0),
                        etapa(3, "DESAQUECIMENTO", 5, null)),
                null, null, null);
    }

    @Test
    @DisplayName("REGENERATIVO com etapas só de duração (incl. PRINCIPAL) → distância derivada > 0")
    void derivaDistanciaDoRegenerativo() throws Exception {
        TreinoPlanejadoLlmDto out = invoke(regenerativoSemDistancia(), new BigDecimal("5.0"));

        // paceZ2 = 5.0 * 1.20 = 6.0 min/km → total = 35min/6 ≈ 5.83 km
        assertThat(out.distanciaKm()).isNotNull().isGreaterThan(0.0);
        // a PRINCIPAL (o grosso) deixou de ser 0
        double principal = out.etapas().stream().filter(e -> "PRINCIPAL".equals(e.tipoEtapa()))
                .findFirst().orElseThrow().distanciaKm();
        assertThat(principal).isGreaterThan(0.0);
        // soma coerente com o total
        double soma = out.etapas().stream().mapToDouble(e -> e.distanciaKm() == null ? 0 : e.distanciaKm()).sum();
        assertThat(out.distanciaKm()).isEqualTo(soma);
    }

    @Test
    @DisplayName("sem paceLimiar usa default 7.0 min/km — ainda deriva > 0")
    void derivaComPaceDefault() throws Exception {
        TreinoPlanejadoLlmDto out = invoke(regenerativoSemDistancia(), null);
        assertThat(out.distanciaKm()).isNotNull().isGreaterThan(0.0);
    }

    @Test
    @DisplayName("não sobrescreve distância válida já existente")
    void naoSobrescreveDistanciaValida() throws Exception {
        TreinoPlanejadoLlmDto comDist = new TreinoPlanejadoLlmDto("SEGUNDA", "REGENERATIVO", "113-128 bpm",
                14, null, 3, "x", "35:00", 5.0, "7:00-7:30/km",
                List.of(etapa(1, "PRINCIPAL", 20, 5.0)), null, null, null);
        TreinoPlanejadoLlmDto out = invoke(comDist, new BigDecimal("5.0"));
        assertThat(out.distanciaKm()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("não age em INTERVALADO (PRINCIPAL é distância fixa)")
    void naoAgeEmIntervalado() throws Exception {
        TreinoPlanejadoLlmDto interval = new TreinoPlanejadoLlmDto("TERCA", "INTERVALADO", "160-170 bpm",
                60, null, 7, "x", "50:00", 0.0, "5:00/km",
                List.of(etapa(1, "PRINCIPAL", 20, 0.0)), null, null, null);
        TreinoPlanejadoLlmDto out = invoke(interval, new BigDecimal("5.0"));
        assertThat(out.distanciaKm()).isEqualTo(0.0); // inalterado
    }
}
