package br.com.menthoros.backend.services.helper;

import br.com.menthoros.backend.domain.compliance.ComplianceContext;
import br.com.menthoros.backend.domain.compliance.GeneratedPlanSnapshot;
import br.com.menthoros.backend.domain.compliance.GeneratedSessionSnapshot;
import br.com.menthoros.backend.domain.compliance.PlannerAuditMetadata;
import br.com.menthoros.backend.domain.compliance.PlannerComplianceStatus;
import br.com.menthoros.backend.domain.compliance.PlannerVersion;
import br.com.menthoros.backend.domain.compliance.PlannerViolation;
import br.com.menthoros.backend.domain.compliance.SkeletonComplianceChecker;
import br.com.menthoros.backend.domain.planner.AthleteConstraints;
import br.com.menthoros.backend.domain.planner.AthleteSnapshot;
import br.com.menthoros.backend.domain.planner.PlannerEngine;
import br.com.menthoros.backend.domain.planner.PlannerInputSnapshot;
import br.com.menthoros.backend.domain.planner.ProvaSnapshot;
import br.com.menthoros.backend.domain.planner.TreinoRealizadoSnapshot;
import br.com.menthoros.backend.domain.planner.WeekPlanSkeleton;
import br.com.menthoros.backend.dto.DecisaoProgressao;
import br.com.menthoros.backend.dto.ProgressaoHistoricoResumo;
import br.com.menthoros.backend.dto.input.DadosPlanoDto;
import br.com.menthoros.backend.dto.llm.PlanoSemanalLlmDto;
import br.com.menthoros.backend.dto.llm.TreinoPlanejadoLlmDto;
import br.com.menthoros.backend.dto.output.TreinoRealizadoOutputDto;
import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.entity.PlanoSemanal;
import br.com.menthoros.backend.entity.Prova;
import br.com.menthoros.backend.entity.TreinoPlanejado;
import br.com.menthoros.backend.enums.DiaSemana;
import br.com.menthoros.backend.enums.DistanciaProva;
import br.com.menthoros.backend.enums.ProvaStatus;
import br.com.menthoros.backend.services.ProgressaoTreinoService;
import br.com.menthoros.backend.services.prompt.PeriodizacaoPromptFormatter;
import br.com.menthoros.backend.util.Utils;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Roda o {@link PlannerEngine} em shadow apos a geracao legada retornar (design.md Decisao 10)
 * — nunca dentro de {@code IaServiceImpl}, que nao sabe que o planner existe. Persiste
 * auditoria em {@link PlanoSemanal} (in-memory; quem chama salva) e emite metricas
 * Micrometer. Nunca lanca excecao (CA11): qualquer falha e engolida, logada e contabilizada
 * em {@code planner.shadow.error.count} — a geracao do plano nunca falha por causa do shadow.
 */
@Slf4j
@Component
public class PlannerShadowService {

    private final PlannerEngine plannerEngine;
    private final SkeletonComplianceChecker complianceChecker;
    private final ProgressaoTreinoService progressaoTreinoService;
    private final PeriodizacaoPromptFormatter periodizacaoPromptFormatter;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final boolean shadowEnabled;
    private final int injuryRecentWindowDays;

    public PlannerShadowService(PlannerEngine plannerEngine,
                                 SkeletonComplianceChecker complianceChecker,
                                 ProgressaoTreinoService progressaoTreinoService,
                                 PeriodizacaoPromptFormatter periodizacaoPromptFormatter,
                                 MeterRegistry meterRegistry,
                                 ObjectMapper objectMapper,
                                 @Value("${planner-engine.shadow:false}") boolean shadowEnabled,
                                 @Value("${planner-engine.injury.recent-window-days:30}") int injuryRecentWindowDays) {
        this.plannerEngine = plannerEngine;
        this.complianceChecker = complianceChecker;
        this.progressaoTreinoService = progressaoTreinoService;
        this.periodizacaoPromptFormatter = periodizacaoPromptFormatter;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        this.shadowEnabled = shadowEnabled;
        this.injuryRecentWindowDays = injuryRecentWindowDays;
    }

