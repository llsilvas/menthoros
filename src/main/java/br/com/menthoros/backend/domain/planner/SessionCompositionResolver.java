package br.com.menthoros.backend.domain.planner;

import br.com.menthoros.backend.enums.TipoTreino;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Compoe a lista de {@link SessionSlot} prescritivos de uma semana ANTES da alocacao de dias
 * (design planner-engine-enforcement Decisao 4b; ADR-0011). Puro e deterministico: dada a mesma
 * entrada, produz a mesma composicao (requisito do golden set).
 *
 * <p>Contrato (ADR-0011): modelo de carga LINEAR (`TSS = fatorImpacto x TAXA_BASE x horas`, o
 * `fatorImpacto` do {@link TipoTreino} e a taxa de carga por hora, NAO um IF — sem quadrado);
 * `targetTss` e a ancora e a duracao e o output; minimos de duracao vencem o alvo; polarizacao soft
 * (fracao de TSS em zona alta) escolhida por proximidade da faixa da fase. O dia de cada slot fica
 * {@code null} aqui — e atribuido na alocacao (Decisao 4).
 */
public final class SessionCompositionResolver {

    /** Carga (TSS) de 1h de treino FACIL (fatorImpacto 1.0). Calibravel via shadow. */
    public static final double TAXA_BASE = 50.0;

    // Abaixo deste targetTss semanal a semana e leve demais para uma sessao dura (calibracao
    // 2026-09-11): so aerobico. Mantem coerencia com o teto de intensidade do SkeletonComplianceChecker.
    private static final double LIMIAR_SESSAO_DURA_TSS = 220.0;

    /** Tipos de "sessao dura" (numerador da polarizacao). PROVA e excluida da razao. */
    private static final EnumSet<TipoTreino> DURAS =
            EnumSet.of(TipoTreino.INTERVALADO, TipoTreino.TIRO, TipoTreino.TEMPO_RUN, TipoTreino.SUBIDA, TipoTreino.FARTLEK);

    /** Limites de duracao (min-max, minutos) por tipo — clamp da derivacao de duracao (ADR-0011 item 5). */
    private static final Map<TipoTreino, int[]> CLAMP = new EnumMap<>(TipoTreino.class);
    static {
        CLAMP.put(TipoTreino.REGENERATIVO, new int[]{20, 45});
        CLAMP.put(TipoTreino.FACIL,        new int[]{30, 75});
        CLAMP.put(TipoTreino.CONTINUO,     new int[]{40, 90});
        CLAMP.put(TipoTreino.LONGO,        new int[]{60, 150});
        CLAMP.put(TipoTreino.TEMPO_RUN,    new int[]{30, 75});
        CLAMP.put(TipoTreino.INTERVALADO,  new int[]{40, 80});
        CLAMP.put(TipoTreino.TIRO,         new int[]{30, 60});
        CLAMP.put(TipoTreino.FARTLEK,      new int[]{40, 75});
        CLAMP.put(TipoTreino.SUBIDA,       new int[]{30, 60});
        CLAMP.put(TipoTreino.PROVA,        new int[]{20, 300});
    }

    private record PhaseSpec(
            List<TipoTreino> prioridade,
            int tetoDuras,
            double faixaBaixa,
            double faixaAlta,
            TipoTreino chave,        // null quando a fase nao tem chave
            boolean chaveComGate) {  // LONGO condicionado a capacidade recente (RETURN_TO_TRAINING)
    }

