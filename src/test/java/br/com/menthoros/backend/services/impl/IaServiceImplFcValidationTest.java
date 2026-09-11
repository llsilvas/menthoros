package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.dto.llm.EtapaTreinoLlmDto;
import br.com.menthoros.backend.dto.llm.TreinoPlanejadoLlmDto;
import br.com.menthoros.backend.exception.LLMException;
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

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * Testes unitários para os métodos de validação de FC por zona em IaServiceImpl.
 * Usa reflexão para acessar os métodos privados da lógica de validação LTHR.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IaServiceImpl — validação FC por zona LTHR")
class IaServiceImplFcValidationTest {

    private IaServiceImpl service;

    // zonas para fcLimiar = 160 bpm (LTHR)
    // Z1=[120,136], Z2=[136,142], Z3=[142,150], Z4=[150,160], Z5=[160,170]
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
                new ZonaFC(1, "Recuperação",  120, 136),
                new ZonaFC(2, "Aeróbico",     136, 142),
                new ZonaFC(3, "Tempo",        142, 150),
                new ZonaFC(4, "Limiar",       150, 160),
                new ZonaFC(5, "VO2max",       160, 170)
        );
    }

    // ======================== parseFcRange ========================

    @Nested
    @DisplayName("parseFcRange")
    class ParseFcRange {

        @Test
        @DisplayName("formato válido '140-160 bpm' → int[]{140, 160}")
        void formatoValido() throws Exception {
            int[] result = invokeParseFcRange("140-160 bpm");
            assertThat(result).isNotNull();
            assertThat(result[0]).isEqualTo(140);
            assertThat(result[1]).isEqualTo(160);
        }

        @Test
        @DisplayName("formato antigo '88-95% FCmax' → null")
        void formatoPercentualFcMax_retornaNull() throws Exception {
            int[] result = invokeParseFcRange("88-95% FCmax");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("null → null")
        void nulo_retornaNull() throws Exception {
            int[] result = invokeParseFcRange(null);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("string vazia → null")
        void vazio_retornaNull() throws Exception {
            int[] result = invokeParseFcRange("");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("'150-165 bpm' com espaço externo → trim aplicado, parseia corretamente")
        void comEspacoExtra_parseiaComTrim() throws Exception {
            int[] result = invokeParseFcRange(" 150-165 bpm ");
            assertThat(result).isNotNull();
            assertThat(result[0]).isEqualTo(150);
            assertThat(result[1]).isEqualTo(165);
        }

        private int[] invokeParseFcRange(String value) throws Exception {
            Method m = IaServiceImpl.class.getDeclaredMethod("parseFcRange", String.class);
            m.setAccessible(true);
            return (int[]) m.invoke(service, value);
        }
    }

    // ======================== validarFcEtapa ========================

    @Nested
    @DisplayName("validarFcEtapa")
    class ValidarFcEtapa {

        @Test
        @DisplayName("AQUECIMENTO dentro de Z1 → sem alteração")
        void aquecimento_dentroZ1_semAlteracao() throws Exception {
            var etapa = new EtapaTreinoLlmDto(1, "AQUECIMENTO", "Trote leve", 10, 1.5, "120-136 bpm", 1, null);
            EtapaTreinoLlmDto resultado = invokeValidarFcEtapa(etapa, "INTERVALADO", zonasFC160);
            assertThat(resultado.fcAlvoEtapa()).isEqualTo("120-136 bpm");
        }

        @Test
        @DisplayName("AQUECIMENTO com FC de Z4 → corrigido para centro de Z1")
        void aquecimento_foraZ1_corrigido() throws Exception {
            var etapa = new EtapaTreinoLlmDto(1, "AQUECIMENTO", "Trote leve", 10, 1.5, "155-165 bpm", 1, null);
            EtapaTreinoLlmDto resultado = invokeValidarFcEtapa(etapa, "LONGO", zonasFC160);
            assertThat(resultado.fcAlvoEtapa()).isNotEqualTo("155-165 bpm");
            assertThat(resultado.fcAlvoEtapa()).endsWith(" bpm");
        }

        @Test
        @DisplayName("INTERVALADO (etapa) dentro de Z4-Z5 → sem alteração")
        void intervalado_dentroZ4Z5_semAlteracao() throws Exception {
            var etapa = new EtapaTreinoLlmDto(2, "INTERVALADO", "400m forte", 4, 0.4, "155-165 bpm", 1, null);
            EtapaTreinoLlmDto resultado = invokeValidarFcEtapa(etapa, "INTERVALADO", zonasFC160);
            assertThat(resultado.fcAlvoEtapa()).isEqualTo("155-165 bpm");
        }

        @Test
        @DisplayName("fcAlvoEtapa não parseable → sem exceção, valor mantido")
        void naoParseavel_semExcecao() {
            var etapa = new EtapaTreinoLlmDto(1, "AQUECIMENTO", "Leve", 10, 1.5, "Z1 easy", 1, null);
            assertThatNoException().isThrownBy(() -> {
                EtapaTreinoLlmDto resultado = invokeValidarFcEtapa(etapa, "CONTINUO", zonasFC160);
                assertThat(resultado.fcAlvoEtapa()).isEqualTo("Z1 easy");
            });
        }

        @Test
        @DisplayName("fcAlvoEtapa null → sem exceção, valor mantido null")
        void fcAlvoNull_semExcecao() {
            var etapa = new EtapaTreinoLlmDto(1, "PRINCIPAL", "Contínuo", 30, 5.0, null, 1, null);
            assertThatNoException().isThrownBy(() -> {
                EtapaTreinoLlmDto resultado = invokeValidarFcEtapa(etapa, "CONTINUO", zonasFC160);
                assertThat(resultado.fcAlvoEtapa()).isNull();
            });
        }

        @Test
        @DisplayName("tipo de etapa desconhecido → sem validação, etapa inalterada")
        void tipoDesconhecido_semValidacao() throws Exception {
            var etapa = new EtapaTreinoLlmDto(1, "STRIDES", "Acelerações", 5, 0.5, "999-999 bpm", 1, null);
            EtapaTreinoLlmDto resultado = invokeValidarFcEtapa(etapa, "FARTLEK", zonasFC160);
            assertThat(resultado.fcAlvoEtapa()).isEqualTo("999-999 bpm");
        }

        private EtapaTreinoLlmDto invokeValidarFcEtapa(EtapaTreinoLlmDto etapa, String tipoTreino, List<ZonaFC> zonas) throws Exception {
            Method m = IaServiceImpl.class.getDeclaredMethod("validarFcEtapa", EtapaTreinoLlmDto.class, String.class, List.class);
            m.setAccessible(true);
            return (EtapaTreinoLlmDto) m.invoke(service, etapa, tipoTreino, zonas);
        }
    }

    // ======================== P1.1 — zonaEsperadaFC ciente do tipoTreino ========================

    @Nested
    @DisplayName("P1.1 — zonaEsperadaFC e zonaParaEtapaPrincipal por tipoTreino")
    class ZonaEsperadaFcPorTipoTreino {

        // --- zonaEsperadaFC: etapas estruturais são independentes do tipoTreino ---

        @Test
        @DisplayName("AQUECIMENTO → sempre Z1, independente do tipoTreino")
        void aquecimento_sempreZ1() throws Exception {
            int[] z = invokeZonaEsperadaFC("AQUECIMENTO", "REGENERATIVO", zonasFC160);
            assertThat(z).isEqualTo(new int[]{120, 136}); // Z1
        }

        @Test
        @DisplayName("RECUPERACAO → sempre Z1, independente do tipoTreino")
        void recuperacao_sempreZ1() throws Exception {
            int[] z = invokeZonaEsperadaFC("RECUPERACAO", "INTERVALADO", zonasFC160);
            assertThat(z).isEqualTo(new int[]{120, 136}); // Z1
        }

        @Test
        @DisplayName("DESAQUECIMENTO → sempre Z1, independente do tipoTreino")
        void desaquecimento_sempreZ1() throws Exception {
            int[] z = invokeZonaEsperadaFC("DESAQUECIMENTO", "TIRO", zonasFC160);
            assertThat(z).isEqualTo(new int[]{120, 136}); // Z1
        }

        @Test
        @DisplayName("INTERVALADO (etapa) → sempre Z4-Z5, independente do tipoTreino")
        void intervaladoEtapa_sempreZ4Z5() throws Exception {
            int[] z = invokeZonaEsperadaFC("INTERVALADO", "FARTLEK", zonasFC160);
            assertThat(z).isEqualTo(new int[]{150, 170}); // Z4-Z5
        }

        // --- zonaParaEtapaPrincipal: depende do tipoTreino ---

        @Test
        @DisplayName("PRINCIPAL + REGENERATIVO → Z1-Z2 (não Z4!)")
        void principal_regenerativo_z1z2() throws Exception {
            int[] z = invokeZonaEsperadaFC("PRINCIPAL", "REGENERATIVO", zonasFC160);
            assertThat(z).isEqualTo(new int[]{120, 142}); // Z1-Z2
        }

        @Test
        @DisplayName("PRINCIPAL + CONTINUO → Z2-Z3")
        void principal_continuo_z2z3() throws Exception {
            int[] z = invokeZonaEsperadaFC("PRINCIPAL", "CONTINUO", zonasFC160);
            assertThat(z).isEqualTo(new int[]{136, 150}); // Z2-Z3
        }

        @Test
        @DisplayName("PRINCIPAL + LONGO → Z2-Z3")
        void principal_longo_z2z3() throws Exception {
            int[] z = invokeZonaEsperadaFC("PRINCIPAL", "LONGO", zonasFC160);
            assertThat(z).isEqualTo(new int[]{136, 150}); // Z2-Z3
        }

        @Test
        @DisplayName("PRINCIPAL + FARTLEK → Z2-Z4 (range variável)")
        void principal_fartlek_z2z4() throws Exception {
            int[] z = invokeZonaEsperadaFC("PRINCIPAL", "FARTLEK", zonasFC160);
            assertThat(z).isEqualTo(new int[]{136, 160}); // Z2-Z4
        }

        @Test
        @DisplayName("PRINCIPAL + TEMPO_RUN → Z3-Z4 (limiar)")
        void principal_tempoRun_z3z4() throws Exception {
            int[] z = invokeZonaEsperadaFC("PRINCIPAL", "TEMPO_RUN", zonasFC160);
            assertThat(z).isEqualTo(new int[]{142, 160}); // Z3-Z4
        }

        @Test
        @DisplayName("PRINCIPAL + INTERVALADO → Z4-Z5")
        void principal_intervalado_z4z5() throws Exception {
            int[] z = invokeZonaEsperadaFC("PRINCIPAL", "INTERVALADO", zonasFC160);
            assertThat(z).isEqualTo(new int[]{150, 170}); // Z4-Z5
        }

        @Test
        @DisplayName("PRINCIPAL + TIRO → Z4-Z5")
        void principal_tiro_z4z5() throws Exception {
            int[] z = invokeZonaEsperadaFC("PRINCIPAL", "TIRO", zonasFC160);
            assertThat(z).isEqualTo(new int[]{150, 170}); // Z4-Z5
        }

        @Test
        @DisplayName("PRINCIPAL + tipoTreino null → default Z2-Z4")
        void principal_tipoTreinoNull_defaultZ2Z4() throws Exception {
            int[] z = invokeZonaEsperadaFC("PRINCIPAL", null, zonasFC160);
            assertThat(z).isEqualTo(new int[]{136, 160}); // Z2-Z4
        }

        // --- Casos de integração via validarFcEtapa ---

        @Test
        @DisplayName("REGENERATIVO com PRINCIPAL em Z4 → corrigido para Z1-Z2 (não Z4)")
        void regenerativo_principalEmZ4_corrigidoParaZ1Z2() throws Exception {
            // Sem P1.1, PRINCIPAL em qualquer treino era validado como Z2-Z4 → Z4 passaria.
            // Com P1.1, REGENERATIVO PRINCIPAL deve estar em Z1-Z2 → Z4 é corrigido.
            var etapa = new EtapaTreinoLlmDto(2, "PRINCIPAL", "Trote regenerativo", 30, 5.0, "155-160 bpm", 1, null);
            EtapaTreinoLlmDto resultado = invokeValidarFcEtapa(etapa, "REGENERATIVO", zonasFC160);

            assertThat(resultado.fcAlvoEtapa()).isNotEqualTo("155-160 bpm");
            // Z1-Z2 = [120, 142] → quartil central: 120+5=125, 142-5=137 (aprox)
            assertThat(resultado.fcAlvoEtapa()).endsWith(" bpm");
            int[] corrigido = invokeParseFcRange(resultado.fcAlvoEtapa());
            assertThat(corrigido[0]).isGreaterThanOrEqualTo(120);
            assertThat(corrigido[1]).isLessThanOrEqualTo(142);
        }

        @Test
        @DisplayName("TEMPO_RUN com PRINCIPAL em Z1 → corrigido para Z3-Z4")
        void tempoRun_principalEmZ1_corrigidoParaZ3Z4() throws Exception {
            var etapa = new EtapaTreinoLlmDto(2, "PRINCIPAL", "Corrida limiar", 20, 4.0, "120-130 bpm", 1, null);
            EtapaTreinoLlmDto resultado = invokeValidarFcEtapa(etapa, "TEMPO_RUN", zonasFC160);

            assertThat(resultado.fcAlvoEtapa()).isNotEqualTo("120-130 bpm");
            int[] corrigido = invokeParseFcRange(resultado.fcAlvoEtapa());
            // Z3-Z4 = [142, 160] → valor corrigido deve estar nessa faixa
            assertThat(corrigido[0]).isGreaterThanOrEqualTo(142);
            assertThat(corrigido[1]).isLessThanOrEqualTo(160);
        }

        @Test
        @DisplayName("CONTINUO com PRINCIPAL em Z2-Z3 → sem correção (dentro da zona esperada)")
        void continuo_principalEmZ2Z3_semCorrecao() throws Exception {
            var etapa = new EtapaTreinoLlmDto(2, "PRINCIPAL", "Corrida contínua", 40, 7.0, "138-148 bpm", 1, null);
            EtapaTreinoLlmDto resultado = invokeValidarFcEtapa(etapa, "CONTINUO", zonasFC160);

            assertThat(resultado.fcAlvoEtapa()).isEqualTo("138-148 bpm");
        }

        // ===== Helpers de reflexão =====

        private int[] invokeZonaEsperadaFC(String tipoEtapa, String tipoTreino, List<ZonaFC> zonas) throws Exception {
            Method m = IaServiceImpl.class.getDeclaredMethod("zonaEsperadaFC", String.class, String.class, List.class);
            m.setAccessible(true);
            return (int[]) m.invoke(service, tipoEtapa, tipoTreino, zonas);
        }

        private EtapaTreinoLlmDto invokeValidarFcEtapa(EtapaTreinoLlmDto etapa, String tipoTreino, List<ZonaFC> zonas) throws Exception {
            Method m = IaServiceImpl.class.getDeclaredMethod("validarFcEtapa", EtapaTreinoLlmDto.class, String.class, List.class);
            m.setAccessible(true);
            return (EtapaTreinoLlmDto) m.invoke(service, etapa, tipoTreino, zonas);
        }

        private int[] invokeParseFcRange(String value) throws Exception {
            Method m = IaServiceImpl.class.getDeclaredMethod("parseFcRange", String.class);
            m.setAccessible(true);
            return (int[]) m.invoke(service, value);
        }
    }

    // ======================== P0 — bypass de validação FC corrigido ========================

    @Nested
    @DisplayName("P0 — Etapas auto-inseridas devem produzir formato 'NNN-NNN bpm'")
    class BypassFcCorrigido {

        @Test
        @DisplayName("bpmDaZona com zonas válidas → retorna 'fcMin-fcMax bpm'")
        void bpmDaZona_comZonas_retornaFormatoBpm() throws Exception {
            String resultado = invokeBpmDaZona(zonasFC160, 4); // Z5
            assertThat(resultado).isEqualTo("160-170 bpm");
        }

        @Test
        @DisplayName("bpmDaZona com zonas null → retorna null")
        void bpmDaZona_semZonas_retornaNull() throws Exception {
            String resultado = invokeBpmDaZona(null, 4);
            assertThat(resultado).isNull();
        }

        @Test
        @DisplayName("bpmDaZona com índice fora do range → retorna null")
        void bpmDaZona_indiceForaRange_retornaNull() throws Exception {
            String resultado = invokeBpmDaZona(zonasFC160, 10);
            assertThat(resultado).isNull();
        }

        @Test
        @DisplayName("zonaParaFc com zonas disponíveis → retorna bpm, não % FCmax")
        void zonaParaFc_comZonas_retornaBpm() throws Exception {
            assertThat(invokeZonaParaFc("Z5", zonasFC160)).isEqualTo("160-170 bpm");
            assertThat(invokeZonaParaFc("Z4", zonasFC160)).isEqualTo("150-160 bpm");
            assertThat(invokeZonaParaFc("Z3", zonasFC160)).isEqualTo("142-150 bpm");
            assertThat(invokeZonaParaFc("Z2", zonasFC160)).isEqualTo("136-142 bpm");
            assertThat(invokeZonaParaFc("Z1", zonasFC160)).isEqualTo("120-136 bpm");
        }

        @Test
        @DisplayName("zonaParaFc sem zonas (null) → mantém fallback % FCmax para compatibilidade")
        void zonaParaFc_semZonas_retornaFallbackPercentual() throws Exception {
            assertThat(invokeZonaParaFc("Z5", null)).isEqualTo("90-95% FCmax");
            assertThat(invokeZonaParaFc("Z1", null)).isEqualTo("60-70% FCmax");
        }

        @Test
        @DisplayName("adicionarTiroERecuperacao com zonas → FC em formato bpm que parseFcRange aceita")
        void adicionarTiroERecuperacao_comZonas_fcEmBpm() throws Exception {
            List<EtapaTreinoLlmDto> etapasBase = List.of(
                    new EtapaTreinoLlmDto(1, "AQUECIMENTO",    "Trote leve",   10, 1.5, "120-136 bpm", 1, null),
                    new EtapaTreinoLlmDto(2, "INTERVALADO",    "Tiro 1",        4, 0.4, "160-170 bpm", 1, null),
                    new EtapaTreinoLlmDto(3, "RECUPERACAO",    "Trote rec",     2, 0.2, "120-136 bpm", 1, null),
                    new EtapaTreinoLlmDto(4, "DESAQUECIMENTO", "Caminhada",     8, 1.0, "120-136 bpm", 1, null)
            );

            List<EtapaTreinoLlmDto> resultado = invokeAdicionarTiroERecuperacao(
                    new java.util.ArrayList<>(etapasBase), 0.8, 0.3, 4, 2, zonasFC160
            );

            List<EtapaTreinoLlmDto> novas = resultado.stream()
                    .filter(e -> e.descricaoEtapa().contains("extra"))
                    .toList();

            assertThat(novas).hasSize(2);

            EtapaTreinoLlmDto tiroExtra = novas.stream()
                    .filter(e -> "INTERVALADO".equals(e.tipoEtapa())).findFirst().orElseThrow();
            EtapaTreinoLlmDto recExtra = novas.stream()
                    .filter(e -> "RECUPERACAO".equals(e.tipoEtapa())).findFirst().orElseThrow();

            assertThat(tiroExtra.fcAlvoEtapa()).matches("\\d{2,3}-\\d{2,3} bpm");
            assertThat(recExtra.fcAlvoEtapa()).matches("\\d{2,3}-\\d{2,3} bpm");

            assertThat(tiroExtra.fcAlvoEtapa()).isEqualTo("160-170 bpm"); // Z5
            assertThat(recExtra.fcAlvoEtapa()).isEqualTo("120-136 bpm");  // Z1
        }

        @Test
        @DisplayName("adicionarTiroERecuperacao sem zonas (null) → mantém fallback % FCmax")
        void adicionarTiroERecuperacao_semZonas_fcFallback() throws Exception {
            List<EtapaTreinoLlmDto> etapasBase = List.of(
                    new EtapaTreinoLlmDto(1, "AQUECIMENTO",    "Trote leve", 10, 1.5, "120-136 bpm", 1, null),
                    new EtapaTreinoLlmDto(2, "DESAQUECIMENTO", "Caminhada",   8, 1.0, "120-136 bpm", 1, null)
            );

            List<EtapaTreinoLlmDto> resultado = invokeAdicionarTiroERecuperacao(
                    new java.util.ArrayList<>(etapasBase), 0.8, 0.3, 4, 2, null
            );

            List<EtapaTreinoLlmDto> novas = resultado.stream()
                    .filter(e -> e.descricaoEtapa().contains("extra"))
                    .toList();

            assertThat(novas).hasSize(2);
            EtapaTreinoLlmDto tiroExtra = novas.stream()
                    .filter(e -> "INTERVALADO".equals(e.tipoEtapa())).findFirst().orElseThrow();
            EtapaTreinoLlmDto recExtra = novas.stream()
                    .filter(e -> "RECUPERACAO".equals(e.tipoEtapa())).findFirst().orElseThrow();

            assertThat(tiroExtra.fcAlvoEtapa()).isEqualTo("90-95% FCmax");
            assertThat(recExtra.fcAlvoEtapa()).isEqualTo("70-80% FCmax");
        }

        @Test
        @DisplayName("Prova do bypass corrigido: etapa auto-inserida com zonas é validada por validarFcEtapa")
        void etapaAutoInserida_comZonas_naoBypassaValidacaoFc() throws Exception {
            var etapaTiro = new EtapaTreinoLlmDto(2, "INTERVALADO", "Tiro extra em Z5", 4, 0.8, "160-170 bpm", 1, null);
            EtapaTreinoLlmDto resultado = invokeValidarFcEtapa(etapaTiro, "INTERVALADO", zonasFC160);
            assertThat(resultado.fcAlvoEtapa()).isEqualTo("160-170 bpm");

            var etapaTiroForaDaZona = new EtapaTreinoLlmDto(2, "INTERVALADO", "Tiro extra em Z5", 4, 0.8, "125-135 bpm", 1, null);
            EtapaTreinoLlmDto resultadoCorrigido = invokeValidarFcEtapa(etapaTiroForaDaZona, "INTERVALADO", zonasFC160);
            assertThat(resultadoCorrigido.fcAlvoEtapa()).isNotEqualTo("125-135 bpm");
            assertThat(resultadoCorrigido.fcAlvoEtapa()).endsWith(" bpm");
        }

        @Test
        @DisplayName("Etapa com '% FCmax' → parseFcRange retorna null, etapa mantida com WARN (fallback sem zonas)")
        void etapaComPercentualFcMax_escapaValidacao_comportamentoDocumentado() throws Exception {
            var etapa = new EtapaTreinoLlmDto(1, "AQUECIMENTO", "Trote", 10, 1.5, "90-95% FCmax", 1, null);
            EtapaTreinoLlmDto resultado = invokeValidarFcEtapa(etapa, "CONTINUO", zonasFC160);
            assertThat(resultado.fcAlvoEtapa()).isEqualTo("90-95% FCmax");
        }

        // ===== Helpers de reflexão =====

        private String invokeBpmDaZona(List<ZonaFC> zonas, int index) throws Exception {
            Method m = IaServiceImpl.class.getDeclaredMethod("bpmDaZona", List.class, int.class);
            m.setAccessible(true);
            return (String) m.invoke(service, zonas, index);
        }

        @SuppressWarnings("unchecked")
        private List<EtapaTreinoLlmDto> invokeAdicionarTiroERecuperacao(
                List<EtapaTreinoLlmDto> etapas, double distTiro, double distRec,
                int durTiro, int durRec, List<ZonaFC> zonas) throws Exception {
            Method m = IaServiceImpl.class.getDeclaredMethod("adicionarTiroERecuperacao",
                    List.class, double.class, double.class, int.class, int.class, List.class);
            m.setAccessible(true);
            return (List<EtapaTreinoLlmDto>) m.invoke(service, etapas, distTiro, distRec, durTiro, durRec, zonas);
        }

        private String invokeZonaParaFc(String zona, List<ZonaFC> zonas) throws Exception {
            Method m = IaServiceImpl.class.getDeclaredMethod("zonaParaFc", String.class, List.class);
            m.setAccessible(true);
            return (String) m.invoke(service, zona, zonas);
        }

        private EtapaTreinoLlmDto invokeValidarFcEtapa(EtapaTreinoLlmDto etapa, String tipoTreino, List<ZonaFC> zonas) throws Exception {
            Method m = IaServiceImpl.class.getDeclaredMethod("validarFcEtapa", EtapaTreinoLlmDto.class, String.class, List.class);
            m.setAccessible(true);
            return (EtapaTreinoLlmDto) m.invoke(service, etapa, tipoTreino, zonas);
        }
    }

    // ========= corrigirDistanciasEtapasTemporais =========

    @Nested
    @DisplayName("corrigirDistanciasEtapasTemporais — deriva distância de etapas time-based")
    class CorrigirDistanciasEtapasTemporais {

        // paceZ2 = 4.5 × 1.20 = 5.4 min/km → 10min → arredondar2(10/5.4) = 1.85
        @Test
        @DisplayName("AQUECIMENTO 10min com paceLimiar=4.5 → distanciaKm ≈ 1.85 (não 2.5)")
        void corrigeAquecimento() throws Exception {
            var etapa = new EtapaTreinoLlmDto(1, "AQUECIMENTO", "Trote leve", 10, 2.5, "120-136 bpm", 1, null);
            var resultado = invokeCorrigirDistancias(List.of(etapa), new BigDecimal("4.5"));
            assertThat(resultado).hasSize(1);
            assertThat(resultado.get(0).distanciaKm()).isCloseTo(1.85, within(0.01));
        }

        // paceZ2 = 5.0 × 1.20 = 6.0 min/km → 10min → arredondar2(10/6.0) = 1.67
        @Test
        @DisplayName("DESAQUECIMENTO 10min com paceLimiar=5.0 → distanciaKm ≈ 1.67")
        void corrigeDesaquecimento() throws Exception {
            var etapa = new EtapaTreinoLlmDto(1, "DESAQUECIMENTO", "Caminhada", 10, 2.5, "120-136 bpm", 1, null);
            var resultado = invokeCorrigirDistancias(List.of(etapa), new BigDecimal("5.0"));
            assertThat(resultado).hasSize(1);
            assertThat(resultado.get(0).distanciaKm()).isCloseTo(1.67, within(0.01));
        }

        // paceZ1 = 4.5 × 1.35 = 6.075 min/km → 2min → arredondar2(2/6.075) = 0.33
        @Test
        @DisplayName("RECUPERACAO 2min com paceLimiar=4.5 → distanciaKm ≈ 0.33")
        void corrigeRecuperacao() throws Exception {
            var etapa = new EtapaTreinoLlmDto(1, "RECUPERACAO", "Trote leve", 2, 1.0, "120-136 bpm", 1, null);
            var resultado = invokeCorrigirDistancias(List.of(etapa), new BigDecimal("4.5"));
            assertThat(resultado).hasSize(1);
            assertThat(resultado.get(0).distanciaKm()).isCloseTo(0.33, within(0.01));
        }

        // paceZ2 default = 7.0 min/km → 10min → arredondar2(10/7.0) = 1.43
        @Test
        @DisplayName("AQUECIMENTO 10min com paceLimiar=null → usa default 7.0 min/km → distanciaKm ≈ 1.43")
        void usaDefaultsQuandoPaceLimiarNulo() throws Exception {
            var etapa = new EtapaTreinoLlmDto(1, "AQUECIMENTO", "Trote leve", 10, 2.5, "120-136 bpm", 1, null);
            var resultado = invokeCorrigirDistancias(List.of(etapa), null);
            assertThat(resultado).hasSize(1);
            assertThat(resultado.get(0).distanciaKm()).isCloseTo(1.43, within(0.01));
        }

        @Test
        @DisplayName("INTERVALADO com distanciaKm=0.4 → não alterado (etapa de distância fixa)")
        void naoAlteraIntervalado() throws Exception {
            var etapa = new EtapaTreinoLlmDto(1, "INTERVALADO", "400m Z5", 4, 0.4, "160-170 bpm", 1, null);
            var resultado = invokeCorrigirDistancias(List.of(etapa), new BigDecimal("4.5"));
            assertThat(resultado).hasSize(1);
            assertThat(resultado.get(0).distanciaKm()).isEqualTo(0.4);
        }

        @Test
        @DisplayName("TIRO com distanciaKm=0.2 → não alterado")
        void naoAlteraTiro() throws Exception {
            var etapa = new EtapaTreinoLlmDto(1, "TIRO", "200m sprint", 1, 0.2, "165-175 bpm", 1, null);
            var resultado = invokeCorrigirDistancias(List.of(etapa), new BigDecimal("4.5"));
            assertThat(resultado).hasSize(1);
            assertThat(resultado.get(0).distanciaKm()).isEqualTo(0.2);
        }

        @Test
        @DisplayName("lista vazia → retorna lista vazia sem exceção")
        void retornaListaVaziaQuandoListaVazia() throws Exception {
            var resultado = invokeCorrigirDistancias(List.of(), new BigDecimal("4.5"));
            assertThat(resultado).isEmpty();
        }

        @Test
        @DisplayName("AQUECIMENTO com duracaoMin=null → distanciaKm original mantida")
        void naoAlteraEtapaSemDuracaoMin() throws Exception {
            var etapa = new EtapaTreinoLlmDto(1, "AQUECIMENTO", "Trote leve", null, 2.5, "120-136 bpm", 1, null);
            var resultado = invokeCorrigirDistancias(List.of(etapa), new BigDecimal("4.5"));
            assertThat(resultado).hasSize(1);
            assertThat(resultado.get(0).distanciaKm()).isEqualTo(2.5);
        }

        @Test
        @DisplayName("tipoEtapa=null → etapa retornada intacta, sem NullPointerException")
        void naoLancaNpeComTipoEtapaNulo() throws Exception {
            var etapa = new EtapaTreinoLlmDto(1, null, "Etapa sem tipo", 10, 2.5, "120-136 bpm", 1, null);
            var resultado = invokeCorrigirDistancias(List.of(etapa), new BigDecimal("4.5"));
            assertThat(resultado).hasSize(1);
            assertThat(resultado.get(0).distanciaKm()).isEqualTo(2.5);
        }

        @Test
        @DisplayName("paceLimiar=0 → guarda pace <= 0 evita divisão por zero, distanciaKm original mantida")
        void naoGeraInfinityComPaceLimiarZero() throws Exception {
            var etapa = new EtapaTreinoLlmDto(1, "AQUECIMENTO", "Trote leve", 10, 2.5, "120-136 bpm", 1, null);
            var resultado = invokeCorrigirDistancias(List.of(etapa), BigDecimal.ZERO);
            assertThat(resultado).hasSize(1);
            assertThat(resultado.get(0).distanciaKm()).isEqualTo(2.5);
        }

        @Nested
        @DisplayName("integração com reconciliarDistanciaComEtapas — CA1")
        class IntegracaoDistanciaTreinoIntervalado {

            @Test
            @DisplayName("10min aquec + 5×400m + 5×2min rec + 10min desaq → distanciaKm ∈ [5.5, 8.0]")
            void distanciaIntervaladoCorrigidaParaFaixaRealista() throws Exception {
                // Cenário: LLM gerou distâncias erradas usando pace de tiro para etapas fáceis
                var etapas = List.of(
                        new EtapaTreinoLlmDto(1,  "AQUECIMENTO",    "Trote leve",       10, 2.5, "120-136 bpm", 1, null),
                        new EtapaTreinoLlmDto(2,  "INTERVALADO",    "400m Z5",           4, 0.4, "160-170 bpm", 1, null),
                        new EtapaTreinoLlmDto(3,  "RECUPERACAO",    "Trote leve",        2, 1.0, "120-136 bpm", 1, null),
                        new EtapaTreinoLlmDto(4,  "INTERVALADO",    "400m Z5",           4, 0.4, "160-170 bpm", 1, null),
                        new EtapaTreinoLlmDto(5,  "RECUPERACAO",    "Trote leve",        2, 1.0, "120-136 bpm", 1, null),
                        new EtapaTreinoLlmDto(6,  "INTERVALADO",    "400m Z5",           4, 0.4, "160-170 bpm", 1, null),
                        new EtapaTreinoLlmDto(7,  "RECUPERACAO",    "Trote leve",        2, 1.0, "120-136 bpm", 1, null),
                        new EtapaTreinoLlmDto(8,  "INTERVALADO",    "400m Z5",           4, 0.4, "160-170 bpm", 1, null),
                        new EtapaTreinoLlmDto(9,  "RECUPERACAO",    "Trote leve",        2, 1.0, "120-136 bpm", 1, null),
                        new EtapaTreinoLlmDto(10, "INTERVALADO",    "400m Z5",           4, 0.4, "160-170 bpm", 1, null),
                        new EtapaTreinoLlmDto(11, "RECUPERACAO",    "Trote leve",        2, 1.0, "120-136 bpm", 1, null),
                        new EtapaTreinoLlmDto(12, "DESAQUECIMENTO", "Caminhada",        10, 2.5, "120-136 bpm", 1, null)
                );
                var treino = new TreinoPlanejadoLlmDto(
                        "SEGUNDA", "INTERVALADO", null, null, null, null, null,
                        "50:00", 10.0, null, etapas);

                // Passo 0: corrigir com paceLimiar=4.5 (4:30/km)
                var etapasCorrigidas = invokeCorrigirDistancias(etapas, new BigDecimal("4.5"));
                var treinoCorrigido = new TreinoPlanejadoLlmDto(
                        treino.diaSemana(), treino.tipoTreino(), treino.fcAlvo(),
                        treino.tssPlanejado(), treino.intensidadePlanejada(),
                        treino.percepcaoEsforcoEsperada(), treino.justificativaIa(),
                        treino.duracaoMin(), treino.distanciaKm(), treino.ritmoAlvo(),
                        etapasCorrigidas);

                // AQUECIMENTO não deve mais ser 2.5km — deve ser ~1.85km
                assertThat(etapasCorrigidas.get(0).distanciaKm())
                        .isLessThan(2.0)
                        .isGreaterThan(1.0);

                // Passo 3: reconciliar total com soma das etapas corrigidas
                var treinoFinal = invokeReconciliarDistancia(treinoCorrigido);

                // CA1: total deve estar na faixa realista (não 10km)
                assertThat(treinoFinal.distanciaKm())
                        .isGreaterThanOrEqualTo(5.5)
                        .isLessThanOrEqualTo(8.0);
            }
        }

        // ===== Helpers de reflexão =====

        @SuppressWarnings("unchecked")
        private List<EtapaTreinoLlmDto> invokeCorrigirDistancias(
                List<EtapaTreinoLlmDto> etapas, BigDecimal paceLimiar) throws Exception {
            Method m = IaServiceImpl.class.getDeclaredMethod(
                    "corrigirDistanciasEtapasTemporais", List.class, BigDecimal.class);
            m.setAccessible(true);
            return (List<EtapaTreinoLlmDto>) m.invoke(service, etapas, paceLimiar);
        }

        private TreinoPlanejadoLlmDto invokeReconciliarDistancia(TreinoPlanejadoLlmDto treino) throws Exception {
            Method m = IaServiceImpl.class.getDeclaredMethod(
                    "reconciliarDistanciaComEtapas", TreinoPlanejadoLlmDto.class);
            m.setAccessible(true);
            return (TreinoPlanejadoLlmDto) m.invoke(service, treino);
        }
    }

    @Nested
    @DisplayName("validarEstrutura3Etapas (caracterização do hard-fail atual)")
    class Estrutura3Etapas {

        @Test
        @DisplayName("≠ 3 etapas → LLMException (hoje derruba o plano inteiro)")
        void numeroEtapasErrado() {
            var treino = treino("REGENERATIVO", etapa("AQUECIMENTO"), etapa("PRINCIPAL")); // 2 etapas
            assertThatThrownBy(() -> service.validarEstrutura3Etapas(treino, "REGENERATIVO", "atleta", true))
                    .isInstanceOf(LLMException.class);
        }

        @Test
        @DisplayName("3 etapas na ordem canônica → ok")
        void ordemCanonica() {
            var treino = treino("REGENERATIVO", etapa("AQUECIMENTO"), etapa("PRINCIPAL"), etapa("DESAQUECIMENTO"));
            assertThatNoException().isThrownBy(
                    () -> service.validarEstrutura3Etapas(treino, "REGENERATIVO", "atleta", true));
        }

        @Test
        @DisplayName("fora de ordem com validarOrdem=true → LLMException")
        void ordemTrocada() {
            var treino = treino("REGENERATIVO", etapa("PRINCIPAL"), etapa("AQUECIMENTO"), etapa("DESAQUECIMENTO"));
            assertThatThrownBy(() -> service.validarEstrutura3Etapas(treino, "REGENERATIVO", "atleta", true))
                    .isInstanceOf(LLMException.class);
        }

        @Test
        @DisplayName("LONGO (validarOrdem=false) checa só a contagem, não a ordem")
        void longoSoContagem() {
            var treino = treino("LONGO", etapa("PRINCIPAL"), etapa("AQUECIMENTO"), etapa("DESAQUECIMENTO"));
            assertThatNoException().isThrownBy(
                    () -> service.validarEstrutura3Etapas(treino, "LONGO", "atleta", false));
        }

        private EtapaTreinoLlmDto etapa(String tipo) {
            return new EtapaTreinoLlmDto(1, tipo, "x", 10, 1.0, null, 1, null);
        }

        private TreinoPlanejadoLlmDto treino(String tipo, EtapaTreinoLlmDto... etapas) {
            return new TreinoPlanejadoLlmDto("SEGUNDA", tipo, null, null, null, null, null, null, null, null, List.of(etapas));
        }
    }
}