    /**
     * Idempotent: NAO — cada chamada roda o motor e persiste um novo snapshot de auditoria.
     * Side Effects: mutacao in-memory dos campos {@code planner_*} de {@code plano} (o caller
     * salva); metricas Micrometer. Sem I/O externo alem da leitura read-only de
     * {@code ProgressaoTreinoService}.
     * Tenant-aware: NAO — opera inteiramente sobre objetos ja resolvidos pelo caller.
     *
     * @return o {@code WeekPlanSkeleton} calculado (para o caller decidir auto-approve do
     *         onboarding, athlete-onboarding-baseline CA5) — {@code Optional.empty()} quando o
     *         shadow esta desligado ou quando o calculo falha (CA11: a falha e engolida e
     *         contabilizada, nunca propaga; o caller trata a ausencia como "sem informacao de
     *         risco", nao auto-aprovando por seguranca).
     */
    public Optional<WeekPlanSkeleton> aplicarShadow(PlanoSemanal plano,
                               PlanoSemanalLlmDto planoGeradoPeloLlm,
                               DadosPlanoDto dadosPlano,
                               DecisaoProgressao decisaoProgressao,
                               LocalDate semanaInicio,
                               boolean batch) {
        return aplicarShadow(plano, planoGeradoPeloLlm, dadosPlano, decisaoProgressao, semanaInicio, batch, Optional.empty());
    }

    /**
     * Sobrecarga que aceita o {@code OnboardingContext} (athlete-onboarding-baseline,
     * design.md Decisao 2) para popular {@code PlannerInputSnapshot.onboardingContext} —
     * hoje sempre {@code Optional.empty()} na sobrecarga acima. Mesmas garantias de
     * isolamento de falha (CA11).
     */
    public Optional<WeekPlanSkeleton> aplicarShadow(PlanoSemanal plano,
                               PlanoSemanalLlmDto planoGeradoPeloLlm,
                               DadosPlanoDto dadosPlano,
                               DecisaoProgressao decisaoProgressao,
                               LocalDate semanaInicio,
                               boolean batch,
                               Optional<br.com.menthoros.backend.domain.planner.OnboardingContext> onboardingContext) {
        plano.setPlannerEnabled(false); // shadow nunca habilita de fato nesta change
        if (!shadowEnabled) {
            return Optional.empty();
        }
        try {
            return Optional.of(executar(plano, planoGeradoPeloLlm, dadosPlano, decisaoProgressao, semanaInicio, batch, onboardingContext));
        } catch (Exception e) {
            Atleta atleta = dadosPlano.atleta();
            log.error("[planner-shadow] erro ao processar atleta {}: {}", atleta != null ? atleta.getId() : null, e.getMessage(), e);
            meterRegistry.counter("planner.shadow.error.count", "reason", e.getClass().getSimpleName()).increment();
            return Optional.empty();
        }
    }