    private static final Map<TrainingPhase, PhaseSpec> TABELA = new EnumMap<>(TrainingPhase.class);
    static {
        TABELA.put(TrainingPhase.BASE, new PhaseSpec(
                List.of(TipoTreino.LONGO, TipoTreino.FACIL, TipoTreino.CONTINUO, TipoTreino.FACIL, TipoTreino.TEMPO_RUN, TipoTreino.REGENERATIVO),
                1, 0.0, 0.12, TipoTreino.LONGO, false));
        TABELA.put(TrainingPhase.BUILD, new PhaseSpec(
                List.of(TipoTreino.LONGO, TipoTreino.INTERVALADO, TipoTreino.FACIL, TipoTreino.TEMPO_RUN, TipoTreino.CONTINUO, TipoTreino.REGENERATIVO),
                2, 0.15, 0.22, TipoTreino.LONGO, false));
        TABELA.put(TrainingPhase.PEAK, new PhaseSpec(
                List.of(TipoTreino.LONGO, TipoTreino.INTERVALADO, TipoTreino.TEMPO_RUN, TipoTreino.FACIL, TipoTreino.TIRO, TipoTreino.REGENERATIVO),
                2, 0.20, 0.28, TipoTreino.LONGO, false));
        TABELA.put(TrainingPhase.TAPER, new PhaseSpec(
                List.of(TipoTreino.TEMPO_RUN, TipoTreino.FACIL, TipoTreino.REGENERATIVO, TipoTreino.LONGO),
                1, 0.0, 0.12, null, false));
        TABELA.put(TrainingPhase.RACE_WEEK, new PhaseSpec(
                List.of(TipoTreino.PROVA, TipoTreino.REGENERATIVO, TipoTreino.FACIL),
                0, 0.0, 0.0, TipoTreino.PROVA, false));
        TABELA.put(TrainingPhase.RECOVERY, new PhaseSpec(
                List.of(TipoTreino.REGENERATIVO, TipoTreino.FACIL),
                0, 0.0, 0.0, null, false));
        TABELA.put(TrainingPhase.POST_RACE, new PhaseSpec(
                List.of(TipoTreino.REGENERATIVO, TipoTreino.FACIL),
                0, 0.0, 0.0, null, false));
        TABELA.put(TrainingPhase.RETURN_TO_TRAINING, new PhaseSpec(
                List.of(TipoTreino.FACIL, TipoTreino.CONTINUO, TipoTreino.LONGO, TipoTreino.REGENERATIVO),
                0, 0.0, 0.0, TipoTreino.LONGO, true));
    }

    /**
     * Entrada da composicao. `provaHoras` != null apenas quando a fase coloca um slot PROVA
     * (RACE_WEEK): a duracao estimada da prova em horas (design 4b, item 7).
     */
    public record CompositionRequest(
            TrainingPhase phase,
            double targetTss,
            int diasDisponiveis,
            Integer maxSessoesPorSemana,
            Integer duracaoMaximaMinutos,
            int longoesRealizados21d,
            Double provaHoras) {
    }

    /** Idempotente/puro. Retorna slots com {@code day == null} (o dia e atribuido na alocacao). */
    public List<SessionSlot> compose(CompositionRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("CompositionRequest cannot be null");
        }
        PhaseSpec spec = TABELA.get(req.phase());
        if (spec == null) { // CALIBRATION (reservada ao cold-start) ou fase nao emitida aqui
            return List.of();
        }
        int piso = (req.phase() == TrainingPhase.RECOVERY || req.phase() == TrainingPhase.POST_RACE) ? 0 : 1;
        int sessionCount = resolverSessionCount(req, piso);
        if (sessionCount == 0) {
            return List.of();
        }

        TipoTreino chave = resolverChave(spec, req);
        List<TipoTreino> prioridade = prioridadeElegivel(spec, req);

        // Escolhe a contagem de duras por proximidade da faixa de polarizacao (soft; empate -> menos duras).
        // Gate de carga (calibracao 2026-09-11): semana leve (targetTss < LIMIAR) nao recebe sessao dura —
        // um intervalado real (~55-65 TSS) dominaria uma semana pequena e seria reprovado pelo compliance.
        // PROVA (RACE_WEEK) nao passa por aqui: e a chave, sempre incluida.
        int maxDuras = req.targetTss() < LIMIAR_SESSAO_DURA_TSS
                ? 0
                : Math.min(spec.tetoDuras(), contarDurasDisponiveis(prioridade, chave));
        int melhorDuras = 0;
        double melhorDist = Double.MAX_VALUE;
        for (int h = 0; h <= maxDuras; h++) {
            List<TipoTreino> tipos = montarTipos(prioridade, chave, sessionCount, h);
            double dist = distanciaDaFaixa(fracaoAlta(tipos), spec);
            if (dist < melhorDist - 1e-9) {
                melhorDist = dist;
                melhorDuras = h;
            }
        }

