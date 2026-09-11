package br.com.menthoros.backend.domain.compliance;

import br.com.menthoros.backend.domain.planner.AthleteConstraints;
import br.com.menthoros.backend.domain.planner.ProvaSnapshot;
import br.com.menthoros.backend.domain.planner.TrainingPhase;
import br.com.menthoros.backend.domain.planner.WeekPlanSkeleton;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Compliance hipotetico do {@link WeekPlanSkeleton} contra o plano efetivamente gerado pelo
 * LLM, em dois estagios (design.md Decisao 4). Puro — sem excecao de dominio, sem Micrometer;
 * metricas ficam no caller (shadow, secao 7). Nesta change o checker so roda em shadow; o
 * wiring de retry/enforcement e escopo de {@code planner-engine-enforcement}.
 */
@Component
public class SkeletonComplianceChecker {

    private static final double TETO_LONGO_FRACAO = 0.40;
    // Teto de uma sessao intensa: fracao do targetTss OU um piso absoluto — o que for maior. O piso
    // (calibracao 2026-09-11) evita reprovar um intervalado real (~55-65 TSS) em semana de carga baixa,
    // onde 0.30 x targetTss ficaria abaixo do custo minimo de um intervalado estruturado.
    private static final double TETO_INTERVALADO_FRACAO = 0.40;
    private static final double TETO_INTERVALADO_PISO = 60.0;
    private static final long JANELA_PESADA_MIN_DIAS = 2;
    private static final long JANELA_PESADA_MAX_DIAS = 3;
    private static final List<String> TIPOS_INTENSOS = List.of("INTERVALADO", "TEMPO_RUN", "INTENSO");

    /**
     * Compliance sobre o plano do LLM antes da redistribuicao: fase, contagem de sessoes,
     * TSS total (+-10%), teto de longo, excesso de intensidade, sessao pesada 48-72h antes da
     * prova (na posicao gerada), constraints duras.
     */
    public List<PlannerViolation> checkPreRedistribution(GeneratedPlanSnapshot plano,
                                                           WeekPlanSkeleton skeleton,
                                                           ComplianceContext context) {
        List<PlannerViolation> violacoes = new ArrayList<>();
        checarFase(plano, skeleton, violacoes);
        checarSessionCount(plano, skeleton, violacoes);
        checarTssNaFaixa(plano, skeleton, violacoes);
        checarTetoDeLongo(plano, skeleton, violacoes);
        // EXCESSO_INTENSIDADE NAO entra no estagio 1 (calibracao 2026-09-11): e uma divergencia de
        // distribuicao (revisavel), nao um erro estrutural que o retro deva reprovar ate esgotar o
        // orcamento e devolver 422. Fica no estagio 2 (checkPost) -> soft -> FAILED + requiresCoachReview.
        checarSessaoPesadaPertoDaProva(plano, context, violacoes);
        checarConstraintsDuras(plano, context, violacoes);
        return violacoes;
    }

    /**
     * Compliance sobre os treinos ja redistribuidos: dias permitidos, sessao pesada perto da
     * prova apos o reposicionamento, taper/race-week preservados.
     */
    public List<PlannerViolation> checkPostRedistribution(GeneratedPlanSnapshot treinosRedistribuidos,
                                                            WeekPlanSkeleton skeleton,
                                                            ComplianceContext context) {
        List<PlannerViolation> violacoes = new ArrayList<>();
        checarDiasPermitidos(treinosRedistribuidos, context, violacoes);
        checarExcessoDeIntensidade(treinosRedistribuidos, skeleton, violacoes); // estagio 2 (soft), ver checkPre
        checarSessaoPesadaPertoDaProva(treinosRedistribuidos, context, violacoes);
        checarTaperPreservado(treinosRedistribuidos, skeleton, violacoes);
        return violacoes;
    }

    private void checarFase(GeneratedPlanSnapshot plano, WeekPlanSkeleton skeleton, List<PlannerViolation> violacoes) {
        if (skeleton.phase() != TrainingPhase.RECOVERY) {
            return;
        }
        boolean temSessaoIntensa = plano.sessoes().stream().anyMatch(s -> ehTipoIntenso(s.tipoTreino()));
        if (temSessaoIntensa) {
            violacoes.add(new PlannerViolation(PlannerViolationKey.FASE_DIVERGENTE,
                    "Sessao de intensidade incompativel com a fase RECOVERY"));
        }
    }

    private void checarSessionCount(GeneratedPlanSnapshot plano, WeekPlanSkeleton skeleton, List<PlannerViolation> violacoes) {
        if (plano.sessoes().isEmpty() && skeleton.loadTarget().targetTss() > 0) {
            violacoes.add(new PlannerViolation(PlannerViolationKey.SESSION_COUNT_DIVERGENTE,
                    "Plano gerado sem sessoes, mas alvo de carga semanal > 0"));
        }
    }

    private void checarTssNaFaixa(GeneratedPlanSnapshot plano, WeekPlanSkeleton skeleton, List<PlannerViolation> violacoes) {
        int tssTotal = plano.tssTotal();
        if (tssTotal < skeleton.loadTarget().minTss() || tssTotal > skeleton.loadTarget().maxTss()) {
            violacoes.add(new PlannerViolation(PlannerViolationKey.TSS_FORA_DA_FAIXA,
                    "TSS total gerado (" + tssTotal + ") fora da faixa ["
                            + skeleton.loadTarget().minTss() + ", " + skeleton.loadTarget().maxTss() + "]"));
        }
    }