    private WeekPlanSkeleton executar(PlanoSemanal plano,
                           PlanoSemanalLlmDto planoGeradoPeloLlm,
                           DadosPlanoDto dadosPlano,
                           DecisaoProgressao decisaoProgressao,
                           LocalDate semanaInicio,
                           boolean batch,
                           Optional<br.com.menthoros.backend.domain.planner.OnboardingContext> onboardingContext) throws Exception {
        Atleta atleta = dadosPlano.atleta();
        PlannerInputSnapshot snapshot = mapToSnapshot(atleta, dadosPlano, decisaoProgressao, semanaInicio, onboardingContext);

        WeekPlanSkeleton skeleton = plannerEngine.planWeek(snapshot);

        ComplianceContext context = new ComplianceContext(
                skeleton.provaDeterminante(),
                resolverConstraints(atleta),
                semanaInicio);

        GeneratedPlanSnapshot planoGeradoSnapshot = mapPlanoGerado(planoGeradoPeloLlm, semanaInicio);
        List<PlannerViolation> violacoesPre = complianceChecker.checkPreRedistribution(planoGeradoSnapshot, skeleton, context);

        GeneratedPlanSnapshot redistribuidoSnapshot = mapTreinosRedistribuidos(plano.getTreinosPlanejados());
        List<PlannerViolation> violacoesPost = complianceChecker.checkPostRedistribution(redistribuidoSnapshot, skeleton, context);

        List<PlannerViolation> todasViolacoes = new ArrayList<>(violacoesPre.size() + violacoesPost.size());
        todasViolacoes.addAll(violacoesPre);
        todasViolacoes.addAll(violacoesPost);

        PlannerComplianceStatus status = todasViolacoes.isEmpty()
                ? PlannerComplianceStatus.COMPLIANT
                : PlannerComplianceStatus.VIOLATIONS_DETECTED;

        persistirAuditoria(plano, skeleton, status, todasViolacoes.size());
        registrarMetricas(skeleton, status, violacoesPre, violacoesPost, batch);
        registrarDivergenciaDeFase(skeleton, atleta, semanaInicio);

        return skeleton;
    }

    /**
     * Computa o {@link WeekPlanSkeleton} para uso PRE-prompt (planner-engine-enforcement secao 3),
     * reusando o mesmo mapeamento de snapshot do shadow — SEM os efeitos de auditoria/persistencia/
     * metricas do {@link #executar}. Somente leitura.
     *
     * <p>Idempotent: YES (puro/leitura). Side Effects: NONE. Tenant-aware: recebe o {@code DadosPlanoDto}
     * ja resolvido pelo chamador. Nao engole excecao — o chamador aplica o fail-open (design Decisao 3).
     */
    public WeekPlanSkeleton computarSkeleton(DadosPlanoDto dadosPlano,
                                             DecisaoProgressao decisaoProgressao,
                                             LocalDate semanaInicio,
                                             Optional<br.com.menthoros.backend.domain.planner.OnboardingContext> onboardingContext) {
        PlannerInputSnapshot snapshot = mapToSnapshot(dadosPlano.atleta(), dadosPlano, decisaoProgressao, semanaInicio, onboardingContext);
        return plannerEngine.planWeek(snapshot);
    }

    // --- Mapeamento entity -> record (anti-corruption layer, design.md Decisao 17) ---

    private PlannerInputSnapshot mapToSnapshot(Atleta atleta,
                                                DadosPlanoDto dadosPlano,
                                                DecisaoProgressao decisaoProgressao,
                                                LocalDate semanaInicio,
                                                Optional<br.com.menthoros.backend.domain.planner.OnboardingContext> onboardingContext) {
        AthleteSnapshot athleteSnapshot = new AthleteSnapshot(
                atleta.getId(),
                atleta.getNivelExperiencia(),
                Boolean.TRUE.equals(atleta.getTemLesao()),
                atleta.getDescricaoLesao(),
                atleta.getDataUltimaLesao(),
                atleta.getDiasDisponiveis(),
                null); // modalidade reservada — Atleta ainda nao tem esse campo

        ProgressaoHistoricoResumo historico = progressaoTreinoService.calcularHistorico(atleta.getId());

        List<ProvaSnapshot> provas = atleta.getProvas() == null ? List.of() : atleta.getProvas().stream()
                .filter(p -> p.getDataProva() != null) // dado incompleto/legado — nao entra no calendario do planner
                .map(this::mapProva)
                .toList();

        List<TreinoRealizadoSnapshot> historicoDiario = dadosPlano.ultimosTreinos() == null ? List.of() : dadosPlano.ultimosTreinos().stream()
                .map(t -> new TreinoRealizadoSnapshot(t.dataTreino(), t.tssCalculado()))
                .toList();

        return new PlannerInputSnapshot(
                athleteSnapshot, decisaoProgressao, historico, provas, historicoDiario,
                onboardingContext, semanaInicio, injuryRecentWindowDays);
    }

