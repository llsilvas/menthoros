package br.com.menthoros.backend.domain.planner;

import br.com.menthoros.backend.enums.DiaSemana;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Servico de dominio puro (design.md Decisao 1): sem IO, sem JPA, sem LLM, nunca chama
 * {@code LocalDate.now()}. Orquestra periodizacao, alvo de carga, taper, risco de lesao,
 * politica de lesao e validacao de constraints num unico {@link WeekPlanSkeleton}.
 *
 * <p>Hierarquia P0 (Decisao 6): (1) constraints duras e lesao ativa, (2) risco fisiologico,
 * (3) prova na semana / taper / pos-prova, (4) {@code DecisaoProgressao}, (5) preferencias do
 * atleta/coach. O override de fase por lesao (passo 1) tem precedencia sobre a fase resolvida
 * pela periodizacao (passo 3).
 *
 * <p>Registrado no Spring ({@code @Service}) mas puro — sem {@code @Transactional}, sem
 * dependencia de infraestrutura; o registro existe apenas para o
 * {@code PlannerShadowService} injetar via construtor.
 */
@Service
public class PlannerEngine {

    private final PeriodizationPlanner periodizationPlanner;
    private final LoadTargetResolver loadTargetResolver;
    private final TaperStrategy taperStrategy;
    private final InjuryRiskEvaluator injuryRiskEvaluator;
    private final InjuryPolicyResolver injuryPolicyResolver;
    private final ConstraintValidator constraintValidator;
    // Puros e sem dependencias — instanciados aqui para nao mudar a assinatura do construtor
    // (injetado pelo PlannerShadowService e construido em varios testes).
    private final SessionCompositionResolver compositionResolver = new SessionCompositionResolver();
    private final SessionDayAllocator dayAllocator = new SessionDayAllocator();

    public PlannerEngine(PeriodizationPlanner periodizationPlanner,
                          LoadTargetResolver loadTargetResolver,
                          TaperStrategy taperStrategy,
                          InjuryRiskEvaluator injuryRiskEvaluator,
                          InjuryPolicyResolver injuryPolicyResolver,
                          ConstraintValidator constraintValidator) {
        this.periodizationPlanner = periodizationPlanner;
        this.loadTargetResolver = loadTargetResolver;
        this.taperStrategy = taperStrategy;
        this.injuryRiskEvaluator = injuryRiskEvaluator;
        this.injuryPolicyResolver = injuryPolicyResolver;
        this.constraintValidator = constraintValidator;
    }

    public WeekPlanSkeleton planWeek(PlannerInputSnapshot snapshot) {
        PeriodizationResult periodizacao = periodizationPlanner.resolvePhase(snapshot.provas(), snapshot.referenceDate());

        InjuryPolicyResult injuryPolicy = injuryPolicyResolver.resolve(
                snapshot.athlete(), snapshot.referenceDate(), snapshot.injuryRecentWindowDays());

        // Passo 1 da hierarquia P0: lesao ativa/recente tem precedencia sobre a fase da periodizacao.
        TrainingPhase fase = injuryPolicy.phaseOverride().orElse(periodizacao.phase());

        InjuryRiskAssessment risco = injuryRiskEvaluator.assess(
                snapshot.progressaoHistorico(), snapshot.historico(), snapshot.referenceDate());

        WeeklyLoadTarget loadTarget = loadTargetResolver.resolve(fase, snapshot.decisaoProgressao(), snapshot.progressaoHistorico());
        loadTarget = aplicarTaperSeAplicavel(loadTarget, fase, periodizacao, snapshot.referenceDate());

        AthleteConstraints constraints = resolverConstraints(snapshot);

        // Composicao prescritiva (planner-engine-enforcement, Decisao 4/4b + ADR-0011): compoe os
        // SessionSlot por fase e aloca os dias. Roda sempre (o skeleton alimenta shadow e, com
        // enabled=true, o prompt/compliance).
        List<SessionSlot> sessoes = comporSessoes(snapshot, fase, loadTarget, constraints, periodizacao);
        ConstraintValidationResult constraintResult = constraintValidator.validate(constraints, sessoes);

        boolean requiresCoachReview = injuryPolicy.requiresCoachReview() || risco.requiresCoachReview();
        // Hierarquia P0: motivo de lesao (passo 1) tem precedencia sobre motivo fisiologico (passo 2).
        String coachReviewReason = injuryPolicy.requiresCoachReview() ? injuryPolicy.reason()
                : risco.requiresCoachReview() ? risco.reason()
                : null;

        String plannerScope = resolverPlannerScope(snapshot.athlete().modalidade());

        return new WeekPlanSkeleton(
                fase,
                loadTarget,
                sessoes,
                risco,
                constraintResult,
                requiresCoachReview,
                coachReviewReason,
                snapshot.referenceDate(),
                plannerScope,
                periodizacao.provaDeterminante());
    }

