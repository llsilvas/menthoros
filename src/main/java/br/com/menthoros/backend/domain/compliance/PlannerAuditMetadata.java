package br.com.menthoros.backend.domain.compliance;

import br.com.menthoros.backend.domain.planner.TrainingPhase;

import java.util.List;

/**
 * Resumo compacto do {@code WeekPlanSkeleton} persistido em
 * {@code tb_plano_semanal.planner_metadata_json} — sem prompt nem dado sensivel
 * (design.md Decisao 9).
 *
 * <p>planner-engine-enforcement §7.1 (Codex blocker 3): alem da contagem, guarda a lista
 * estruturada de {@link PlannerViolation} (key + mensagem) para a superficie de review do coach
 * exibir os motivos reais, nao so o total. Mesma coluna, sem migration.
 */
public record PlannerAuditMetadata(
        TrainingPhase phase,
        boolean requiresCoachReview,
        String coachReviewReason,
        PlannerComplianceStatus complianceStatus,
        int violationCount,
        List<PlannerViolation> violations,
        String plannerVersion
) {
}