    private ProvaSnapshot mapProva(Prova prova) {
        return new ProvaSnapshot(
                prova.getDataProva(),
                resolverDistanciaKm(prova),
                prova.isProvaAlvo(),
                prova.getStatusProva() == ProvaStatus.CANCELADA);
    }

    private Double resolverDistanciaKm(Prova prova) {
        if (prova.getDistanciaKm() != null) {
            return prova.getDistanciaKm().doubleValue();
        }
        DistanciaProva distancia = prova.getDistancia();
        if (distancia == null) {
            return null;
        }
        return switch (distancia) {
            case KM_5 -> 5.0;
            case KM_10 -> 10.0;
            case KM_21 -> 21.0975;
            case KM_42 -> 42.195;
            case CUSTOMIZADA -> prova.getDistanciaKm() != null ? prova.getDistanciaKm().doubleValue() : null;
        };
    }

    private AthleteConstraints resolverConstraints(Atleta atleta) {
        List<java.time.DayOfWeek> diasDisponiveis = atleta.getDiasDisponiveis() == null
                ? List.of()
                : atleta.getDiasDisponiveis().stream().map(Utils::converterParaDayOfWeek).toList();
        return new AthleteConstraints(diasDisponiveis, null, null, List.of());
    }

    private GeneratedPlanSnapshot mapPlanoGerado(PlanoSemanalLlmDto planoDto, LocalDate semanaInicio) {
        if (planoDto.treinosPlanejados() == null) {
            return new GeneratedPlanSnapshot(List.of());
        }
        List<GeneratedSessionSnapshot> sessoes = planoDto.treinosPlanejados().stream()
                .map(t -> mapTreinoLlm(t, semanaInicio))
                .toList();
        return new GeneratedPlanSnapshot(sessoes);
    }

    private GeneratedSessionSnapshot mapTreinoLlm(TreinoPlanejadoLlmDto dto, LocalDate semanaInicio) {
        LocalDate data;
        try {
            DiaSemana diaSemana = DiaSemana.valueOf(dto.diaSemana());
            data = semanaInicio.with(TemporalAdjusters.nextOrSame(Utils.converterParaDayOfWeek(diaSemana)));
        } catch (IllegalArgumentException e) {
            log.warn("[planner-shadow] diaSemana invalido do LLM: '{}' — usando semanaInicio como fallback", dto.diaSemana());
            data = semanaInicio; // dia invalido/inesperado do LLM — nao trava o shadow
        }
        return new GeneratedSessionSnapshot(data, dto.tipoTreino(), dto.tssPlanejado(), null);
    }

    private GeneratedPlanSnapshot mapTreinosRedistribuidos(List<TreinoPlanejado> treinos) {
        if (treinos == null) {
            return new GeneratedPlanSnapshot(List.of());
        }
        List<GeneratedSessionSnapshot> sessoes = treinos.stream()
                .map(t -> new GeneratedSessionSnapshot(
                        t.getDataTreino(),
                        t.getTipoTreino() != null ? t.getTipoTreino().name() : null,
                        t.getTssPlanejado(),
                        t.getZonaAlvo()))
                .toList();
        return new GeneratedPlanSnapshot(sessoes);
    }

    // --- Auditoria e metricas ---

    private void persistirAuditoria(PlanoSemanal plano, WeekPlanSkeleton skeleton,
                                     PlannerComplianceStatus status, int violationCount) throws Exception {
        plano.setPlannerVersion(PlannerVersion.CURRENT);
        plano.setPlannerPhase(skeleton.phase().name());
        plano.setPlannerRequiresCoachReview(skeleton.requiresCoachReview());
        plano.setPlannerComplianceStatus(status.name());
        plano.setPlannerSkeletonHash(hashSkeleton(skeleton));

        PlannerAuditMetadata metadata = new PlannerAuditMetadata(
                skeleton.phase(), skeleton.requiresCoachReview(), skeleton.coachReviewReason(),
                status, violationCount, PlannerVersion.CURRENT);
        plano.setPlannerMetadataJson(objectMapper.writeValueAsString(metadata));
    }