    private void checarTetoDeLongo(GeneratedPlanSnapshot plano, WeekPlanSkeleton skeleton, List<PlannerViolation> violacoes) {
        double teto = skeleton.loadTarget().targetTss() * TETO_LONGO_FRACAO;
        plano.sessoes().stream()
                .filter(s -> "LONGO".equalsIgnoreCase(s.tipoTreino()))
                .filter(s -> s.tssPlanejado() != null && s.tssPlanejado() > teto)
                .forEach(s -> violacoes.add(new PlannerViolation(PlannerViolationKey.LONGO_ACIMA_TETO,
                        "Longo em " + s.data() + " (" + s.tssPlanejado() + " TSS) acima do teto de "
                                + Math.round(teto) + " TSS")));
    }

    private void checarExcessoDeIntensidade(GeneratedPlanSnapshot plano, WeekPlanSkeleton skeleton, List<PlannerViolation> violacoes) {
        double teto = Math.max(skeleton.loadTarget().targetTss() * TETO_INTERVALADO_FRACAO, TETO_INTERVALADO_PISO);
        plano.sessoes().stream()
                .filter(s -> ehTipoIntenso(s.tipoTreino()))
                .filter(s -> s.tssPlanejado() != null && s.tssPlanejado() > teto)
                .forEach(s -> violacoes.add(new PlannerViolation(PlannerViolationKey.EXCESSO_INTENSIDADE,
                        "Sessao intensa em " + s.data() + " (" + s.tssPlanejado() + " TSS) acima do teto de "
                                + Math.round(teto) + " TSS")));
    }

    private void checarSessaoPesadaPertoDaProva(GeneratedPlanSnapshot plano, ComplianceContext context, List<PlannerViolation> violacoes) {
        if (context.provaDeterminante().isEmpty()) {
            return;
        }
        LocalDate dataProva = context.provaDeterminante().get().dataProva();
        double tetoPesada = 0.0; // qualquer sessao intensa/longa na janela critica ja e um risco

        plano.sessoes().stream()
                .filter(s -> ehTipoIntenso(s.tipoTreino()) || "LONGO".equalsIgnoreCase(s.tipoTreino()))
                .filter(s -> s.tssPlanejado() != null && s.tssPlanejado() > tetoPesada)
                .filter(s -> estaNaJanelaCriticaPreProva(s.data(), dataProva))
                .forEach(s -> violacoes.add(new PlannerViolation(PlannerViolationKey.SESSAO_PESADA_PROXIMA_PROVA,
                        "Sessao pesada (" + s.tipoTreino() + ") em " + s.data()
                                + ", 48-72h antes da prova em " + dataProva)));
    }

    private void checarConstraintsDuras(GeneratedPlanSnapshot plano, ComplianceContext context, List<PlannerViolation> violacoes) {
        List<java.time.DayOfWeek> diasDisponiveis = context.constraints().diasDisponiveis();
        if (diasDisponiveis == null) {
            return;
        }
        plano.sessoes().stream()
                .filter(s -> !diasDisponiveis.contains(s.data().getDayOfWeek()))
                .forEach(s -> violacoes.add(new PlannerViolation(PlannerViolationKey.CONSTRAINT_DURA_VIOLADA,
                        "Sessao em " + s.data() + " (" + s.data().getDayOfWeek() + ") fora dos dias disponiveis do atleta")));
    }

    private void checarDiasPermitidos(GeneratedPlanSnapshot treinosRedistribuidos, ComplianceContext context, List<PlannerViolation> violacoes) {
        List<java.time.DayOfWeek> diasDisponiveis = context.constraints().diasDisponiveis();
        if (diasDisponiveis == null) {
            return;
        }
        treinosRedistribuidos.sessoes().stream()
                .filter(s -> !diasDisponiveis.contains(s.data().getDayOfWeek()))
                .forEach(s -> violacoes.add(new PlannerViolation(PlannerViolationKey.DIA_INDISPONIVEL,
                        "Treino redistribuido para " + s.data() + " (" + s.data().getDayOfWeek()
                                + "), fora dos dias disponiveis do atleta")));
    }

    private void checarTaperPreservado(GeneratedPlanSnapshot treinosRedistribuidos, WeekPlanSkeleton skeleton, List<PlannerViolation> violacoes) {
        if (skeleton.phase() != TrainingPhase.TAPER && skeleton.phase() != TrainingPhase.RACE_WEEK) {
            return;
        }
        int tssTotal = treinosRedistribuidos.tssTotal();
        if (tssTotal > skeleton.loadTarget().maxTss()) {
            violacoes.add(new PlannerViolation(PlannerViolationKey.TAPER_VIOLADO,
                    "TSS redistribuido (" + tssTotal + ") acima do teto de taper/race-week ("
                            + skeleton.loadTarget().maxTss() + ")"));
        }
    }

    private boolean estaNaJanelaCriticaPreProva(LocalDate dataSessao, LocalDate dataProva) {
        long diasAntesDaProva = ChronoUnit.DAYS.between(dataSessao, dataProva);
        return diasAntesDaProva >= JANELA_PESADA_MIN_DIAS && diasAntesDaProva <= JANELA_PESADA_MAX_DIAS;
    }

    private boolean ehTipoIntenso(String tipoTreino) {
        return tipoTreino != null && TIPOS_INTENSOS.contains(tipoTreino.toUpperCase());
    }
}