    /** Compoe os SessionSlot por fase (ADR-0011) e aloca os dias. Puro/deterministico. */
    private List<SessionSlot> comporSessoes(PlannerInputSnapshot snapshot,
                                            TrainingPhase fase,
                                            WeeklyLoadTarget loadTarget,
                                            AthleteConstraints constraints,
                                            PeriodizationResult periodizacao) {
        List<DayOfWeek> diasDisponiveis = constraints.diasDisponiveis() != null ? constraints.diasDisponiveis() : List.of();
        int longoes21d = snapshot.progressaoHistorico() != null ? snapshot.progressaoHistorico().longoesRealizados21d() : 0;

        // PROVA na semana (RACE_WEEK): estima horas da prova pelo pace default por distancia (Decisao 4b);
        // o dia da prova e o real dela (ancora fixa), fora dos dias disponiveis se preciso.
        Double provaHoras = null;
        DayOfWeek provaDay = null;
        if (fase == TrainingPhase.RACE_WEEK && periodizacao.provaDeterminante().isPresent()) {
            ProvaSnapshot prova = periodizacao.provaDeterminante().get();
            provaDay = prova.dataProva() != null ? prova.dataProva().getDayOfWeek() : null;
            if (prova.distanciaKm() != null && prova.distanciaKm() > 0) {
                provaHoras = prova.distanciaKm() * paceDefaultMinPorKm(prova.distanciaKm()) / 60.0;
            }
        }

        var req = new SessionCompositionResolver.CompositionRequest(
                fase, loadTarget.targetTss(), diasDisponiveis.size(),
                constraints.maxSessoesPorSemana(), constraints.duracaoMaximaMinutos(),
                longoes21d, provaHoras);
        List<SessionSlot> composed = compositionResolver.compose(req);
        // preferredLongDay nao esta no snapshot do planner (fica na entidade Atleta) — fallback = ultimo
        // dia disponivel. Trazer o campo ao snapshot e follow-up.
        return dayAllocator.allocate(composed, diasDisponiveis, null, provaDay);
    }

    /** Pace default (min/km) por faixa de distancia, quando o pace do atleta nao esta no snapshot (Decisao 4b). */
    private double paceDefaultMinPorKm(double distanciaKm) {
        if (distanciaKm <= 5) return 5.0;
        if (distanciaKm <= 10) return 5.25;
        if (distanciaKm <= 21) return 5.5;
        if (distanciaKm <= 42) return 6.0;
        return 6.5;
    }

    private WeeklyLoadTarget aplicarTaperSeAplicavel(WeeklyLoadTarget loadTarget,
                                                       TrainingPhase fase,
                                                       PeriodizationResult periodizacao,
                                                       java.time.LocalDate referenceDate) {
        if (fase != TrainingPhase.TAPER && fase != TrainingPhase.RACE_WEEK) {
            return loadTarget;
        }
        Optional<ProvaSnapshot> provaDeterminante = periodizacao.provaDeterminante();
        if (provaDeterminante.isEmpty() || provaDeterminante.get().distanciaKm() == null) {
            return loadTarget;
        }
        ProvaSnapshot prova = provaDeterminante.get();
        long diasParaProva = ChronoUnit.DAYS.between(referenceDate, prova.dataProva());
        if (!taperStrategy.estaNaJanelaDeTaper(prova.distanciaKm(), diasParaProva)) {
            return loadTarget;
        }
        return taperStrategy.aplicar(loadTarget, diasParaProva);
    }

    private AthleteConstraints resolverConstraints(PlannerInputSnapshot snapshot) {
        return snapshot.onboardingContext()
                .map(OnboardingContext::constraints)
                .orElseGet(() -> new AthleteConstraints(
                        mapDiasDisponiveis(snapshot.athlete().diasDisponiveis()),
                        null,
                        null,
                        List.of()));
    }

    private List<DayOfWeek> mapDiasDisponiveis(List<DiaSemana> diasDisponiveis) {
        if (diasDisponiveis == null) {
            return List.of();
        }
        return diasDisponiveis.stream().map(this::toDayOfWeek).toList();
    }

    private DayOfWeek toDayOfWeek(DiaSemana dia) {
        return switch (dia) {
            case DOMINGO -> DayOfWeek.SUNDAY;
            case SEGUNDA -> DayOfWeek.MONDAY;
            case TERCA -> DayOfWeek.TUESDAY;
            case QUARTA -> DayOfWeek.WEDNESDAY;
            case QUINTA -> DayOfWeek.THURSDAY;
            case SEXTA -> DayOfWeek.FRIDAY;
            case SABADO -> DayOfWeek.SATURDAY;
        };
    }

    private String resolverPlannerScope(String modalidade) {
        if (modalidade == null) {
            return null;
        }
        boolean running = modalidade.equalsIgnoreCase("RUNNING") || modalidade.equalsIgnoreCase("CORRIDA");
        return running ? null : "RUNNING_FIRST";
    }
}