    private void registrarMetricas(WeekPlanSkeleton skeleton, PlannerComplianceStatus status,
                                    List<PlannerViolation> violacoesPre, List<PlannerViolation> violacoesPost,
                                    boolean batch) {
        String batchTag = String.valueOf(batch);
        meterRegistry.counter("planner.generated.count",
                        "phase", skeleton.phase().name(), "plannerVersion", PlannerVersion.CURRENT, "batch", batchTag)
                .increment();

        if (skeleton.requiresCoachReview()) {
            meterRegistry.counter("planner.requires_coach_review.count",
                            "reason", skeleton.coachReviewReason() != null ? skeleton.coachReviewReason() : "OUTRO",
                            "phase", skeleton.phase().name())
                    .increment();
        }

        violacoesPre.forEach(v -> meterRegistry.counter("planner.compliance.hypothetical_failure.count",
                        "reason", v.key().name(), "phase", skeleton.phase().name(), "stage", "PRE")
                .increment());
        violacoesPost.forEach(v -> meterRegistry.counter("planner.compliance.hypothetical_failure.count",
                        "reason", v.key().name(), "phase", skeleton.phase().name(), "stage", "POST")
                .increment());
    }

    /**
     * Compara a fase resolvida pelo planner com a do {@code PeriodizacaoPromptFormatter} legado
     * (CA16, design.md Decisao 12) — instrumento de calibracao, nao corrige nenhum dos dois
     * lados. Selecao de prova replicada de forma minima (so por data, sem desempate por
     * distancia) para espelhar o comportamento real do formatter legado.
     */
    private void registrarDivergenciaDeFase(WeekPlanSkeleton skeleton, Atleta atleta, LocalDate semanaInicio) {
        if (atleta.getProvas() == null) {
            return;
        }
        Optional<Prova> provaLegado = atleta.getProvas().stream()
                .filter(p -> p.getStatusProva() != ProvaStatus.CANCELADA)
                .filter(p -> p.getDataProva() != null && !p.getDataProva().isBefore(semanaInicio))
                .filter(Prova::isProvaAlvo)
                .findFirst()
                .or(() -> atleta.getProvas().stream()
                        .filter(p -> p.getStatusProva() != ProvaStatus.CANCELADA)
                        .filter(p -> p.getDataProva() != null && !p.getDataProva().isBefore(semanaInicio))
                        .min(Comparator.comparing(Prova::getDataProva)));

        if (provaLegado.isEmpty()) {
            return;
        }

        int diasFaltando = (int) ChronoUnit.DAYS.between(semanaInicio, provaLegado.get().getDataProva());
        String faseLegadoRaw = periodizacaoPromptFormatter.determinarFasePreparacao(diasFaltando);
        String faseLegadoNormalizada = normalizarFaseLegado(faseLegadoRaw);

        if (!skeleton.phase().name().equals(faseLegadoNormalizada)) {
            meterRegistry.counter("planner.phase.divergence.count",
                            "plannerPhase", skeleton.phase().name(), "formatterPhase", faseLegadoNormalizada)
                    .increment();
        }
    }

    private String normalizarFaseLegado(String faseLegadoRaw) {
        if (faseLegadoRaw == null) {
            return null;
        }
        return switch (faseLegadoRaw) {
            case "BASE" -> "BASE";
            case "BUILD" -> "BUILD";
            case "ESPECÍFICO" -> "PEAK";
            case "TAPER" -> "TAPER";
            case "SEMANA DA PROVA" -> "RACE_WEEK";
            case "PÓS-PROVA" -> "POST_RACE";
            default -> faseLegadoRaw;
        };
    }

    private String hashSkeleton(WeekPlanSkeleton skeleton) throws NoSuchAlgorithmException {
        String canonico = skeleton.phase() + "|" + skeleton.loadTarget().targetTss() + "|"
                + skeleton.requiresCoachReview() + "|" + skeleton.referenceDate() + "|" + skeleton.plannerScope();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(canonico.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
