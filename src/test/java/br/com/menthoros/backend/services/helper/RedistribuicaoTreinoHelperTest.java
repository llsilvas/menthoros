package br.com.menthoros.backend.services.helper;

import br.com.menthoros.backend.dto.llm.TreinoPlanejadoLlmDto;
import br.com.menthoros.backend.enums.DiaSemana;
import br.com.menthoros.backend.enums.ModoGeracaoPlano;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.menthoros.backend.util.Utils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedistribuicaoTreinoHelperTest {

    @Mock
    private RegraGeracaoTreino regraGeracaoTreinoMock;

    @InjectMocks
    private RedistribuicaoTreinoHelper redistribuicaoTreinoHelper;

    @Test
    @DisplayName("Deve manter LONGO quando não houver dia preferido informado e evitar intensivos consecutivos nos demais")
    void deveFiltrarTreinosIncompativeisNoMeioDaSemana() {
        LocalDate quarta = LocalDate.of(2025, 10, 8);
        LocalDate semanaInicio = quarta.with(DayOfWeek.MONDAY);
        LocalDate semanaFim = semanaInicio.plusDays(6);

        when(regraGeracaoTreinoMock.isMeioSemana(quarta)).thenReturn(true);

        // Sem dia preferido do longão informado, o helper preserva o comportamento compatível:
        // LONGO entra primeiro, e os demais são distribuídos evitando adjacência entre intensivos.
        List<TreinoPlanejadoLlmDto> treinos = List.of(
                criarTreinoMock("QUINTA", "LONGO"),
                criarTreinoMock("SEXTA", "INTERVALADO"),
                criarTreinoMock("SABADO", "CONTINUO"),
                criarTreinoMock("DOMINGO", "REGENERATIVO")
        );

        List<DiaSemana> diasDisponiveis = List.of(
                DiaSemana.QUINTA, DiaSemana.SEXTA, DiaSemana.SABADO, DiaSemana.DOMINGO
        );

        var resultado = redistribuicaoTreinoHelper.redistribuirTreinos(
                treinos, diasDisponiveis, quarta, semanaInicio, semanaFim,
                ModoGeracaoPlano.SEMANA_ATUAL
        );

        assertEquals(4, resultado.size());
        assertTrue(resultado.stream().anyMatch(t -> t.tipoTreino().equals("LONGO")),
                "LONGO deve ser mantido quando não há dia preferido explícito");
        assertTrue(resultado.stream().anyMatch(t -> t.tipoTreino().equals("INTERVALADO")),
                "INTERVALADO tem slot válido e deve ser mantido");
    }

    @Test
    @DisplayName("Deve remover dos treinos os treinos intervalados e longo, caso seja meio da semana")
    void deveRemoverTreinosIncompativeisNoMeioDaSemana() {
        LocalDate quarta = LocalDate.of(2025, 10, 8);
        LocalDate semanaInicio = quarta.with(DayOfWeek.MONDAY);
        LocalDate semanaFim = semanaInicio.plusDays(6);

        when(regraGeracaoTreinoMock.isMeioSemana(quarta)).thenReturn(true);

        List<TreinoPlanejadoLlmDto> treinos = List.of(
                criarTreinoMock("SEGUNDA", "REGENERATIVO"),
                criarTreinoMock("TERCA", "CONTINUO"),
                criarTreinoMock("QUINTA", "INTERVALADO"),
                criarTreinoMock("SABADO", "LONGO")
        );

        List<DiaSemana> diasDisponiveis = List.of(
                DiaSemana.SEGUNDA, DiaSemana.TERCA, DiaSemana.QUINTA, DiaSemana.SABADO);

        var resultado = redistribuicaoTreinoHelper.redistribuirTreinos(
                treinos, diasDisponiveis, quarta, semanaInicio, semanaFim,
                ModoGeracaoPlano.SEMANA_ATUAL
        );

        assertEquals(2, resultado.size());

    }
    @Test
    @DisplayName("Deve retornar lista vazia quando não houver dias disponíveis")
    void deveRetornarListaVaziaQuandoNaoHouverDiasDisponiveis() {
        LocalDate hoje = LocalDate.of(2025, 10, 8);
        LocalDate semanaInicio = hoje.with(DayOfWeek.MONDAY);
        LocalDate semanaFim = semanaInicio.plusDays(6);

        List<TreinoPlanejadoLlmDto> treinos = List.of(
                criarTreinoMock("SEGUNDA", "CONTINUO")
        );

        List<DiaSemana> diasDisponiveis = List.of(); // Sem dias

        var resultado = redistribuicaoTreinoHelper.redistribuirTreinos(
                treinos, diasDisponiveis, hoje, semanaInicio, semanaFim,
                ModoGeracaoPlano.SEMANA_ATUAL
        );

        assertTrue(resultado.isEmpty());
    }

    @Test
    @DisplayName("Deve filtrar dias que já passaram na semana atual")
    void deveFiltrarDiasPassadosNaSemanaAtual() {
        LocalDate quinta = LocalDate.of(2025, 10, 9); // Quinta
        LocalDate semanaInicio = quinta.with(DayOfWeek.MONDAY);
        LocalDate semanaFim = semanaInicio.plusDays(6);

        List<TreinoPlanejadoLlmDto> treinos = List.of(
                criarTreinoMock("SEGUNDA", "CONTINUO"),
                criarTreinoMock("TERCA", "FARTLEK"),
                criarTreinoMock("SEXTA", "LONGO")
        );

        List<DiaSemana> diasDisponiveis = List.of(
                DiaSemana.SEGUNDA, DiaSemana.TERCA, DiaSemana.SEXTA
        );

        var resultado = redistribuicaoTreinoHelper.redistribuirTreinos(
                treinos, diasDisponiveis, quinta, semanaInicio, semanaFim,
                ModoGeracaoPlano.SEMANA_ATUAL
        );

        // Deve considerar apenas Sexta (dias posteriores a Quinta)
        assertTrue(resultado.size() > 0);
        assertTrue(resultado.stream().anyMatch(t -> t.diaSemana().equals("SEXTA")));
    }

    @Test
    @DisplayName("Deve manter todos os dias quando modo for PROXIMA_SEMANA sem dias consecutivos intensos")
    void deveManterTodosDiasQuandoModoForProximaSemana() {
        LocalDate quinta = LocalDate.of(2025, 10, 9);
        LocalDate semanaInicio = quinta.plusWeeks(1).with(DayOfWeek.MONDAY);
        LocalDate semanaFim = semanaInicio.plusDays(6);

        // SEGUNDA, TERCA, QUINTA — FARTLEK(TER) e INTERVALADO(QUI) têm QUA como buffer
        List<TreinoPlanejadoLlmDto> treinos = List.of(
                criarTreinoMock("SEGUNDA", "CONTINUO"),
                criarTreinoMock("TERCA", "FARTLEK"),
                criarTreinoMock("QUINTA", "INTERVALADO")
        );

        List<DiaSemana> diasDisponiveis = List.of(
                DiaSemana.SEGUNDA, DiaSemana.TERCA, DiaSemana.QUINTA
        );

        var resultado = redistribuicaoTreinoHelper.redistribuirTreinos(
                treinos, diasDisponiveis, quinta, semanaInicio, semanaFim,
                ModoGeracaoPlano.PROXIMA_SEMANA
        );

        assertEquals(3, resultado.size());
    }

    @Test
    @DisplayName("Não deve colocar dois treinos intensivos em dias consecutivos")
    void naoDeveColocarDoisIntensivosEmDiasConsecutivos() {
        LocalDate segunda = LocalDate.of(2025, 10, 6);
        LocalDate semanaInicio = segunda;
        LocalDate semanaFim = semanaInicio.plusDays(6);

        // INTERVALADO e TIRO são ambos alta intensidade — não podem ser TER+QUA (consecutivos)
        List<TreinoPlanejadoLlmDto> treinos = List.of(
                criarTreinoMock("SEGUNDA", "REGENERATIVO"),
                criarTreinoMock("TERCA", "INTERVALADO"),
                criarTreinoMock("QUINTA", "TIRO")
        );

        List<DiaSemana> diasDisponiveis = List.of(
                DiaSemana.SEGUNDA, DiaSemana.TERCA, DiaSemana.QUARTA, DiaSemana.QUINTA
        );

        var resultado = redistribuicaoTreinoHelper.redistribuirTreinos(
                treinos, diasDisponiveis, segunda, semanaInicio, semanaFim,
                ModoGeracaoPlano.PROXIMA_SEMANA
        );

        // Verifica que INTERVALADO e TIRO não estão em dias consecutivos
        var diasIntensivos = resultado.stream()
                .filter(t -> t.tipoTreino().equals("INTERVALADO") || t.tipoTreino().equals("TIRO"))
                .map(TreinoPlanejadoLlmDto::diaSemana)
                .map(DiaSemana::valueOf)
                .toList();

        assertEquals(2, diasIntensivos.size(), "Ambos os intensivos devem ser alocados");
        DiaSemana d1 = diasIntensivos.get(0);
        DiaSemana d2 = diasIntensivos.get(1);
        int diff = Math.abs(
                Utils.converterParaDayOfWeek(d1).getValue() -
                Utils.converterParaDayOfWeek(d2).getValue()
        );
        assertTrue(diff > 1, "Dias intensivos não devem ser consecutivos (diff=" + diff + ")");
    }

    @Test
    @DisplayName("Deve descartar intensivo quando não há slot sem conflito de adjacência")
    void deveDescartarIntensivoSemEspacoDisponivel() {
        LocalDate segunda = LocalDate.of(2025, 10, 6);
        LocalDate semanaInicio = segunda;
        LocalDate semanaFim = semanaInicio.plusDays(6);

        // Apenas TER e QUA disponíveis — dois intensivos consecutivos: o segundo deve ser descartado
        List<TreinoPlanejadoLlmDto> treinos = List.of(
                criarTreinoMock("TERCA", "INTERVALADO"),
                criarTreinoMock("QUARTA", "TIRO")
        );

        List<DiaSemana> diasDisponiveis = List.of(DiaSemana.TERCA, DiaSemana.QUARTA);

        var resultado = redistribuicaoTreinoHelper.redistribuirTreinos(
                treinos, diasDisponiveis, segunda, semanaInicio, semanaFim,
                ModoGeracaoPlano.PROXIMA_SEMANA
        );

        assertEquals(1, resultado.size(), "Apenas o primeiro intensivo deve ser alocado");
    }

    @Test
    @DisplayName("Deve permitir treino intensivo e leve em dias consecutivos")
    void devePermitirIntensivoELeveConsecutivos() {
        LocalDate segunda = LocalDate.of(2025, 10, 6);
        LocalDate semanaInicio = segunda;
        LocalDate semanaFim = semanaInicio.plusDays(6);

        // TER=INTERVALADO, QUA=REGENERATIVO — leve depois de intenso é permitido fisiologicamente
        List<TreinoPlanejadoLlmDto> treinos = List.of(
                criarTreinoMock("TERCA", "INTERVALADO"),
                criarTreinoMock("QUARTA", "REGENERATIVO")
        );

        List<DiaSemana> diasDisponiveis = List.of(DiaSemana.TERCA, DiaSemana.QUARTA);

        var resultado = redistribuicaoTreinoHelper.redistribuirTreinos(
                treinos, diasDisponiveis, segunda, semanaInicio, semanaFim,
                ModoGeracaoPlano.PROXIMA_SEMANA
        );

        assertEquals(2, resultado.size(), "Intensivo + leve em dias consecutivos deve ser permitido");
    }

    @Test
    @DisplayName("Deve lidar com lista de treinos vazia")
    void deveLidarComListaTreinosVazia() {
        LocalDate hoje = LocalDate.now();
        LocalDate semanaInicio = hoje.with(DayOfWeek.MONDAY);
        LocalDate semanaFim = semanaInicio.plusDays(6);

        List<TreinoPlanejadoLlmDto> treinos = List.of();
        List<DiaSemana> diasDisponiveis = List.of(DiaSemana.SEGUNDA, DiaSemana.QUARTA);

        var resultado = redistribuicaoTreinoHelper.redistribuirTreinos(
                treinos, diasDisponiveis, hoje, semanaInicio, semanaFim,
                ModoGeracaoPlano.SEMANA_ATUAL
        );

        assertTrue(resultado.isEmpty());
    }

    @Test
    @DisplayName("Deve lidar com mais treinos que dias disponíveis")
    void deveLidarComMaisTreinosQueDiasDisponiveis() {
        LocalDate segunda = LocalDate.of(2025, 10, 6);
        LocalDate semanaInicio = segunda;
        LocalDate semanaFim = semanaInicio.plusDays(6);

        List<TreinoPlanejadoLlmDto> treinos = List.of(
                criarTreinoMock("TERCA", "CONTINUO"),
                criarTreinoMock("QUARTA", "FARTLEK"),
                criarTreinoMock("QUINTA", "REGENERATIVO"),
                criarTreinoMock("SEXTA", "CONTINUO"),
                criarTreinoMock("SABADO", "LONGO")
        );

        // Apenas 2 dias disponíveis
        List<DiaSemana> diasDisponiveis = List.of(DiaSemana.QUARTA, DiaSemana.SABADO);

        var resultado = redistribuicaoTreinoHelper.redistribuirTreinos(
                treinos, diasDisponiveis, segunda, semanaInicio, semanaFim,
                ModoGeracaoPlano.SEMANA_ATUAL
        );

        // Deve distribuir máximo de 2 treinos
        assertTrue(resultado.size() <= 2);
    }

    @Test
    @DisplayName("Deve priorizar LONGO no dia preferido e redistribuir os demais ao redor")
    void devePriorizarLongoNoDiaPreferido() {
        LocalDate segunda = LocalDate.of(2025, 10, 6);
        LocalDate semanaInicio = segunda;
        LocalDate semanaFim = semanaInicio.plusDays(6);

        List<TreinoPlanejadoLlmDto> treinos = List.of(
                criarTreinoMock("TERCA", "CONTINUO"),
                criarTreinoMock("QUARTA", "INTERVALADO"),
                criarTreinoMock("SABADO", "LONGO")
        );

        List<DiaSemana> diasDisponiveis = List.of(
                DiaSemana.TERCA, DiaSemana.QUINTA, DiaSemana.DOMINGO
        );

        var resultado = redistribuicaoTreinoHelper.redistribuirTreinos(
                treinos, diasDisponiveis, segunda, semanaInicio, semanaFim,
                ModoGeracaoPlano.PROXIMA_SEMANA, DiaSemana.DOMINGO
        );

        assertTrue(resultado.stream().anyMatch(t ->
                        "LONGO".equals(t.tipoTreino()) && "DOMINGO".equals(t.diaSemana())),
                "LONGO deve ser fixado no dia preferido");
        assertTrue(resultado.stream().anyMatch(t -> "CONTINUO".equals(t.tipoTreino())));
    }

    @Test
    @DisplayName("Deve descartar LONGO quando o dia preferido não estiver disponível")
    void deveDescartarLongoQuandoDiaPreferidoNaoEstiverDisponivel() {
        LocalDate segunda = LocalDate.of(2025, 10, 6);
        LocalDate semanaInicio = segunda;
        LocalDate semanaFim = semanaInicio.plusDays(6);

        List<TreinoPlanejadoLlmDto> treinos = List.of(
                criarTreinoMock("SABADO", "LONGO"),
                criarTreinoMock("TERCA", "CONTINUO")
        );

        List<DiaSemana> diasDisponiveis = List.of(DiaSemana.TERCA, DiaSemana.QUINTA);

        var resultado = redistribuicaoTreinoHelper.redistribuirTreinos(
                treinos, diasDisponiveis, segunda, semanaInicio, semanaFim,
                ModoGeracaoPlano.PROXIMA_SEMANA, DiaSemana.DOMINGO
        );

        assertTrue(resultado.stream().noneMatch(t -> "LONGO".equals(t.tipoTreino())));
        assertTrue(resultado.stream().anyMatch(t -> "CONTINUO".equals(t.tipoTreino())));
    }


    @Test
    @DisplayName("§5.3: SEMANA_ATUAL com dias-alvo dos slots — CONTINUO vai para o dia prescrito, nao o greedy")
    void deveRespeitarDiaAlvoDoSlotNaRedistribuicao() {
        LocalDate segunda = LocalDate.of(2025, 10, 6);
        LocalDate semanaInicio = segunda;
        LocalDate semanaFim = semanaInicio.plusDays(6);
        // hoje = segunda -> dias validos (SEMANA_ATUAL): TERCA..DOMINGO

        List<TreinoPlanejadoLlmDto> treinos = List.of(
                criarTreinoMock("QUINTA", "LONGO"),
                criarTreinoMock("QUINTA", "REGENERATIVO"),
                criarTreinoMock("QUINTA", "CONTINUO")
        );
        List<DiaSemana> diasDisponiveis = List.of(
                DiaSemana.TERCA, DiaSemana.QUARTA, DiaSemana.QUINTA,
                DiaSemana.SEXTA, DiaSemana.SABADO, DiaSemana.DOMINGO);

        // Skeleton prescreve CONTINUO na SEXTA; sem guia o greedy o colocaria na QUINTA (1o livre).
        java.util.Map<br.com.menthoros.backend.enums.TipoTreino, DiaSemana> diasAlvo =
                java.util.Map.of(br.com.menthoros.backend.enums.TipoTreino.CONTINUO, DiaSemana.SEXTA);

        var resultado = redistribuicaoTreinoHelper.redistribuirTreinos(
                treinos, diasDisponiveis, segunda, semanaInicio, semanaFim,
                ModoGeracaoPlano.SEMANA_ATUAL, null, diasAlvo);

        assertTrue(resultado.stream().anyMatch(
                        t -> "CONTINUO".equals(t.tipoTreino()) && "SEXTA".equals(t.diaSemana())),
                "CONTINUO deve cair no dia prescrito pelo SessionSlot (SEXTA)");
    }

    @Test
    @DisplayName("§5.3: dia-alvo indisponivel/ocupado — cai no greedy de fallback (algoritmo inalterado)")
    void deveCairNoFallbackQuandoDiaAlvoIndisponivel() {
        LocalDate segunda = LocalDate.of(2025, 10, 6);
        LocalDate semanaInicio = segunda;
        LocalDate semanaFim = semanaInicio.plusDays(6);

        List<TreinoPlanejadoLlmDto> treinos = List.of(
                criarTreinoMock("QUINTA", "REGENERATIVO"),
                criarTreinoMock("QUINTA", "CONTINUO")
        );
        List<DiaSemana> diasDisponiveis = List.of(DiaSemana.TERCA, DiaSemana.QUARTA);

        // Slot pede CONTINUO no SABADO, que nao esta entre os dias validos -> fallback greedy.
        java.util.Map<br.com.menthoros.backend.enums.TipoTreino, DiaSemana> diasAlvo =
                java.util.Map.of(br.com.menthoros.backend.enums.TipoTreino.CONTINUO, DiaSemana.SABADO);

        var resultado = redistribuicaoTreinoHelper.redistribuirTreinos(
                treinos, diasDisponiveis, segunda, semanaInicio, semanaFim,
                ModoGeracaoPlano.SEMANA_ATUAL, null, diasAlvo);

        // CONTINUO ainda e alocado (num dos dias validos), nao descartado por causa do dia-alvo invalido.
        assertTrue(resultado.stream().anyMatch(t -> "CONTINUO".equals(t.tipoTreino())),
                "CONTINUO deve ser alocado via fallback quando o dia-alvo do slot nao esta disponivel");
        assertTrue(resultado.stream().allMatch(
                        t -> List.of("TERCA", "QUARTA").contains(t.diaSemana())),
                "todos os treinos ficam nos dias validos");
    }

    private TreinoPlanejadoLlmDto criarTreinoMock(String dia, String tipo) {
        return new TreinoPlanejadoLlmDto(
                dia, tipo, "140-160% FCmáx", 100, 1.0, 7,
                "Treino mock", "60", 10.0, "5:00-5:30/km", null
        );
    }
}
