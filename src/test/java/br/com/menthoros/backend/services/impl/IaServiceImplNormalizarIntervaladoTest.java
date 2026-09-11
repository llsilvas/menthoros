package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.dto.llm.EtapaTreinoLlmDto;
import br.com.menthoros.backend.dto.llm.TreinoPlanejadoLlmDto;
import br.com.menthoros.backend.enums.NivelExperiencia;
import br.com.menthoros.backend.services.helper.LlmUsageLogger;
import br.com.menthoros.backend.services.helper.ZonaTreinoService;
import br.com.menthoros.backend.services.helper.ZonaTreinoService.ZonaFC;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Normalização de treino INTERVALADO/TIRO ({@code normalizarTreinoIntervalado}).
 *
 * <p>Caso real que motivou esta change: geração de 2026-09-11 gerou "5x800m Z4" (5 tiros de
 * 0,8 km), mas a soma das etapas (9,47 km) excedia a {@code distanciaKm} declarada pelo LLM
 * (8,0 km). O normalizador encolhia os próprios tiros para bater com o total — quebrando a
 * distância prescrita. Tiros não podem encolher; só a "folga" (recuperação/aquec./desaq.) absorve
 * o excesso, e o total declarado é corrigido depois por {@code reconciliarDistanciaComEtapas}.</p>
 *
 * <p>Acessa {@code normalizarTreinoIntervalado} por reflexão, mesmo padrão de
 * {@link IaServiceImplFartlekExpansaoTest}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IaServiceImpl — normalização de treino intervalado")
class IaServiceImplNormalizarIntervaladoTest {

    private IaServiceImpl service;
    private List<ZonaFC> zonasFC160;

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

        zonasFC160 = List.of(
                new ZonaFC(1, "Recuperação", 120, 136),
                new ZonaFC(2, "Aeróbico",    136, 142),
                new ZonaFC(3, "Tempo",       142, 150),
                new ZonaFC(4, "Limiar",      150, 160),
                new ZonaFC(5, "VO2max",      160, 170)
        );
    }

    @Nested
    @DisplayName("normalizarTreinoIntervalado")
    class NormalizarTreinoIntervalado {

        @Test
        @DisplayName("CA1: não encolhe tiros de INTERVALADO quando a soma das etapas excede o alvo")
        void naoEncolheTiros() throws Exception {
            // Arrange — mesma estrutura de "5x800m Z4" (tiros=4,0km, rec=1,2km) + aquec./desaq.
            // somando 8,0km contra um alvo de 6,8km declarado pelo LLM (gap negativo de 1,2km,
            // como no caso real de 2026-09-11: soma > distanciaKm do LLM).
            TreinoPlanejadoLlmDto treino = intervalado(6.8,
                    etapa("AQUECIMENTO", 10, 1.5),
                    tiro(0.8), rec(0.3),
                    tiro(0.8), rec(0.3),
                    tiro(0.8), rec(0.3),
                    tiro(0.8), rec(0.3),
                    tiro(0.8),
                    etapa("DESAQUECIMENTO", 10, 1.3)
            );

            // Act
            TreinoPlanejadoLlmDto resultado = normalizar(treino, NivelExperiencia.INTERMEDIARIO);

            // Assert — todo tiro continua com 0,8km, nenhum foi reduzido
            assertThat(resultado.etapas())
                    .filteredOn(e -> "INTERVALADO".equals(e.tipoEtapa()))
                    .extracting(EtapaTreinoLlmDto::distanciaKm)
                    .containsOnly(0.8);
        }

        @Test
        @DisplayName("CA2: recuperação continua absorvendo o excesso quando o gap é negativo")
        void recuperacaoAbsorveExcesso() throws Exception {
            TreinoPlanejadoLlmDto treino = intervalado(6.8,
                    etapa("AQUECIMENTO", 10, 1.5),
                    tiro(0.8), rec(0.3),
                    tiro(0.8), rec(0.3),
                    tiro(0.8), rec(0.3),
                    tiro(0.8), rec(0.3),
                    tiro(0.8),
                    etapa("DESAQUECIMENTO", 10, 1.3)
            );

            TreinoPlanejadoLlmDto resultado = normalizar(treino, NivelExperiencia.INTERMEDIARIO);

            // Assert — pelo menos uma recuperação foi ajustada para baixo do valor original (0,3km)
            assertThat(resultado.etapas())
                    .filteredOn(e -> "RECUPERACAO".equals(e.tipoEtapa()))
                    .extracting(EtapaTreinoLlmDto::distanciaKm)
                    .anyMatch(d -> d < 0.3);
        }

        @Test
        @DisplayName("CA4: soma já dentro da tolerância não altera nenhuma etapa")
        void semAlteracaoQuandoJaBate() throws Exception {
            // 5 tiros de 0,8 (4,0) + 4 rec de 0,3 (1,2) + aquec 1,5 + desaq 1,3 = 8,0
            TreinoPlanejadoLlmDto treino = intervalado(8.0,
                    etapa("AQUECIMENTO", 10, 1.5),
                    tiro(0.8), rec(0.3),
                    tiro(0.8), rec(0.3),
                    tiro(0.8), rec(0.3),
                    tiro(0.8), rec(0.3),
                    tiro(0.8),
                    etapa("DESAQUECIMENTO", 10, 1.3)
            );

            TreinoPlanejadoLlmDto resultado = normalizar(treino, NivelExperiencia.INTERMEDIARIO);

            assertThat(resultado.etapas()).isEqualTo(treino.etapas());
        }
    }

    // ---------- helpers ----------

    private TreinoPlanejadoLlmDto normalizar(TreinoPlanejadoLlmDto treino, NivelExperiencia nivel) throws Exception {
        Method m = IaServiceImpl.class.getDeclaredMethod(
                "normalizarTreinoIntervalado", TreinoPlanejadoLlmDto.class, NivelExperiencia.class, List.class);
        m.setAccessible(true);
        return (TreinoPlanejadoLlmDto) m.invoke(service, treino, nivel, zonasFC160);
    }

    private EtapaTreinoLlmDto etapa(String tipo, Integer duracaoMin, Double distanciaKm) {
        return new EtapaTreinoLlmDto(1, tipo, tipo, duracaoMin, distanciaKm, "136-150 bpm", 1, null);
    }

    private EtapaTreinoLlmDto tiro(double distanciaKm) {
        return new EtapaTreinoLlmDto(1, "INTERVALADO", "Intervalo Z5", 4, distanciaKm, "90-95% FCmax", 1, null);
    }

    private EtapaTreinoLlmDto rec(double distanciaKm) {
        return new EtapaTreinoLlmDto(1, "RECUPERACAO", "Recuperação trote", 2, distanciaKm, "60-70% FCmax", 1, null);
    }

    private TreinoPlanejadoLlmDto intervalado(double distanciaKm, EtapaTreinoLlmDto... etapas) {
        return new TreinoPlanejadoLlmDto(
                "TERCA", "INTERVALADO", "150-160 bpm", 60, 8.0, 6,
                "Estímulo de VO2max.",
                "70", distanciaKm, "5:00-5:15/km", List.of(etapas));
    }
}
