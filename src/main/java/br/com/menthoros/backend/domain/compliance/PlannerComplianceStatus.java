package br.com.menthoros.backend.domain.compliance;

/**
 * Status de compliance do {@code WeekPlanSkeleton}, persistido em
 * {@code tb_plano_semanal.planner_compliance_status} (design.md Decisao 9).
 *
 * <p><b>Shadow (parte 1, deterministic-planner-engine):</b> {@link #NOT_EVALUATED},
 * {@link #COMPLIANT}, {@link #VIOLATIONS_DETECTED} — auditoria em paralelo, sem enforcement.</p>
 *
 * <p><b>Enforcement (planner-engine-enforcement):</b> o ciclo completo de uma requisicao com
 * {@code planner-engine.enabled=true}. O status final = pior resultado entre os estagios
 * (ver design Decisao 3 — matriz fail-open + precedencia das invariantes obrigatorias):</p>
 * <ul>
 *   <li>{@link #PASSED} — compliance verde na 1a geracao;</li>
 *   <li>{@link #RETRIED_PASSED} — passou apos 1 retry (dentro do orcamento unico);</li>
 *   <li>{@link #FALLBACK} — planner falhou <b>antes</b> do LLM e o pipeline legado gerou (fail-open),
 *       1a e unica geracao dentro do orcamento;</li>
 *   <li>{@link #FAILED} — violacao <b>soft</b> no estagio 2 com fail-open: plano persistido com
 *       {@code requiresCoachReview=true}. Violacao <b>obrigatoria (hard)</b> nunca persiste — falha
 *       fechado (422), sem status persistido.</li>
 * </ul>
 */
public enum PlannerComplianceStatus {
    NOT_EVALUATED,
    COMPLIANT,
    VIOLATIONS_DETECTED,
    PASSED,
    RETRIED_PASSED,
    FALLBACK,
    FAILED
}
