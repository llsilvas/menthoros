package br.com.menthoros.backend.services.helper;

import br.com.menthoros.backend.domain.planner.InjuryRiskLevel;
import br.com.menthoros.backend.domain.planner.OnboardingContext;
import br.com.menthoros.backend.domain.planner.ReviewMode;
import br.com.menthoros.backend.domain.planner.WeekPlanSkeleton;
import br.com.menthoros.backend.dto.DecisaoProgressao;
import br.com.menthoros.backend.dto.input.DadosPlanoDto;
import br.com.menthoros.backend.dto.llm.PlanoSemanalLlmDto;
import br.com.menthoros.backend.dto.llm.TreinoPlanejadoLlmDto;
import br.com.menthoros.backend.dto.output.MetricasSemanaisMedias;
import br.com.menthoros.backend.dto.output.PadroesTreino;
import br.com.menthoros.backend.dto.output.TreinoRealizadoOutputDto;
import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.entity.PlanoMetaDados;
import br.com.menthoros.backend.entity.PlanoSemanal;
import br.com.menthoros.backend.entity.RevisaoSemanal;
import br.com.menthoros.backend.entity.TreinoPlanejado;
import br.com.menthoros.backend.enums.ConsumedReviewOutcome;
import br.com.menthoros.backend.enums.DiaSemana;
import br.com.menthoros.backend.enums.ModoGeracaoPlano;
import br.com.menthoros.backend.enums.OrigemAprovacao;
import br.com.menthoros.backend.enums.PlanoReviewStatus;
import br.com.menthoros.backend.enums.TipoTreino;
import br.com.menthoros.backend.events.RevisaoConsumidaEvent;
import br.com.menthoros.backend.exception.DomainNotFoundException;
import br.com.menthoros.backend.exception.DomainRuleViolationException;
import br.com.menthoros.backend.domain.compliance.PlannerComplianceStatus;
import br.com.menthoros.backend.domain.compliance.PlannerViolation;
import io.micrometer.core.instrument.MeterRegistry;
import br.com.menthoros.backend.exception.PlanoJaExistenteException;
import br.com.menthoros.backend.mapper.PlanoSemanalMapper;
import br.com.menthoros.backend.mapper.TreinoMapper;
import br.com.menthoros.backend.multitenancy.TenantContext;
import br.com.menthoros.backend.repository.PlanoMetadadosRepository;
import br.com.menthoros.backend.repository.PlanoSemanalRepository;
import br.com.menthoros.backend.services.MetricasAgregadasService;
import br.com.menthoros.backend.services.PlanoReviewService;
import br.com.menthoros.backend.services.impl.MetricasAlertaService;
import br.com.menthoros.backend.services.onboarding.OnboardingService;
import br.com.menthoros.backend.services.plano.ProvaNoPlanoService;
import br.com.menthoros.backend.util.Utils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Fase 3 da geração de plano (refactor-llm-call-outside-transaction, design.md D1): valida, monta
 * e persiste o plano numa transação que só começa DEPOIS da resposta do LLM.
 *
 * <p>Regra da fronteira (design.md D2): as entidades do {@link PlanGenerationContext} chegam
 * detached. Nenhuma associação do {@code PlanoSemanal} tem cascade, então associá-las por
 * referência é seguro — o Hibernate usa só o id para a FK. O único objeto que precisa estar
 * managed para ser <b>alterado</b> é o {@code PlanoMetaDados}, re-buscado por id em
 * {@link #prepararMetadados}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanGenerationPersister {

    private static final BigDecimal FATOR_INCREMENTO_VOLUME_PLANEJADO = BigDecimal.valueOf(1.10);
    private static final int DIAS_POR_SEMANA = 6;

    private final PlanoSemanalRepository planoSemanalRepository;
    private final PlanoMetadadosRepository planoMetadadosRepository;
    private final TreinoMapper treinoMapper;
    private final PlanoSemanalMapper planoSemanalMapper;
    private final RedistribuicaoTreinoHelper redistribuicaoHelper;
    private final MetricasAlertaService metricasAlertaService;
    private final MetricasAgregadasService metricasAgregadasService;
    private final PlannerShadowService plannerShadowService;
    private final OnboardingService onboardingService;
    private final PlanoReviewService planoReviewService;
    private final ApplicationEventPublisher eventPublisher;
    private final ProvaNoPlanoService provaNoPlanoService;
    private final MeterRegistry meterRegistry;

    @Value("${onboarding.auto-approve.enabled:true}")
    private boolean autoApproveEnabled;

    @Value("${onboarding.migrate-existing.enabled:true}")
    private boolean migrateExistingEnabled;

    // planner-engine-enforcement §5: com enabled=true, o estagio 2 (compliance pos-redistribuicao)
    // roda como ultimo passo antes de aprovar/salvar. Default false — rollout gated (tasks 8.4).
    @Value("${planner-engine.enabled:false}")
    private boolean plannerEnabled;

    // fail-open=true (default): violacao soft do estagio 2 => FAILED + requiresCoachReview (persiste,
    // exige revisao); false => erro de dominio, nada persistido (Decisao 3).
    @Value("${planner-engine.fail-open:true}")
    private boolean plannerFailOpen;

    /**
     * Persiste um plano completo gerado pela LLM: período, redistribuição de treinos conforme o
     * modo, metadados, shadow do planner, auto-approve do onboarding, revisão consumida e save.
     *
     * Idempotent: NÃO — cria o plano e atualiza metadados; a re-checagem de plano ativo e o
     *   índice parcial da V52 impedem duplicata.
     * Side Effects: INSERT em tb_plano_semanal/tb_treino_planejado, UPDATE em tb_plano_metadados,
     *   possível aprovação automática e eventos.
     * Tenant-aware: YES — {@code TenantContext.getRequiredTenantId()}.
     *
     * @throws PlanoJaExistenteException     já há plano ativo na semana (re-checagem)
     * @throws DomainRuleViolationException  sem treinos após redistribuição
     */
    @Transactional
    public PlanoSemanal persist(PlanoSemanalLlmDto planoDto, PlanGenerationContext ctx,
                                ModoGeracaoPlano modoGeracao) {
        DadosPlanoDto dadosPlano = ctx.dados();
        Atleta atleta = ctx.atleta();
        LocalDate semanaInicio = ctx.semanaInicio();
        DecisaoProgressao decisaoProgressao = ctx.decisaoProgressao();

        log.info("Iniciando persistência de plano completo para atleta {}", atleta.getId());

        PeriodoPlano periodo = new PeriodoPlano(semanaInicio);

        // Re-checagem dentro da transação de escrita (design.md D3, camada 2). Um plano REJEITADO
        // não bloqueia — casa com o índice único parcial da V52, que é a autoridade final.
        if (planoSemanalRepository.existePlanoAtivoNaSemana(atleta.getId(), semanaInicio, TenantContext.getRequiredTenantId())) {
            log.debug("Tentativa de gerar plano duplicado para atleta {} na semana de início {}.", atleta.getId(), semanaInicio);
            throw PlanoJaExistenteException.paraSemana(atleta.getId(), semanaInicio);
        }

        log.info("Período calculado: {} a {} (Modo: {}, {} treinos no plano LLM)",
                periodo.inicio(), periodo.fim(), modoGeracao, planoDto.treinosPlanejados().size());

        DiaSemana diaPrioritarioLongo = inferirDiaPrioritarioLongo(dadosPlano);
        List<TreinoPlanejadoLlmDto> treinos = obterTreinosParaPlano(
                planoDto.treinosPlanejados(), atleta, periodo, modoGeracao, diaPrioritarioLongo);

        // Volume recalculado da lista final (pós-garantia da prova), não o que o LLM declarou em
        // planoDto.volumePlanejadoKm() — sem isso PlanoMetaDados e o alerta de progressão ficam
        // desatualizados em relação ao plano de fato persistido (prova-no-plano-semanal, DoR 2.2).
        double volumePlanejadoRecalculado = calcularVolumeTotalPlanejadoDto(treinos);
        PlanoMetaDados metaDados = prepararMetadados(dadosPlano, volumePlanejadoRecalculado);

        PlanoSemanal plano = criarPlanoComTreinos(planoDto, atleta, periodo, metaDados, treinos);

        // Shadow do PlannerEngine (deterministic-planner-engine, Decisao 10): roda apos a geracao
        // legada, nunca altera plano/prompt/persistencia (CA12); falha isolada internamente (CA11).
        // batch=false: este call site nao distingue interativo de lote — tag de metrica aproximada.
        UUID tenantId = TenantContext.getRequiredTenantId();
        Optional<OnboardingContext> onboardingContext = resolverOnboardingContext(atleta.getId(), tenantId);
        Optional<WeekPlanSkeleton> weekPlanSkeleton = plannerShadowService.aplicarShadow(
                plano, planoDto, dadosPlano, decisaoProgressao, periodo.inicio(), false, onboardingContext);

        // planner-engine-enforcement §5 (Decisao 2): estagio 2 terminal — roda sobre os treinos ja
        // redistribuidos E com prova garantida (plano.getTreinosPlanejados()), como ULTIMO passo antes
        // de aprovar/salvar/emitir eventos. So enforca com enabled=true; caso contrario e no-op e o
        // shadow acima segue como auditoria (CA9). Usa periodo.inicio() como referenceDate (nunca now()).
        if (plannerEnabled) {
            weekPlanSkeleton.ifPresent(skeleton ->
                    aplicarEnforcementEstagio2(plano, skeleton, atleta, periodo.inicio()));
        }

        // Auto-approve Cenario A (athlete-onboarding-baseline CA5, Decisao 7).
        onboardingContext.ifPresent(context -> aplicarAutoApproveSeElegivel(plano, context, weekPlanSkeleton, tenantId));

        // Avaliacao semanal de calibracao: precisa do InjuryRiskLevel do shadow; sem skeleton,
        // tenta de novo no proximo ciclo.
        weekPlanSkeleton.ifPresent(skeleton -> onboardingService.avaliarCalibracaoSeAplicavel(
                atleta.getId(), tenantId, semanaInicio.minusDays(1), skeleton.injuryRisk().level()));

        // Revisao da semana anterior consumida como insumo (add-weekly-review-llm-focus D9/D11): a
        // MESMA que alimentou o prompt. Sem revisao consumivel o plano nasce NOT_CONSUMED —
        // ausencia de dado, nao julgamento negativo do coach.
        registrarRevisaoConsumida(plano, ctx.revisaoConsumidaOpcional());

        PlanoSemanal salvo = salvarPlanoCompleto(plano);
        publicarRevisaoConsumida(salvo, tenantId);
        return salvo;
    }

    /**
     * Grava no plano o vinculo com a revisao consumida e o desfecho inicial.
     *
     * Idempotent: YES — mutacao in-memory deterministica sobre o mesmo plano.
     * Side Effects: NONE (a persistencia acontece no salvarPlanoCompleto).
     * Tenant-aware: YES
     */
    private void registrarRevisaoConsumida(PlanoSemanal plano, Optional<RevisaoSemanal> revisao) {
        if (revisao.isEmpty()) {
            plano.setConsumedReviewOutcome(ConsumedReviewOutcome.NOT_CONSUMED);
            return;
        }
        plano.setConsumedReview(revisao.get());
        plano.setConsumedReviewOutcome(ConsumedReviewOutcome.PENDING);
    }

    /**
     * Publica o {@link RevisaoConsumidaEvent} DEPOIS da persistencia — o id do plano e
     * {@code @GeneratedValue}, entao antes do save o {@code planoId} seria sempre nulo.
     *
     * Idempotent: NAO — publica um evento por chamada.
     * Side Effects: publicacao de evento.
     * Tenant-aware: YES
     */
    private void publicarRevisaoConsumida(PlanoSemanal planoSalvo, UUID tenantId) {
        if (planoSalvo.getConsumedReview() == null) {
            return;
        }
        eventPublisher.publishEvent(new RevisaoConsumidaEvent(
                tenantId,
                planoSalvo.getAtleta().getId(),
                planoSalvo.getSemanaInicio(),
                planoSalvo.getConsumedReview().getId(),
                planoSalvo.getId()));
    }

    /**
     * Resolve o {@code OnboardingContext} respeitando o kill-switch
     * {@code onboarding.migrate-existing.enabled}: atleta com baseline sempre tem o contexto
     * recalculado; atleta legado sem snapshot so e migrado com a flag ligada.
     */
    private Optional<OnboardingContext> resolverOnboardingContext(UUID atletaId, UUID tenantId) {
        boolean atletaLegado = !onboardingService.possuiBaseline(atletaId, tenantId);
        if (atletaLegado && !migrateExistingEnabled) {
            return Optional.empty();
        }
        return Optional.of(onboardingService.montarContexto(atletaId, tenantId));
    }

    /**
     * Auto-aprova quando o atleta esta em Cenario A (EXCEPTION_ONLY) e o ciclo nao apresenta
     * risco; a dupla checagem com HIGH_RISK e defesa em profundidade, redundante por design.
     */
    private void aplicarAutoApproveSeElegivel(PlanoSemanal plano, OnboardingContext onboardingContext,
                                               Optional<WeekPlanSkeleton> weekPlanSkeleton, UUID tenantId) {
        if (!autoApproveEnabled) {
            return;
        }
        if (onboardingContext.planningPolicy().reviewMode() != ReviewMode.EXCEPTION_ONLY) {
            return;
        }
        if (weekPlanSkeleton.isEmpty()) {
            return;
        }
        // Veto do enforcement (planner-engine-enforcement §5, Codex blocker 3): plano que o estagio 2
        // marcou FAILED / requiresCoachReview NUNCA e auto-aprovado — entra em AGUARDANDO_REVISAO.
        if (PlannerComplianceStatus.FAILED.name().equals(plano.getPlannerComplianceStatus())
                || Boolean.TRUE.equals(plano.getPlannerRequiresCoachReview())) {
            return;
        }
        WeekPlanSkeleton skeleton = weekPlanSkeleton.get();
        if (skeleton.requiresCoachReview() || skeleton.injuryRisk().level() == InjuryRiskLevel.HIGH_RISK) {
            return;
        }
        planoReviewService.aprovarTransicao(plano, tenantId, OrigemAprovacao.AUTO_CONFIANCA_ALTA);
    }

    /**
     * Estagio 2 do enforcement (planner-engine-enforcement §5, Decisao 2): compliance
     * POS-redistribuicao, terminal (sem retry), sobre os treinos finais do {@code plano} — ja
     * redistribuidos e com prova garantida. Roda somente com {@code planner-engine.enabled=true}.
     *
     * <p>Todas as {@code PlannerViolation} do estagio 2 sao revisaveis (soft) nesta change — as
     * invariantes obrigatorias (hard) sao os checks estruturais do estagio 1, ja fail-closed em
     * {@code IaServiceImpl}. Matriz fail-open (Decisao 3): {@code fail-open=true} persiste
     * {@code FAILED} + {@code requiresCoachReview} (veta auto-aprovacao); {@code fail-open=false}
     * lanca erro de dominio antes de persistir. Sem violacao -> {@code PASSED}.
     *
     * <p>Idempotent: YES (leitura + mutacao in-memory deterministica). Side Effects: mutacao dos
     * campos {@code planner_*} de {@code plano}; metrica Micrometer. Tenant-aware: opera sobre objetos
     * ja resolvidos.
     */
    private void aplicarEnforcementEstagio2(PlanoSemanal plano, WeekPlanSkeleton skeleton,
                                            Atleta atleta, LocalDate referenceDate) {
        List<PlannerViolation> violacoes = plannerShadowService.checkPostRedistribution(
                plano.getTreinosPlanejados(), skeleton, atleta, referenceDate);
        if (violacoes.isEmpty()) {
            plano.setPlannerComplianceStatus(PlannerComplianceStatus.PASSED.name());
            return;
        }
        if (!plannerFailOpen) {
            String motivos = violacoes.stream()
                    .map(v -> v.key() + ": " + v.mensagem())
                    .collect(java.util.stream.Collectors.joining("; "));
            throw new DomainRuleViolationException(
                    "Plano viola a estrutura prescrita apos redistribuicao: " + motivos);
        }
        meterRegistry.counter("planner.compliance.failure.count", "stage", "POST").increment();
        plano.setPlannerComplianceStatus(PlannerComplianceStatus.FAILED.name());
        plano.setPlannerRequiresCoachReview(true);
    }

    private record PeriodoPlano(LocalDate inicio, LocalDate fim) {
        PeriodoPlano(LocalDate inicio) {
            this(inicio, inicio.plusDays(DIAS_POR_SEMANA));
        }
    }

    /**
     * Para SEMANA_ATUAL, redistribui considerando dias ja passados; nos demais modos usa os
     * treinos da LLM diretamente.
     */
    private List<TreinoPlanejadoLlmDto> obterTreinosParaPlano(List<TreinoPlanejadoLlmDto> treinosLlm,
                                                              Atleta atleta,
                                                              PeriodoPlano periodo,
                                                              ModoGeracaoPlano modoGeracao,
                                                              DiaSemana diaPrioritarioLongo) {
        List<TreinoPlanejadoLlmDto> treinos = ModoGeracaoPlano.SEMANA_ATUAL.equals(modoGeracao)
                ? redistribuicaoHelper.redistribuirTreinos(
                        treinosLlm,
                        atleta.getDiasDisponiveis(),
                        LocalDate.now(),
                        periodo.inicio(),
                        periodo.fim(),
                        modoGeracao,
                        diaPrioritarioLongo)
                : treinosLlm;

        // Garantia determinística (design.md D2): roda depois da redistribuição — senão o
        // SEMANA_ATUAL poderia mexer no dia da prova — e antes da validação, para o resultado
        // final ser o que é validado e vira volume.
        treinos = provaNoPlanoService.garantirProvasNaSemana(treinos, atleta, periodo.inicio(), periodo.fim());

        validarTreinosGerados(treinos);
        return treinos;
    }

    private double calcularVolumeTotalPlanejadoDto(List<TreinoPlanejadoLlmDto> treinos) {
        return treinos.stream()
                .mapToDouble(t -> t.distanciaKm() != null ? t.distanciaKm() : 0.0)
                .sum();
    }

    private DiaSemana inferirDiaPrioritarioLongo(DadosPlanoDto dadosPlano) {
        List<TreinoRealizadoOutputDto> ultimosTreinos = dadosPlano.ultimosTreinos();
        if (ultimosTreinos != null && !ultimosTreinos.isEmpty()) {
            Map<DiaSemana, Long> frequenciaPorDia = ultimosTreinos.stream()
                    .filter(t -> TipoTreino.LONGO.equals(t.tipoTreino()))
                    .map(TreinoRealizadoOutputDto::diaSemana)
                    .filter(Objects::nonNull)
                    .collect(Collectors.groupingBy(dia -> dia, Collectors.counting()));

            if (!frequenciaPorDia.isEmpty()) {
                List<Map.Entry<DiaSemana, Long>> ranking = frequenciaPorDia.entrySet().stream()
                        .sorted(Map.Entry.<DiaSemana, Long>comparingByValue().reversed())
                        .toList();

                Map.Entry<DiaSemana, Long> primeiro = ranking.getFirst();
                long segundoLugar = ranking.size() > 1 ? ranking.get(1).getValue() : 0;

                if (primeiro.getValue() >= 2 && primeiro.getValue() > segundoLugar) {
                    log.info("Dia prioritário do LONGO inferido pelo histórico: {} ({} ocorrências recentes)",
                            primeiro.getKey(), primeiro.getValue());
                    return primeiro.getKey();
                }
            }
        }

        DiaSemana fallback = dadosPlano.atleta().getDiaPreferidoLongo();
        if (fallback != null) {
            log.info("Usando diaPreferidoLongo configurado como fallback para LONGO: {}", fallback);
        }
        return fallback;
    }

    private void validarTreinosGerados(List<TreinoPlanejadoLlmDto> treinos) {
        if (treinos.isEmpty()) {
            throw new DomainRuleViolationException(
                    """
                    Não foi possível gerar treinos para a semana selecionada.
                        Motivos possíveis:
                        - Geração no meio da semana sem dias disponíveis
                        - Todos os treinos da LLM são incompatíveis (LONGO/INTERVALADO)
                        Sugestão: Gere para a próxima semana.
                    """);
        }
    }

    private PlanoSemanal criarPlanoComTreinos(PlanoSemanalLlmDto planoDto,
                                              Atleta atleta,
                                              PeriodoPlano periodo,
                                              PlanoMetaDados metaDados,
                                              List<TreinoPlanejadoLlmDto> treinosDto) {
        PlanoSemanal plano = criarPlanoEntity(planoDto, atleta, periodo.inicio(), periodo.fim(), metaDados);

        List<TreinoPlanejado> treinosPlanejados = treinosDto.stream()
                .map(dto -> converterTreino(dto, plano, periodo.inicio()))
                .toList();

        BigDecimal volumePlanejado = calcularVolumeTotalPlanejado(treinosPlanejados);

        plano.setVolumePlanejadoKm(volumePlanejado);
        plano.setVolumeAlvoKm(volumePlanejado);
        plano.setTreinosPlanejados(treinosPlanejados);

        return plano;
    }

    private TreinoPlanejado converterTreino(TreinoPlanejadoLlmDto dto, PlanoSemanal plano, LocalDate semanaInicio) {
        TreinoPlanejado treino = treinoMapper.toEntity(dto);
        treino.setPlanoSemanal(plano);

        DiaSemana diaSemana = DiaSemana.valueOf(dto.diaSemana());
        treino.setDataTreino(calcularDataTreino(semanaInicio, diaSemana));

        return treino;
    }

    private BigDecimal calcularVolumeTotalPlanejado(List<TreinoPlanejado> treinos) {
        double volume = treinos.stream()
                .mapToDouble(this::distanciaTreinoPlanejado)
                .sum();
        return BigDecimal.valueOf(volume);
    }

    private PlanoSemanal salvarPlanoCompleto(PlanoSemanal plano) {
        // PlanoMetaDados ja foi persistido em prepararMetadados(); nao salvar de novo.
        PlanoSemanal planoSalvo = planoSemanalRepository.save(plano);

        log.info("✅ Plano salvo - {} treinos, volume: {}km",
                plano.getTreinosPlanejados().size(),
                plano.getVolumePlanejadoKm());

        return planoSalvo;
    }

    private PlanoSemanal criarPlanoEntity(PlanoSemanalLlmDto planoDto, Atleta atleta, LocalDate semanaInicio,
                                          LocalDate semanaFim, PlanoMetaDados metaDados) {
        PlanoSemanal ultimoPlano = planoSemanalRepository
                .findTopByAtletaIdOrderBySemanaInicioDesc(atleta.getId())
                .orElse(null);

        PlanoSemanal plano = planoSemanalMapper.toEntity(planoDto);
        // Associacoes sem cascade: a referencia detached serve, o Hibernate so usa o id na FK.
        plano.setAtleta(atleta);
        plano.setAssessoria(atleta.getAssessoria());
        plano.setReviewStatus(PlanoReviewStatus.AGUARDANDO_REVISAO);

        plano.setSemanaInicio(semanaInicio);
        plano.setSemanaFim(semanaFim);

        if (ultimoPlano != null && ultimoPlano.getPlanoMetaDados() != null) {
            var mediaSemanalHistorica = ultimoPlano.getPlanoMetaDados().getVolumeSemanalMedio();
            var volumePlanejado = mediaSemanalHistorica.multiply(FATOR_INCREMENTO_VOLUME_PLANEJADO);

            metaDados.setVolumePlanejado(volumePlanejado);
            metaDados.setVolumeSemanalMedio(mediaSemanalHistorica);
        }

        plano.setPlanoMetaDados(metaDados);

        return plano;
    }

    /**
     * Atualiza os metadados do atleta — metricas semanais medias, padroes de treino, volume
     * planejado, progressao e alertas — a partir de treinos REALIZADOS, e persiste.
     */
    private PlanoMetaDados prepararMetadados(DadosPlanoDto dadosPlano, double volumePlanejadoKm) {
        PlanoMetaDados metaDadosCached = dadosPlano.metaDados();

        if (metaDadosCached.getId() == null) {
            log.debug("Metadados sem ID - retornando sem atualizar");
            return metaDadosCached;
        }

        // Re-busca por id: o objeto do contexto esta detached (carregado na transacao de leitura), e
        // este e o unico que sera ALTERADO — precisa estar managed para o merge nao virar
        // StaleObjectStateException. tenant-aware: garante que pertence ao tenant do contexto.
        UUID tenantId = TenantContext.getRequiredTenantId();
        PlanoMetaDados metaDados = planoMetadadosRepository.findByIdAndTenantId(metaDadosCached.getId(), tenantId)
                .orElseThrow(() -> new DomainNotFoundException(
                        "Metadados não encontrados: " + metaDadosCached.getId()));

        UUID atletaId = dadosPlano.atleta().getId();

        log.debug("Atualizando metadados para atleta {} com volume planejado: {}km",
                atletaId, volumePlanejadoKm);

        MetricasSemanaisMedias metricas = metricasAgregadasService.calcularMetricasSemanais(atletaId, 6);
        metaDados.setVolumeSemanalMedio(metricas.volumeMedio());
        metaDados.setTssSemanalMedio(metricas.tssMedio());
        metaDados.setTreinosPorSemanaMedio(metricas.treinosPorSemanaMedio());

        PadroesTreino padroes = metricasAgregadasService.calcularPadroesTreino(atletaId);
        metaDados.setDiasConsecutivosTreino(padroes.diasConsecutivos());
        metaDados.setDiasDesdeUltimoDescanso(padroes.diasDesdeDescanso());

        metaDados.setVolumePlanejado(BigDecimal.valueOf(volumePlanejadoKm));

        atualizarProgressao(metaDados, volumePlanejadoKm);

        metaDados.aplicarAnalise(metricasAlertaService.analisarMetricas(metaDados));

        metaDados = planoMetadadosRepository.save(metaDados);

        log.info("Metadados atualizados com sucesso para atleta {}", atletaId);

        return metaDados;
    }

    private double distanciaTreinoPlanejado(TreinoPlanejado treinoPlanejado) {
        if (treinoPlanejado.getDistanciaKm() != null) {
            return treinoPlanejado.getDistanciaKm().doubleValue();
        }
        if (treinoPlanejado.getEtapas() != null) {
            return treinoPlanejado.getEtapas().stream()
                    .map(e -> e.getDistanciaKm() == null ? 0.0 : e.getDistanciaKm().doubleValue())
                    .mapToDouble(Double::doubleValue)
                    .sum();
        }
        return 0.0;
    }

    /**
     * Incrementa o contador de semanas de progressao continua quando o volume planejado excede a
     * media; zera quando ha reducao — sinal para semana de recuperacao apos 3-4 aumentos.
     */
    private void atualizarProgressao(PlanoMetaDados metaDados, double volumeNovo) {
        if (metaDados.getVolumeSemanalMedio() != null) {
            if (volumeNovo > metaDados.getVolumeSemanalMedio().doubleValue()) {
                Integer semanas = metaDados.getSemanasProgressaoContinua();
                Integer novaContagem = semanas != null ? semanas + 1 : 1;
                metaDados.setSemanasProgressaoContinua(novaContagem);

                log.debug("Progressão contínua: {} semanas seguidas de aumento de volume", novaContagem);

                if (novaContagem >= 3) {
                    log.info("Atleta em progressão contínua há {} semanas. Considere semana de recuperação em breve.",
                            novaContagem);
                }
            } else {
                metaDados.setSemanasProgressaoContinua(0);
                log.debug("Progressão resetada - volume não aumentou em relação à média");
            }
        }
    }

    private LocalDate calcularDataTreino(LocalDate semanaInicio, DiaSemana diaSemana) {
        DayOfWeek dayOfWeek = Utils.converterParaDayOfWeek(diaSemana);
        return semanaInicio.with(TemporalAdjusters.nextOrSame(dayOfWeek));
    }
}