        List<TipoTreino> tipos = montarTipos(prioridade, chave, sessionCount, melhorDuras);
        return repartir(tipos, chave, req);
    }

    private int resolverSessionCount(CompositionRequest req, int piso) {
        int dias = Math.max(0, req.diasDisponiveis());
        int teto = req.maxSessoesPorSemana() != null ? Math.max(0, req.maxSessoesPorSemana()) : dias;
        int count = Math.min(dias, teto);
        return Math.max(piso == 0 ? 0 : Math.min(dias, 1), count == 0 ? (piso == 0 ? 0 : Math.min(dias, 1)) : count);
    }

    private TipoTreino resolverChave(PhaseSpec spec, CompositionRequest req) {
        if (spec.chave() == null) {
            return null;
        }
        if (spec.chaveComGate() && req.longoesRealizados21d() <= 0) {
            return null; // LONGO gated: sem evidencia de capacidade recente, sem chave
        }
        return spec.chave();
    }

    private List<TipoTreino> prioridadeElegivel(PhaseSpec spec, CompositionRequest req) {
        if (spec.chaveComGate() && req.longoesRealizados21d() <= 0) {
            // remove o LONGO gated da prioridade (nao elegivel)
            List<TipoTreino> lista = new ArrayList<>(spec.prioridade());
            lista.removeIf(t -> t == TipoTreino.LONGO);
            return lista;
        }
        return spec.prioridade();
    }

    private int contarDurasDisponiveis(List<TipoTreino> prioridade, TipoTreino chave) {
        int n = 0;
        for (TipoTreino t : prioridade) {
            if (DURAS.contains(t) && t != chave) n++;
        }
        return n;
    }

    /** Monta a lista de tipos de tamanho sessionCount: chave -> duras (ate hardCount) -> aerobicos. */
    private List<TipoTreino> montarTipos(List<TipoTreino> prioridade, TipoTreino chave, int sessionCount, int hardCount) {
        List<TipoTreino> res = new ArrayList<>(sessionCount);
        if (chave != null) {
            res.add(chave);
        }
        int durasAdd = 0;
        for (TipoTreino t : prioridade) {
            if (res.size() >= sessionCount) break;
            if (t != chave && DURAS.contains(t) && durasAdd < hardCount) {
                res.add(t);
                durasAdd++;
            }
        }
        for (TipoTreino t : prioridade) {
            if (res.size() >= sessionCount) break;
            if (t != chave && !DURAS.contains(t)) {
                res.add(t);
            }
        }
        // preenchimento: primeiro FACIL, resto REGENERATIVO (nunca lacuna)
        boolean primeiroExtra = true;
        while (res.size() < sessionCount) {
            res.add(primeiroExtra ? TipoTreino.FACIL : TipoTreino.REGENERATIVO);
            primeiroExtra = false;
        }
        return res;
    }

    /** Fracao da carga (peso = fatorImpacto) em tipos duros; PROVA excluida da razao. */
    private double fracaoAlta(List<TipoTreino> tipos) {
        double total = 0;
        double alta = 0;
        for (TipoTreino t : tipos) {
            if (t == TipoTreino.PROVA) continue;
            double w = t.getFatorImpacto();
            total += w;
            if (DURAS.contains(t)) alta += w;
        }
        return total <= 0 ? 0 : alta / total;
    }

    private double distanciaDaFaixa(double fracao, PhaseSpec spec) {
        if (fracao < spec.faixaBaixa()) return spec.faixaBaixa() - fracao;
        if (fracao > spec.faixaAlta()) return fracao - spec.faixaAlta();
        return 0.0;
    }

    /** Reparte o targetTss (linear), reservando a PROVA primeiro; deriva e clampa duracoes. */
    private List<SessionSlot> repartir(List<TipoTreino> tiposEntrada, TipoTreino chave, CompositionRequest req) {
        List<TipoTreino> tipos = new ArrayList<>(tiposEntrada);

        // PROVA: TSS reservado do alvo primeiro (design 4b item 7).
        double provaTss = 0;
        boolean temProva = tipos.contains(TipoTreino.PROVA) && req.provaHoras() != null;
        if (temProva) {
            provaTss = TipoTreino.PROVA.getFatorImpacto() * TAXA_BASE * req.provaHoras();
        }
        double restante = Math.max(0, req.targetTss() - provaTss);

        // Se a prova domina o alvo, os demais slots viram REGENERATIVO (design 4b item 7).
        if (temProva && restante <= 0) {
            for (int i = 0; i < tipos.size(); i++) {
                if (tipos.get(i) != TipoTreino.PROVA) tipos.set(i, TipoTreino.REGENERATIVO);
            }
        }

        // Minimos vencem: se a soma dos minimos (nao-prova) excede o restante, dropa o de menor
        // prioridade (o ultimo da lista, preservando a chave no indice 0) ate caber.
        while (somaMinimosTss(tipos) > req.targetTss() && podeDropar(tipos, chave)) {
            tipos.remove(ultimoDroppavel(tipos, chave));
        }

        double totalPeso = 0;
        for (TipoTreino t : tipos) {
            if (t == TipoTreino.PROVA) continue;
            totalPeso += t.getFatorImpacto();
        }

        List<SessionSlot> slots = new ArrayList<>(tipos.size());
        for (TipoTreino t : tipos) {
            boolean ehChave = (chave != null && t == chave && slotChaveAindaNaoUsado(slots, chave))
                    || (t == TipoTreino.PROVA);
            double tss;
            int dur;
            if (t == TipoTreino.PROVA) {
                tss = provaTss;
                dur = clamp(t, arredondar(req.provaHoras() != null ? req.provaHoras() * 60 : 0), req.duracaoMaximaMinutos());
            } else {
                tss = totalPeso <= 0 ? 0 : restante * (t.getFatorImpacto() / totalPeso);
                double durBruta = tss <= 0 ? CLAMP.get(t)[0] : (tss / (t.getFatorImpacto() * TAXA_BASE)) * 60.0;
                dur = clamp(t, arredondar(durBruta), req.duracaoMaximaMinutos());
                // TSS efetivo do slot apos o clamp da duracao (coerencia dur<->tss).
                tss = t.getFatorImpacto() * TAXA_BASE * (dur / 60.0);
            }
            slots.add(new SessionSlot(null, t.name(), round1(tss), t.getZonaFcAlvo(), ehChave, dur));
        }
        return slots;
    }

    private boolean slotChaveAindaNaoUsado(List<SessionSlot> slots, TipoTreino chave) {
        return slots.stream().noneMatch(s -> s.chave() && chave.name().equals(s.sessionType()));
    }

    private double somaMinimosTss(List<TipoTreino> tipos) {
        double s = 0;
        for (TipoTreino t : tipos) {
            if (t == TipoTreino.PROVA) continue;
            s += t.getFatorImpacto() * TAXA_BASE * (CLAMP.get(t)[0] / 60.0);
        }
        return s;
    }

    private boolean podeDropar(List<TipoTreino> tipos, TipoTreino chave) {
        // pode dropar enquanto houver algum slot alem da chave
        long naoChave = tipos.stream().filter(t -> chave == null || t != chave || t == TipoTreino.PROVA).count();
        return naoChave > 0 && tipos.size() > 1;
    }

    private int ultimoDroppavel(List<TipoTreino> tipos, TipoTreino chave) {
        for (int i = tipos.size() - 1; i >= 0; i--) {
            TipoTreino t = tipos.get(i);
            boolean ehChave = chave != null && t == chave && i == 0;
            if (!ehChave && t != TipoTreino.PROVA) return i;
        }
        return tipos.size() - 1;
    }

    private int clamp(TipoTreino t, int dur, Integer duracaoMaximaMinutos) {
        int[] c = CLAMP.getOrDefault(t, new int[]{20, 120});
        int min = c[0];
        int max = c[1];
        if (duracaoMaximaMinutos != null && duracaoMaximaMinutos > 0) {
            max = Math.min(max, duracaoMaximaMinutos);
        }
        max = Math.max(min, max);
        return Math.max(min, Math.min(max, dur));
    }

    private int arredondar(double v) {
        return (int) Math.round(v);
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
