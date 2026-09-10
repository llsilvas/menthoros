package br.com.menthoros.backend.domain.planner;

import br.com.menthoros.backend.enums.TipoTreino;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Atribui um dia a cada {@link SessionSlot} composto (design planner-engine-enforcement Decisao 4;
 * regras absorvidas da {@code WeeklyDistributionSkill} orfa, agora em {@code domain/planner} — o
 * registry de skills nao pode virar dependencia do nucleo). Puro e deterministico.
 *
 * <p>Regras: (1) a PROVA fica no dia real dela (ancora fixa, ignora disponibilidade); (2) a
 * sessao-chave LONGO vai para o dia preferido, senao o ultimo dia disponivel; (3) sessoes duras nunca
 * em dias consecutivos (inclusive a fronteira domingo→segunda), maximizando o espacamento; (4) as
 * demais preenchem os dias livres em ordem canonica. Empates resolvidos pela ordem canonica da semana.
 */
public final class SessionDayAllocator {

    private static final EnumSet<TipoTreino> DURAS =
            EnumSet.of(TipoTreino.INTERVALADO, TipoTreino.TIRO, TipoTreino.TEMPO_RUN, TipoTreino.SUBIDA, TipoTreino.FARTLEK);

    /**
     * @param composed        slots com {@code day == null}, na ordem de prioridade (chave primeiro)
     * @param availableDays   dias disponiveis do atleta
     * @param preferredLongDay dia preferido do longao ({@code null} → usa o ultimo dia disponivel)
     * @param provaDay        dia real da prova ({@code null} quando nao ha slot PROVA nesta semana)
     */
    public List<SessionSlot> allocate(List<SessionSlot> composed,
                                      List<DayOfWeek> availableDays,
                                      DayOfWeek preferredLongDay,
                                      DayOfWeek provaDay) {
        if (composed == null) {
            throw new IllegalArgumentException("composed cannot be null");
        }
        if (composed.isEmpty()) {
            return List.of();
        }
        List<DayOfWeek> dias = availableDays == null ? List.of()
                : availableDays.stream().distinct().sorted(Comparator.comparingInt(DayOfWeek::getValue)).toList();

        List<SessionSlot> result = new ArrayList<>(composed.size());
        for (int i = 0; i < composed.size(); i++) result.add(null);

        List<DayOfWeek> livres = new ArrayList<>(dias);
        List<DayOfWeek> diasComDura = new ArrayList<>(); // ancoras de intensidade (duras + prova)

        // 1. PROVA no dia real dela.
        for (int i = 0; i < composed.size(); i++) {
            SessionSlot s = composed.get(i);
            if (TipoTreino.PROVA.name().equals(s.sessionType()) && provaDay != null) {
                result.set(i, comDia(s, provaDay));
                livres.remove(provaDay);
                diasComDura.add(provaDay);
            }
        }

        // 2. Sessao-chave LONGO: dia preferido se livre, senao ultimo dia disponivel livre.
        for (int i = 0; i < composed.size(); i++) {
            SessionSlot s = composed.get(i);
            if (result.get(i) != null) continue;
            if (s.chave() && TipoTreino.LONGO.name().equals(s.sessionType())) {
                DayOfWeek alvo = (preferredLongDay != null && livres.contains(preferredLongDay))
                        ? preferredLongDay
                        : (livres.isEmpty() ? null : livres.get(livres.size() - 1));
                if (alvo != null) {
                    result.set(i, comDia(s, alvo));
                    livres.remove(alvo);
                }
            }
        }

        // 3. Sessoes duras: dia livre que maximiza a distancia minima aos dias de intensidade ja usados,
        //    preferindo nao-adjacente. Ordem canonica desempata.
        for (int i = 0; i < composed.size(); i++) {
            SessionSlot s = composed.get(i);
            if (result.get(i) != null) continue;
            if (DURAS.contains(TipoTreino.valueOf(s.sessionType()))) {
                DayOfWeek melhor = escolherDiaEspacado(livres, diasComDura);
                if (melhor != null) {
                    result.set(i, comDia(s, melhor));
                    livres.remove(melhor);
                    diasComDura.add(melhor);
                }
            }
        }

        // 4. Demais slots: dias livres em ordem canonica.
        for (int i = 0; i < composed.size(); i++) {
            if (result.get(i) != null) continue;
            if (!livres.isEmpty()) {
                DayOfWeek d = livres.remove(0);
                result.set(i, comDia(composed.get(i), d));
            } else {
                result.set(i, composed.get(i)); // sem dia livre — mantem null (nao deve ocorrer: slots ≤ dias)
            }
        }
        return result;
    }

    /** Escolhe o dia livre cuja menor distancia (com wrap dom→seg) aos dias de intensidade e maxima. */
    private DayOfWeek escolherDiaEspacado(List<DayOfWeek> livres, List<DayOfWeek> ancoras) {
        DayOfWeek melhor = null;
        int melhorDist = -1;
        for (DayOfWeek d : livres) { // livres ja esta em ordem canonica → desempate estavel
            int dist = ancoras.isEmpty() ? Integer.MAX_VALUE
                    : ancoras.stream().mapToInt(a -> distanciaCircular(d, a)).min().orElse(Integer.MAX_VALUE);
            if (dist > melhorDist) {
                melhorDist = dist;
                melhor = d;
            }
        }
        return melhor;
    }

    /** Distancia entre dois dias da semana com wrap (dom↔seg adjacentes) — 1 = consecutivos. */
    private int distanciaCircular(DayOfWeek a, DayOfWeek b) {
        int diff = Math.abs(a.getValue() - b.getValue());
        return Math.min(diff, 7 - diff);
    }

    private SessionSlot comDia(SessionSlot s, DayOfWeek dia) {
        return new SessionSlot(dia, s.sessionType(), s.targetTss(), s.intensityZone(), s.chave(), s.durationMinutes());
    }
}
