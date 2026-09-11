package br.com.menthoros.backend.services.helper;

import br.com.menthoros.backend.dto.llm.TreinoPlanejadoLlmDto;
import br.com.menthoros.backend.enums.DiaSemana;
import br.com.menthoros.backend.enums.ModoGeracaoPlano;
import br.com.menthoros.backend.enums.TipoTreino;
import br.com.menthoros.backend.util.Utils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedistribuicaoTreinoHelper {

    private final RegraGeracaoTreino regraGeracao;

    private static final Set<TipoTreino> TIPOS_INCOMPATIVEIS_MEIO_SEMANA = Set.of(
            TipoTreino.LONGO,
            TipoTreino.INTERVALADO,
            TipoTreino.TEMPO_RUN
    );

    /**
     * Tipos de alta intensidade que exigem ao menos 1 dia de intervalo entre si.
     * Critério: fatorImpacto >= 1.15 (exige 36h+ de recuperação).
     */
    private static final Set<TipoTreino> TIPOS_ALTA_INTENSIDADE = Set.of(
            TipoTreino.LONGO,
            TipoTreino.FARTLEK,
            TipoTreino.TEMPO_RUN,
            TipoTreino.PROVA,
            TipoTreino.INTERVALADO,
            TipoTreino.TIRO,
            TipoTreino.SUBIDA
    );

    public List<TreinoPlanejadoLlmDto> redistribuirTreinos(
            List<TreinoPlanejadoLlmDto> treinosLlm,
            List<DiaSemana> diasDisponiveisAtleta,
            LocalDate hoje,
            LocalDate semanaInicio,
            LocalDate semanaFim,
            ModoGeracaoPlano modoGeracao
    ) {
        return redistribuirTreinos(
                treinosLlm,
                diasDisponiveisAtleta,
                hoje,
                semanaInicio,
                semanaFim,
                modoGeracao,
                null
        );
    }

    public List<TreinoPlanejadoLlmDto> redistribuirTreinos(
            List<TreinoPlanejadoLlmDto> treinosLlm,
            List<DiaSemana> diasDisponiveisAtleta,
            LocalDate hoje,
            LocalDate semanaInicio,
            LocalDate semanaFim,
            ModoGeracaoPlano modoGeracao,
            DiaSemana diaPreferidoLongo
    ) {
        return redistribuirTreinos(treinosLlm, diasDisponiveisAtleta, hoje, semanaInicio, semanaFim,
                modoGeracao, diaPreferidoLongo, java.util.Map.of());
    }

    /**
     * Overload com dias-alvo por tipo (planner-engine-enforcement §5.3): quando o planner esta ligado,
     * o caller passa o dia prescrito de cada {@code SessionSlot} do skeleton; a redistribuicao tenta
     * respeitar esse dia primeiro (se valido, livre e sem conflito de adjacencia) e, so entao, cai no
     * algoritmo greedy de fallback existente — que fica inalterado. Mapa vazio = comportamento legado.
     */
    public List<TreinoPlanejadoLlmDto> redistribuirTreinos(
            List<TreinoPlanejadoLlmDto> treinosLlm,
            List<DiaSemana> diasDisponiveisAtleta,
            LocalDate hoje,
            LocalDate semanaInicio,
            LocalDate semanaFim,
            ModoGeracaoPlano modoGeracao,
            DiaSemana diaPreferidoLongo,
            java.util.Map<TipoTreino, DiaSemana> diasAlvoPorTipo
    ) {

        log.info("Iniciando redistribuição - {} treinos da LLM, modo: {}",
                treinosLlm.size(), modoGeracao);

        List<DiaSemana> diasValidos = filtrarDiasValidos(
                diasDisponiveisAtleta,
                hoje,
                semanaInicio,
                semanaFim,
                modoGeracao
        );

        if (diasValidos.isEmpty()) {
            log.warn("Nenhum dia disponível para redistribuição de treinos na semana");
            return Collections.emptyList();
        }


        // 1. Filtrar treinos compatíveis (remove LONGO/INTERVALADO/TEMPO_RUN se meio da semana)
        List<TreinoPlanejadoLlmDto> treinosFiltrados = filtrarTreinosCompativeis(
                treinosLlm,
                hoje,
                modoGeracao
        );

        if (treinosFiltrados.isEmpty()) {
            log.warn("Nenhum treino compatível após filtro");
            return Collections.emptyList();
        }

        // 2. Redistribuir treinos pelos dias disponíveis (prioriza treinos leves)
        List<TreinoPlanejadoLlmDto> treinosRedistribuidos = redistribuir(
                treinosFiltrados,
                diasValidos,
                semanaInicio,
                diaPreferidoLongo,
                diasAlvoPorTipo
        );

        log.info("Redistribuição concluída: {} treinos redistribuídos em {} dias",
                treinosRedistribuidos.size(), diasValidos.size());

        return treinosRedistribuidos;
    }

    /**
     * Verifica (sem excluir) se a LLM gerou treinos de alta intensidade no meio da semana.
     * O LLM já recebe instrução sobre dias restantes — este método serve apenas como guard de log.
     */
    private List<TreinoPlanejadoLlmDto> filtrarTreinosCompativeis(
            List<TreinoPlanejadoLlmDto> treinos,
            LocalDate hoje,
            ModoGeracaoPlano modo) {

        if (modo == ModoGeracaoPlano.SEMANA_ATUAL && regraGeracao.isMeioSemana(hoje)) {
            treinos.forEach(treino -> {
                TipoTreino tipo = TipoTreino.valueOf(treino.tipoTreino());
                if (TIPOS_INCOMPATIVEIS_MEIO_SEMANA.contains(tipo)) {
                    log.warn("Safety-net: LLM gerou treino {} no meio da semana — verifique o prompt.", tipo);
                }
            });
        }

        return treinos;
    }

    private List<DiaSemana> filtrarDiasValidos(
            List<DiaSemana> diasOriginais,
            LocalDate hoje,
            LocalDate semanaInicio,
            LocalDate semanaFim,
            ModoGeracaoPlano modoGeracao
    ) {
        if (modoGeracao == ModoGeracaoPlano.PROXIMA_SEMANA) {
            return new ArrayList<>(diasOriginais);
        }

        return diasOriginais.stream()
                .filter(dia -> {
                    LocalDate dataDia = calcularDataDia(semanaInicio, dia);
                    boolean isValido = dataDia.isAfter(hoje) && !dataDia.isAfter(semanaFim);
                    if (!isValido) {
                        log.debug("Dia {} ({}) descartado - já passou ou fora da semana",
                                dia, dataDia);
                    }
                    return isValido;
                })
                .toList();
    }

    /**
     * Redistribui treinos pelos dias disponíveis usando greedy placement.
     *
     * <p>Garante que dois treinos de alta intensidade nunca sejam alocados em dias consecutivos,
     * prevenindo overtraining e risco de lesão. Treinos leves são alocados primeiro (menor prioridade
     * numérica), abrindo espaço para que os intensos encontrem slots com buffers adequados.
     * Se um treino intenso não encontrar slot válido (sem conflito de adjacência), é descartado
     * com log de aviso.
     */
    private List<TreinoPlanejadoLlmDto> redistribuir(
            List<TreinoPlanejadoLlmDto> treinos,
            List<DiaSemana> diasValidos,
            LocalDate semanaInicio,
            DiaSemana diaPreferidoLongo,
            java.util.Map<TipoTreino, DiaSemana> diasAlvoPorTipo) {

        List<TreinoPlanejadoLlmDto> treinosOrdenados = ordenarPorPrioridade(treinos);
        List<DiaSemana> diasOrdenados = ordenarDiasSemana(diasValidos);

        // Mapa: dia → treino alocado (preserva ordem de inserção)
        Map<DiaSemana, TreinoPlanejadoLlmDto> atribuicoes = new LinkedHashMap<>();

        TreinoPlanejadoLlmDto treinoLongo = treinosOrdenados.stream()
                .filter(t -> TipoTreino.LONGO.name().equals(t.tipoTreino()))
                .findFirst()
                .orElse(null);

        if (treinoLongo != null) {
            if (diaPreferidoLongo != null && diasOrdenados.contains(diaPreferidoLongo)) {
                atribuicoes.put(diaPreferidoLongo, treinoLongo);
                log.debug("Treino LONGO priorizado no dia preferido {} ({})",
                        diaPreferidoLongo, calcularDataDia(semanaInicio, diaPreferidoLongo));
            } else if (diaPreferidoLongo != null) {
                log.warn("Treino LONGO descartado — dia preferido {} não está disponível na semana.",
                        diaPreferidoLongo);
            }
        }

        for (TreinoPlanejadoLlmDto treino : treinosOrdenados) {
            TipoTreino tipo = TipoTreino.valueOf(treino.tipoTreino());
            boolean isIntensivo = TIPOS_ALTA_INTENSIDADE.contains(tipo);

            if (tipo == TipoTreino.LONGO && diaPreferidoLongo != null) {
                continue;
            }

            DiaSemana diaEscolhido = null;

            // §5.3: tenta primeiro o dia prescrito pelo SessionSlot do skeleton (quando o planner
            // guia a redistribuicao); so aceita se valido, livre e sem conflito de adjacencia.
            DiaSemana diaAlvo = diasAlvoPorTipo.get(tipo);
            if (diaAlvo != null && diasOrdenados.contains(diaAlvo) && !atribuicoes.containsKey(diaAlvo)
                    && !(isIntensivo && temIntensivoConsecutivo(diaAlvo, atribuicoes))) {
                diaEscolhido = diaAlvo;
            }

            for (DiaSemana dia : diasOrdenados) {
                if (diaEscolhido != null) break; // ja resolvido pelo dia-alvo do slot
                if (atribuicoes.containsKey(dia)) continue; // dia já ocupado

                if (isIntensivo && temIntensivoConsecutivo(dia, atribuicoes)) continue; // conflito de adjacência

                diaEscolhido = dia;
                break;
            }

            if (diaEscolhido != null) {
                atribuicoes.put(diaEscolhido, treino);
                log.debug("Treino redistribuído: {} → {} ({})",
                        treino.tipoTreino(), diaEscolhido, calcularDataDia(semanaInicio, diaEscolhido));
            } else {
                log.warn("Treino {} descartado — nenhum slot disponível sem conflito de dias consecutivos.", tipo);
            }
        }

        return atribuicoes.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> Utils.converterParaDayOfWeek(e.getKey()).getValue()))
                .map(e -> atualizarDiaTreino(e.getValue(), e.getKey(), calcularDataDia(semanaInicio, e.getKey())))
                .collect(Collectors.toList());
    }

    /**
     * Verifica se o dia candidato é adjacente a algum dia já alocado com sessão de alta intensidade.
     */
    private boolean temIntensivoConsecutivo(DiaSemana dia, Map<DiaSemana, TreinoPlanejadoLlmDto> atribuicoes) {
        return atribuicoes.entrySet().stream()
                .anyMatch(e -> {
                    TipoTreino tipoAtribuido = TipoTreino.valueOf(e.getValue().tipoTreino());
                    return TIPOS_ALTA_INTENSIDADE.contains(tipoAtribuido)
                            && diasSaoConsecutivos(dia, e.getKey());
                });
    }

    /**
     * Retorna true se os dois dias da semana são consecutivos (diferença de 1 dia).
     * Não há wrap-around entre domingo e segunda (semana já encerrada).
     */
    private boolean diasSaoConsecutivos(DiaSemana d1, DiaSemana d2) {
        int diff = Math.abs(
                Utils.converterParaDayOfWeek(d1).getValue()
                        - Utils.converterParaDayOfWeek(d2).getValue()
        );
        return diff == 1;
    }

    /**
     * Ordena treinos por prioridade de execução
     */
    private List<TreinoPlanejadoLlmDto> ordenarPorPrioridade(List<TreinoPlanejadoLlmDto> treinos) {
        Map<TipoTreino, Integer> prioridades = Map.of(
                TipoTreino.LONGO, 0,
                TipoTreino.REGENERATIVO, 1,
                TipoTreino.CONTINUO, 2,
                TipoTreino.FARTLEK, 3,
                TipoTreino.INTERVALADO, 4,
                TipoTreino.TEMPO_RUN, 5,
                TipoTreino.TIRO, 6
        );

        return treinos.stream()
                .sorted(Comparator.comparingInt(t ->
                        prioridades.getOrDefault(
                                TipoTreino.valueOf(t.tipoTreino()),
                                99
                        )
                ))
                .toList();
    }

    /**
     * Ordena dias da semana
     */
    private List<DiaSemana> ordenarDiasSemana(List<DiaSemana> dias) {
        return dias.stream()
                .sorted(Comparator.comparingInt(d ->
                        Utils.converterParaDayOfWeek(d).getValue()
                ))
                .toList();
    }

    /**
     * Calcula data de um dia específico da semana
     */
    private LocalDate calcularDataDia(LocalDate semanaInicio, DiaSemana dia) {
        DayOfWeek dayOfWeek = Utils.converterParaDayOfWeek(dia);
        return semanaInicio.with(TemporalAdjusters.nextOrSame(dayOfWeek));
    }

    /**
     * Cria novo DTO com dia atualizado (imutável)
     */
    private TreinoPlanejadoLlmDto atualizarDiaTreino(
            TreinoPlanejadoLlmDto original,
            DiaSemana novoDia,
            LocalDate novaData) {

        // Assumindo que TreinoPlanejadoLlmDto é um record
        return new TreinoPlanejadoLlmDto(
                novoDia.name(),  // Atualiza o dia
                original.tipoTreino(),
                original.fcAlvo(),
                original.tssPlanejado(),
                original.intensidadePlanejada(),
                original.percepcaoEsforcoEsperada(),
                original.justificativaIa(),
                original.duracaoMin(),
                original.distanciaKm(),
                original.ritmoAlvo(),
                original.etapas(),
                original.descricao(),
                original.zonaAlvo(),
                original.provaId()
        );
    }
}
