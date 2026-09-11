package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.dto.DecisaoProgressao;
import br.com.menthoros.backend.dto.llm.EtapaTreinoLlmDto;
import br.com.menthoros.backend.dto.llm.PlanoSemanalLlmDto;
import br.com.menthoros.backend.dto.llm.TreinoPlanejadoLlmDto;
import br.com.menthoros.backend.dto.output.AtletaOutputDto;
import br.com.menthoros.backend.dto.output.PlanoSemanalOutputDto;
import br.com.menthoros.backend.dto.output.PlanoTreinoOutputDto;
import br.com.menthoros.backend.dto.output.TreinoRealizadoOutputDto;
import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.entity.PlanoMetaDados;
import br.com.menthoros.backend.entity.Prova;
import br.com.menthoros.backend.entity.RevisaoSemanal;
import br.com.menthoros.backend.enums.DiaSemana;
import br.com.menthoros.backend.enums.ModoGeracaoPlano;
import br.com.menthoros.backend.enums.NivelExperiencia;
import br.com.menthoros.backend.enums.TipoTreino;
import br.com.menthoros.backend.exception.DomainRuleViolationException;
import br.com.menthoros.backend.exception.LLMException;
import br.com.menthoros.backend.multitenancy.TenantContext;
import br.com.menthoros.backend.repository.AtletaRepository;
import br.com.menthoros.backend.services.IaService;
import br.com.menthoros.backend.services.helper.LlmUsageLogger;
import br.com.menthoros.backend.services.helper.PaceValidator;
import br.com.menthoros.backend.services.helper.RegraGeracaoTreino;
import br.com.menthoros.backend.services.helper.TreinoHistoricoProvider;
import br.com.menthoros.backend.services.helper.PlanoEstruturaReparador;
import br.com.menthoros.backend.services.helper.PlanoResilienceService;
import br.com.menthoros.backend.services.helper.PlannerShadowService;
import br.com.menthoros.backend.domain.compliance.PlannerViolation;
import br.com.menthoros.backend.services.helper.ZonaTreinoService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import br.com.menthoros.backend.services.helper.ZonaTreinoService.ZonaFC;
import br.com.menthoros.backend.services.prompt.PaceHistoricoFormatter;
import br.com.menthoros.backend.services.prompt.PlanoTreinoPromptBuilder;
import br.com.menthoros.backend.services.quality.PlanQualityChecker;
import br.com.menthoros.backend.services.quality.ViolacaoQualidade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import br.com.menthoros.backend.routing.ModelRouter;
import br.com.menthoros.backend.routing.TaskComplexity;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@Primary
public class IaServiceImpl implements IaService {

    private final ModelRouter modelRouter;
    private final PlanoTreinoPromptBuilder promptBuilder;
    private final AtletaRepository atletaRepository;
    private final RegraGeracaoTreino regraGeracaoTreino;
    private final TreinoHistoricoProvider treinoHistoricoProvider;
    private final PaceHistoricoFormatter paceHistoricoFormatter;
    private final PaceValidator paceValidator;
    private final ZonaTreinoService zonaTreinoService;
    private final PlanQualityChecker planQualityChecker;
    private final PlanoEstruturaReparador estruturaReparador;
    private final PlanoResilienceService planoResilienceService;
    private final MeterRegistry meterRegistry;
    private final LlmUsageLogger llmUsageLogger;
    private final PlannerShadowService plannerShadowService;

    public IaServiceImpl(ModelRouter modelRouter, PlanoTreinoPromptBuilder promptBuilder,
                         AtletaRepository atletaRepository, RegraGeracaoTreino regraGeracaoTreino,
                         TreinoHistoricoProvider treinoHistoricoProvider,
                         PaceHistoricoFormatter paceHistoricoFormatter,
                         PaceValidator paceValidator,
                         ZonaTreinoService zonaTreinoService,
                         PlanQualityChecker planQualityChecker,
                         PlanoEstruturaReparador estruturaReparador,
                         PlanoResilienceService planoResilienceService,
                         MeterRegistry meterRegistry,
                         LlmUsageLogger llmUsageLogger,
                         PlannerShadowService plannerShadowService) {
        this.modelRouter = modelRouter;
        this.promptBuilder = promptBuilder;
        this.atletaRepository = atletaRepository;
        this.regraGeracaoTreino = regraGeracaoTreino;
        this.treinoHistoricoProvider = treinoHistoricoProvider;
        this.paceHistoricoFormatter = paceHistoricoFormatter;
        this.paceValidator = paceValidator;
        this.zonaTreinoService = zonaTreinoService;
        this.planQualityChecker = planQualityChecker;
        this.estruturaReparador = estruturaReparador;
        this.planoResilienceService = planoResilienceService;
        this.meterRegistry = meterRegistry;
        this.llmUsageLogger = llmUsageLogger;
        this.plannerShadowService = plannerShadowService;
    }

    private OpenAiChatOptions defaultJsonSchemaOptions() {
        Map<String, Object> schemaMap = buildSchemaTightInlineOrDefs();

        var rf = ResponseFormat.builder()
                .type(ResponseFormat.Type.JSON_SCHEMA)
                .jsonSchema(ResponseFormat.JsonSchema.builder()
                        .name("PlanoSemanalLlmDto")
                        .schema(schemaMap)
                        .strict(true)
                        .build())
                .build();

        return OpenAiChatOptions.builder()
                .responseFormat(rf)
                .build();
    }
    @SuppressWarnings("unchecked")
    private static void enforceAllRequired(Map<String,Object> objNode) {
        if (objNode == null) return;
        Map<String,Object> props = (Map<String,Object>) objNode.get("properties");
        if (props == null) return;
        objNode.put("required", new java.util.ArrayList<>(props.keySet())); // strict:true exige TODAS as chaves
    }

    @SuppressWarnings("unchecked")
    private static void putMin(Map<String,Object> props, String name, Number min) {
        Map<String,Object> p = (Map<String,Object>) props.get(name);
        if (p != null) p.put("minimum", min);
    }

    @SuppressWarnings("unchecked")
    private static void putMax(Map<String,Object> props, String name, Number max) {
        Map<String,Object> p = (Map<String,Object>) props.get(name);
        if (p != null) p.put("maximum", max);
    }

    @SuppressWarnings("unchecked")
    private static void putEnum(Map<String,Object> props, String name, java.util.List<String> values) {
        Map<String,Object> p = (Map<String,Object>) props.get(name);
        if (p != null) p.put("enum", values);
    }

    private Map<String, Object> buildSchemaTightInlineOrDefs() {
        var converter = new BeanOutputConverter<>(PlanoSemanalLlmDto.class);
        var schema = (Map<String, Object>) ModelOptionsUtils.jsonToMap(converter.getJsonSchema());

        // ROOT properties
        Map<String, Object> planoProps = (Map<String, Object>) schema.get("properties");
        if (planoProps == null) return schema;

        // Volumes >= 0
        for (String k : List.of("volumePlanejadoKm", "volumeRealizadoKm", "volumeAlvoKm")) {
            putMin(planoProps, k, 0);
        }

        // Status enum
        putEnum(planoProps, "status", List.of("PLANEJADO", "INICIADO", "EM_ANDAMENTO", "ATIVO", "CONCLUIDO"));

        // treinosPlanejados array 3..5
        Map<String, Object> treinos = (Map<String, Object>) planoProps.get("treinosPlanejados");
        if (treinos != null) {
            treinos.put("minItems", 3);
            treinos.put("maxItems", 5);

            // TREINO items
            Map<String, Object> treinoItems = (Map<String, Object>) treinos.get("items");
            Map<String, Object> treinoProps = treinoItems != null
                    ? (Map<String, Object>) treinoItems.get("properties")
                    : null;

            if (treinoProps != null) {
                // provaId/descricao/zonaAlvo nunca vêm do LLM (prova-no-plano-semanal,
                // TreinoPlanejadoLlmDto.java) — ProvaNoPlanoService os preenche depois, no
                // servidor. Removidos ANTES de enforceAllRequired: sem isso, strict:true da
                // OpenAI força os três a "required" com um schema de UUID sem opção de nulo, e o
                // modelo não tem como expressar "sem prova" — cai no sentinel
                // 00000000-0000-0000-0000-000000000000, que é um UUID sintaticamente válido e
                // quebra a FK de tb_treino_planejado.prova_id ao persistir.
                treinoProps.remove("provaId");
                treinoProps.remove("descricao");
                treinoProps.remove("zonaAlvo");

                // Enums
                putEnum(treinoProps, "diaSemana",
                        List.of("DOMINGO", "SEGUNDA", "TERCA", "QUARTA", "QUINTA", "SEXTA", "SABADO"));
                putEnum(treinoProps, "tipoTreino",
                        List.of("REGENERATIVO", "INTERVALADO", "CONTINUO", "LONGO", "TIRO", "FARTLEK", "TEMPO_RUN"));
                putEnum(treinoProps, "statusTreino",
                        List.of("PENDENTE", "REALIZADO", "CANCELADO"));

                // Limites numéricos
                putMin(treinoProps, "intensidadePlanejada", 0.5);
                putMax(treinoProps, "intensidadePlanejada", 1.5);
                putMin(treinoProps, "percepcaoEsforcoEsperada", 1);
                putMax(treinoProps, "percepcaoEsforcoEsperada", 10);
                putMin(treinoProps, "duracaoMin", 1);
                putMin(treinoProps, "distanciaKm", 0);
                putMin(treinoProps, "tssPlanejado", 0);

                // Pattern ritmo: "5:30-6:00/km"
                Map<String, Object> ritmo = (Map<String, Object>) treinoProps.get("ritmoAlvo");
                if (ritmo != null) {
                    ritmo.put("pattern", "^[0-9]{1,2}:[0-5][0-9]-[0-9]{1,2}:[0-5][0-9]/km$");
                }

                // MaxLength justificativa
                Map<String, Object> just = (Map<String, Object>) treinoProps.get("justificativaIa");
                if (just != null) {
                    just.put("maxLength", 200);
                }

                // ETAPAS
                Map<String, Object> etapas = (Map<String, Object>) treinoProps.get("etapas");
                if (etapas != null) {
                    etapas.put("minItems", 2); // Mínimo 2 etapas (qualquer treino)
                    // Sem maxItems - permitir expansão completa de intervalados

                    Map<String, Object> etapaItems = (Map<String, Object>) etapas.get("items");
                    Map<String, Object> etapaProps = etapaItems != null
                            ? (Map<String, Object>) etapaItems.get("properties")
                            : null;

                    if (etapaProps != null) {
                        // Enum tipoEtapa
                        putEnum(etapaProps, "tipoEtapa",
                                List.of("AQUECIMENTO", "PRINCIPAL", "INTERVALADO", "RECUPERACAO", "DESAQUECIMENTO"));

                        // Limites
                        putMin(etapaProps, "ordem", 1);
                        putMin(etapaProps, "duracaoMin", 1);
                        putMin(etapaProps, "distanciaKm", 0);

                        // 🎯 CRÍTICO: repeticoes SEMPRE = 1
                        Map<String, Object> reps = (Map<String, Object>) etapaProps.get("repeticoes");
                        if (reps != null) {
                            reps.put("const", 1); // Força valor constante = 1
                            reps.put("default", 1);
                        }

                        // MaxLength descrição
                        Map<String, Object> desc = (Map<String, Object>) etapaProps.get("descricaoEtapa");
                        if (desc != null) {
                            desc.put("maxLength", 120);
                        }

                        // Pattern FC: "140-160 bpm" (range absoluto em bpm, alinhado com LTHR)
                        Map<String, Object> fc = (Map<String, Object>) etapaProps.get("fcAlvoEtapa");
                        if (fc != null) {
                            fc.put("pattern", "^[0-9]{2,3}-[0-9]{2,3} bpm$");
                        }

                        // ritmoAlvo por etapa: nullable (null para AQUECIMENTO/DESAQUECIMENTO/RECUPERACAO)
                        // anyOf com pattern válido ou null — compatível com strict:true do OpenAI
                        etapaProps.put("ritmoAlvo", new java.util.LinkedHashMap<>(java.util.Map.of(
                                "anyOf", java.util.List.of(
                                        java.util.Map.of("type", "string",
                                                "pattern", "^[0-9]{1,2}:[0-5][0-9]-[0-9]{1,2}:[0-5][0-9]/km$"),
                                        java.util.Map.of("type", "null")
                                )
                        )));
                    }

                    // Tornar todos os campos da etapa obrigatórios (ritmoAlvo nullable via anyOf)
                    if (etapaItems != null) {
                        enforceAllRequired(etapaItems);
                    }
                }

                // Tornar todos os campos do treino obrigatórios
                enforceAllRequired(treinoItems);
            }
        }

        // Tornar todos os campos do ROOT obrigatórios
        enforceAllRequired(schema);

        return schema;
    }



    @Override
    public PlanoSemanalLlmDto gerarPlanoSemanal(AtletaOutputDto atletaOutputDto, List<TreinoRealizadoOutputDto> treinoRealizadoOutputDtoList, PlanoSemanalOutputDto planoSemanalOutputDto) {
        String prompt = promptBuilder.buildRequest(atletaOutputDto, treinoRealizadoOutputDtoList, planoSemanalOutputDto);

        ChatClient chatClient = modelRouter.route(TaskComplexity.PLANO);
        log.info("Geração de plano roteada via TaskComplexity.PLANO (bean gpt4oPlanoClient)");

        try {
            PlanoSemanalLlmDto plano = chatClient.prompt()
                    .user(prompt)
//                    .options(defaultJsonSchemaOptions())
                    .call()
                    .entity(PlanoSemanalLlmDto.class);

            // Validação pós-geração
            plano = validarENormalizarPlanoGerado(plano, atletaOutputDto.id());

            log.info("Plano gerado com sucesso via structured output para atleta: {}", atletaOutputDto.id());
            return plano;

        } catch (Exception e) {
            log.error("Erro ao gerar plano via structured output para atleta {}: {}", atletaOutputDto.id(), e.getMessage(), e);
            throw new LLMException("Falha na geração de plano via IA: " + e.getMessage(), e);
        }
    }

    /**
     * Gera o plano semanal avançado via LLM (roteado por {@code TaskComplexity.PLANO} → GPT-4o).
     *
     * Idempotent: NO — invoca o LLM (saída não-determinística); sem escrita de estado por tentativa.
     * Side Effects: chamada ao LLM (billable); leitura de atleta/histórico/metadados; log de uso de
     *   tokens via {@code LlmUsageLogger} (best-effort); métricas Micrometer via {@code PlanoResilienceService}.
     * Tenant-aware: YES — o atleta é resolvido com predicado de tenant e {@code validarENormalizarPlanoGerado}
     *   opera sob o {@code TenantContext} corrente.
     */
    @Override
    public PlanoSemanalLlmDto geraPlanoSemanalAvancado(Atleta atleta, PlanoMetaDados metaDados, Prova prova, ModoGeracaoPlano modoGeracaoPlano, DecisaoProgressao decisaoProgressao, RevisaoSemanal revisaoConsumida, LocalDate inicioSemana, br.com.menthoros.backend.domain.planner.WeekPlanSkeleton skeleton){
        // Para SEMANA_ATUAL, filtra apenas os dias que ainda não passaram e informa o LLM.
        // Para PROXIMA_SEMANA, passa null — o prompt usa todos os dias disponíveis do atleta.
        List<DiaSemana> diasEfetivos = ModoGeracaoPlano.SEMANA_ATUAL.equals(modoGeracaoPlano)
                ? regraGeracaoTreino.filtrarDiasDisponiveis(atleta.getDiasDisponiveis(), LocalDate.now(), modoGeracaoPlano)
                : null;

        var promptGerado = promptBuilder.buildOptimizedPrompt(atleta, metaDados, prova, inicioSemana, diasEfetivos, decisaoProgressao, revisaoConsumida, skeleton);
        String prompt = promptGerado.prompt();

        ChatClient chatClient = modelRouter.route(TaskComplexity.PLANO);
        log.info("Geração de plano (avançado) roteada via TaskComplexity.PLANO (bean gpt4oPlanoClient)");

        long startTime = System.currentTimeMillis();
        PlanoSemanalLlmDto plano;
        try {
            // Geração resiliente: reparo já aplicado no validar; aqui, retry único com feedback se a
            // validação ainda falhar estruturalmente. Falha final → DomainRuleViolationException (4xx).
            plano = planoResilienceService.gerarComResiliencia(
                    p -> {
                        var resposta = chatClient.prompt().user(p).options(defaultJsonSchemaOptions())
                                .call().responseEntity(PlanoSemanalLlmDto.class);
                        llmUsageLogger.registrar(resposta.getResponse()); // best-effort, nunca lança
                        return resposta.getEntity();
                    },
                    p -> aplicarComplianceEstagio1(
                            validarENormalizarPlanoGerado(p, atleta.getId()), atleta, skeleton, inicioSemana),
                    prompt);
        } catch (DomainRuleViolationException e) {
            throw e; // falha estrutural final → mensagem ao treinador (não re-empacotar como 503)
        } catch (Exception e) {
            log.error("Erro ao gerar plano via structured output para atleta {}: {}", atleta.getId(), e.getMessage(), e);
            throw new LLMException("Falha na geração de plano via IA: " + e.getMessage(), e);
        }

        // Verificação de aderência às Constraint declaradas (mede via Micrometer; ação fica para a harden)
        List<ViolacaoQualidade> violacoes = planQualityChecker.check(plano, promptGerado.regras());
        if (!violacoes.isEmpty()) {
            log.warn("Plano do atleta {} com {} violação(ões) de constraint: {}",
                    atleta.getId(), violacoes.size(), violacoes.stream().map(ViolacaoQualidade::key).toList());
        }

        long totalTime = System.currentTimeMillis() - startTime;
        log.info("Plano gerado com sucesso via structured output para atleta: {} - {} s", atleta.getId(), totalTime / 1000.0);
        return plano;
    }

    /**
     * Estagio 1 do enforcement (planner-engine-enforcement secao 4): compliance PRE-redistribuicao.
     * Roda somente quando ha {@code skeleton} (flag {@code planner-engine.enabled=true}); com o flag
     * off recebe {@code skeleton == null} e é um no-op — prompt/geracao legados, sem checagem.
     *
     * <p>Violacao vira {@link LLMException}: dentro de {@code gerarComResiliencia} isso aciona o retry
     * com os motivos no feedback e, esgotado o orcamento, cai em {@code DomainRuleViolationException}
     * (422) — nenhuma nova geracao alem do orcamento (design Decisao 3b). Emite
     * {@code planner.compliance.failure.count{stage=PRE}} a cada violacao detectada.
     */
    private PlanoSemanalLlmDto aplicarComplianceEstagio1(PlanoSemanalLlmDto validado, Atleta atleta,
                                                         br.com.menthoros.backend.domain.planner.WeekPlanSkeleton skeleton,
                                                         LocalDate inicioSemana) {
        if (skeleton == null) {
            return validado;
        }
        List<PlannerViolation> violacoes = plannerShadowService.checkPreRedistribution(
                validado, skeleton, atleta, inicioSemana);
        if (!violacoes.isEmpty()) {
            meterRegistry.counter("planner.compliance.failure.count", "stage", "PRE").increment();
            String motivos = violacoes.stream()
                    .map(v -> v.key() + ": " + v.mensagem())
                    .collect(java.util.stream.Collectors.joining("; "));
            throw new LLMException("Plano diverge da estrutura prescrita pelo planner: " + motivos);
        }
        return validado;
    }

    /**
     * Valida o plano gerado pela IA, detectando inconsistências comuns
     */
    private PlanoSemanalLlmDto validarENormalizarPlanoGerado(PlanoSemanalLlmDto plano, UUID atletaId) {

        // tenant-aware: garante que o atleta pertence ao tenant do contexto
        UUID tenantId = TenantContext.getRequiredTenantId();
        Atleta atleta = atletaRepository.findByIdAndTenantId(atletaId, tenantId)
                .orElseThrow(() -> new LLMException("Atleta não encontrado"));

        if (plano == null || plano.treinosPlanejados() == null) {
            throw new LLMException("Plano gerado está nulo ou sem treinos");
        }

        // Pré-computar tetos e pisos de pace para validação
        var ctx = treinoHistoricoProvider.prepararContexto(atleta);
        Map<TipoTreino, BigDecimal> tetoPorTipo = paceHistoricoFormatter.calcularTetoPorTipo(ctx.treinosUltimas4Semanas());
        Map<TipoTreino, BigDecimal> pisoPorTipo = paceHistoricoFormatter.calcularPisoPorTipo(ctx.treinosUltimas4Semanas());

        // Pré-computar zonas de FC para validação de etapas (LTHR) — null se sem dados fisiológicos
        final List<ZonaFC> zonasParaValidacao;
        if (atleta.getFcLimiar() != null || atleta.getFcMaxima() != null) {
            zonasParaValidacao = zonaTreinoService.calcularZonasFC(
                    atleta.getFcMaximaCalculada(), atleta.getFcLimiarCalculada());
        } else {
            zonasParaValidacao = null;
        }

        List<TreinoPlanejadoLlmDto> treinosNormalizados = plano.treinosPlanejados().stream().map(treino -> {
            String tipoTreino = treino.tipoTreino();

            // Validar treinos INTERVALADO ou TIRO
            if ("INTERVALADO".equals(tipoTreino) || "TIRO".equals(tipoTreino)) {
                // (Passo 0) corrige distâncias de etapas temporais antes de expandir e normalizar
                List<EtapaTreinoLlmDto> etapasCorrigidas =
                        corrigirDistanciasEtapasTemporais(treino.etapas(), atleta.getPaceLimiar());
                treino = new TreinoPlanejadoLlmDto(
                        treino.diaSemana(), treino.tipoTreino(), treino.fcAlvo(),
                        treino.tssPlanejado(), treino.intensidadePlanejada(),
                        treino.percepcaoEsforcoEsperada(), treino.justificativaIa(),
                        treino.duracaoMin(), treino.distanciaKm(), treino.ritmoAlvo(),
                        etapasCorrigidas, treino.descricao(), treino.zonaAlvo(), treino.provaId());
                // Expansão ANTES da validação: corrige alucinação de compressão "NxDist"
                treino = expandirEtapasAgregadas(treino, zonasParaValidacao);
                validarTreinoIntervalado(treino, atletaId);
                treino = normalizarTreinoIntervalado(treino, atleta.getNivelExperiencia(), zonasParaValidacao);
                treino = reconciliarDistanciaComEtapas(treino);
            }

            // Fartlek: expande alucinações "Nx (AccelMin + RecovMin)" e reconcilia distância
            if ("FARTLEK".equals(tipoTreino)) {
                List<EtapaTreinoLlmDto> etapasCorrigidas =
                        corrigirDistanciasEtapasTemporais(treino.etapas(), atleta.getPaceLimiar());
                treino = new TreinoPlanejadoLlmDto(
                        treino.diaSemana(), treino.tipoTreino(), treino.fcAlvo(),
                        treino.tssPlanejado(), treino.intensidadePlanejada(),
                        treino.percepcaoEsforcoEsperada(), treino.justificativaIa(),
                        treino.duracaoMin(), treino.distanciaKm(), treino.ritmoAlvo(),
                        etapasCorrigidas, treino.descricao(), treino.zonaAlvo(), treino.provaId());
                treino = expandirEtapasAgregadas(treino, zonasParaValidacao);
                treino = reconciliarDistanciaComEtapas(treino);
            }

            // Reparo determinístico de estrutura "3 etapas" ANTES da validação (não-op p/ outros tipos):
            // sintetiza aquecimento/desaquecimento faltante ou reordena, evitando derrubar o plano por
            // violação trivial. Falta-PRINCIPAL/ambíguo não é reparado → cai na validação (retry).
            treino = estruturaReparador.reparar(treino, tipoTreino);

            // Validar treino LONGO
            if ("LONGO".equals(tipoTreino)) {
                validarTreinoLongo(treino, atletaId);
            }

            // Validar estrutura de treinos REGENERATIVO, CONTINUO e TEMPO_RUN
            if ("REGENERATIVO".equals(tipoTreino)) {
                validarTreinoRegenerativo(treino, atletaId);
            }
            if ("CONTINUO".equals(tipoTreino)) {
                validarTreinoContinuo(treino, atletaId);
            }
            if ("TEMPO_RUN".equals(tipoTreino)) {
                validarTreinoTempoRun(treino, atletaId, atleta);
            }

            // Validar repeticoes = 1 em todas as etapas
            validarRepeticoes(treino, atletaId);

            // Validar FC das etapas contra zonas fisiológicas LTHR
            if (zonasParaValidacao != null && treino.etapas() != null) {
                final String tipoTreinoFinal = treino.tipoTreino();
                List<EtapaTreinoLlmDto> etapasValidadas = treino.etapas().stream()
                        .map(etapa -> validarFcEtapa(etapa, tipoTreinoFinal, zonasParaValidacao))
                        .collect(Collectors.toList());
                treino = new TreinoPlanejadoLlmDto(
                        treino.diaSemana(), treino.tipoTreino(), treino.fcAlvo(),
                        treino.tssPlanejado(), treino.intensidadePlanejada(),
                        treino.percepcaoEsforcoEsperada(), treino.justificativaIa(),
                        treino.duracaoMin(), treino.distanciaKm(), treino.ritmoAlvo(), etapasValidadas,
                        treino.descricao(), treino.zonaAlvo(), treino.provaId()
                );
            }

            // Validar ritmoAlvo contra teto e piso de pace
            BigDecimal teto = null;
            BigDecimal piso = null;
            try {
                TipoTreino tipoEnum = TipoTreino.valueOf(tipoTreino);
                teto = tetoPorTipo.get(tipoEnum);
                piso = pisoPorTipo.get(tipoEnum);
            } catch (IllegalArgumentException ignored) {}
            String ritmoValidado = paceValidator.validar(treino.ritmoAlvo(), teto, piso);
            if (!Objects.equals(ritmoValidado, treino.ritmoAlvo())) {
                treino = new TreinoPlanejadoLlmDto(
                        treino.diaSemana(), treino.tipoTreino(), treino.fcAlvo(),
                        treino.tssPlanejado(), treino.intensidadePlanejada(),
                        treino.percepcaoEsforcoEsperada(), treino.justificativaIa(),
                        treino.duracaoMin(), treino.distanciaKm(), ritmoValidado, treino.etapas(),
                        treino.descricao(), treino.zonaAlvo(), treino.provaId()
                );
            }

            // Recalcular duração total com base na soma das etapas (override do valor gerado pelo LLM)
            if (treino.etapas() != null && !treino.etapas().isEmpty()) {
                int totalMinEtapas = somarDuracoesMin(treino.etapas());
                if (totalMinEtapas > 0) {
                    String duracaoAtual = treino.duracaoMin();
                    treino = recalcularDuracaoTreino(treino, treino.etapas());
                    if (!Objects.equals(duracaoAtual, treino.duracaoMin())) {
                        log.info("DURAÇÃO RECALCULADA [{}]: '{}' → '{}' (baseado nas {} etapas)",
                                tipoTreino, duracaoAtual, treino.duracaoMin(), treino.etapas().size());
                    }
                }
            }

            // Distância zerada em treino contínuo (ex.: REGENERATIVO sintetizado pelo reparo estrutural /
            // substituição por lesão): as etapas nascem só com duração, e corrigirDistanciasEtapasTemporais
            // não deriva a etapa PRINCIPAL. Aqui derivamos de duração×pace e reconciliamos o total — sem
            // sobrescrever distância válida já existente.
            treino = garantirDistanciaContinuo(treino, atleta.getPaceLimiar());

            // Validar triângulo pace × distância × duração (após recálculo)
            validarTrianguloPaceDuracaoDistancia(treino);

            return treino;
        }).collect(Collectors.toList());

        // Validar distribuição de carga semanal (dias consecutivos intensos)
        validarDistribuicaoCargaSemanal(treinosNormalizados);

        return new PlanoSemanalLlmDto(
                plano.volumePlanejadoKm(),
                plano.volumeAlvoKm(),
                plano.tsbInicio(),
                plano.tsbFim(),
                plano.status(),
                plano.objetivoSemanal(),
                treinosNormalizados
        );
    }

    // ======================== VALIDAÇÃO FC POR ZONA (LTHR) ========================

    /**
     * Extrai o range de FC do formato "NNN-NNN bpm".
     * Retorna null se o formato não for reconhecido ou o valor for nulo.
     */
    private int[] parseFcRange(String fcAlvoEtapa) {
        if (fcAlvoEtapa == null) return null;
        var matcher = Pattern.compile("^(\\d{2,3})-(\\d{2,3}) bpm$").matcher(fcAlvoEtapa.trim());
        if (!matcher.matches()) return null;
        return new int[]{ Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)) };
    }

    /**
     * Retorna o range de FC esperado para o tipo de etapa, considerando também o tipo de treino.
     * <p>O tipoTreino afina o mapeamento da etapa PRINCIPAL, que varia de Z1-Z2 (REGENERATIVO)
     * até Z4-Z5 (INTERVALADO/TIRO). Sem tipoTreino, PRINCIPAL cai no default Z2-Z4.</p>
     * <table border="1">
     *   <tr><th>tipoEtapa</th><th>tipoTreino</th><th>Zona</th></tr>
     *   <tr><td>AQUECIMENTO / DESAQUECIMENTO</td><td>qualquer</td><td>Z1</td></tr>
     *   <tr><td>RECUPERACAO</td><td>qualquer</td><td>Z1</td></tr>
     *   <tr><td>INTERVALADO</td><td>qualquer</td><td>Z4–Z5</td></tr>
     *   <tr><td>PRINCIPAL</td><td>REGENERATIVO</td><td>Z1–Z2</td></tr>
     *   <tr><td>PRINCIPAL</td><td>CONTINUO / FACIL / LONGO</td><td>Z2–Z3</td></tr>
     *   <tr><td>PRINCIPAL</td><td>FARTLEK</td><td>Z2–Z4</td></tr>
     *   <tr><td>PRINCIPAL</td><td>TEMPO_RUN</td><td>Z3–Z4</td></tr>
     *   <tr><td>PRINCIPAL</td><td>INTERVALADO / TIRO</td><td>Z4–Z5</td></tr>
     *   <tr><td>PRINCIPAL</td><td>default/null</td><td>Z2–Z4</td></tr>
     * </table>
     */
    private int[] zonaEsperadaFC(String tipoEtapa, String tipoTreino, List<ZonaFC> zonasFC) {
        if (tipoEtapa == null || zonasFC == null || zonasFC.size() < 5) return null;
        return switch (tipoEtapa.toUpperCase()) {
            case "AQUECIMENTO", "RECUPERACAO", "DESAQUECIMENTO" ->
                    new int[]{ zonasFC.get(0).fcMin(), zonasFC.get(0).fcMax() }; // Z1
            case "PRINCIPAL" -> zonaParaEtapaPrincipal(tipoTreino, zonasFC);
            case "INTERVALADO" ->
                    new int[]{ zonasFC.get(3).fcMin(), zonasFC.get(4).fcMax() }; // Z4–Z5
            default -> null;
        };
    }

    /** Resolve a zona esperada para etapa PRINCIPAL com base no tipo do treino. */
    private int[] zonaParaEtapaPrincipal(String tipoTreino, List<ZonaFC> zonasFC) {
        if (tipoTreino == null) return new int[]{ zonasFC.get(1).fcMin(), zonasFC.get(3).fcMax() }; // Z2-Z4 default
        return switch (tipoTreino.toUpperCase()) {
            case "REGENERATIVO"            -> new int[]{ zonasFC.get(0).fcMin(), zonasFC.get(1).fcMax() }; // Z1-Z2
            case "CONTINUO", "FACIL", "LONGO" -> new int[]{ zonasFC.get(1).fcMin(), zonasFC.get(2).fcMax() }; // Z2-Z3
            case "FARTLEK"                 -> new int[]{ zonasFC.get(1).fcMin(), zonasFC.get(3).fcMax() }; // Z2-Z4
            case "TEMPO_RUN"               -> new int[]{ zonasFC.get(2).fcMin(), zonasFC.get(3).fcMax() }; // Z3-Z4
            case "INTERVALADO", "TIRO"     -> new int[]{ zonasFC.get(3).fcMin(), zonasFC.get(4).fcMax() }; // Z4-Z5
            default                        -> new int[]{ zonasFC.get(1).fcMin(), zonasFC.get(3).fcMax() }; // Z2-Z4
        };
    }

    /**
     * Verifica se o {@code fcAlvoEtapa} tem sobreposição ≥50% com a zona fisiológica esperada.
     * <p>Em caso de divergência, corrige o valor para o quartil central da zona esperada
     * e registra um {@code WARN}. Nunca lança exceção — manter o plano válido é prioridade.</p>
     */
    private EtapaTreinoLlmDto validarFcEtapa(EtapaTreinoLlmDto etapa, String tipoTreino, List<ZonaFC> zonasFC) {
        int[] prescrito = parseFcRange(etapa.fcAlvoEtapa());
        if (prescrito == null) {
            if (etapa.fcAlvoEtapa() != null) {
                log.warn("fcAlvoEtapa não parseable, mantendo original: tipo='{}' valor='{}'",
                        etapa.tipoEtapa(), etapa.fcAlvoEtapa());
            }
            return etapa;
        }

        int[] esperado = zonaEsperadaFC(etapa.tipoEtapa(), tipoTreino, zonasFC);
        if (esperado == null) return etapa;

        int prescMin = prescrito[0], prescMax = prescrito[1];
        int espMin   = esperado[0],  espMax   = esperado[1];

        int overlap = Math.max(0, Math.min(prescMax, espMax) - Math.max(prescMin, espMin));
        int larguraPrescrita = Math.max(1, prescMax - prescMin);
        double overlapPct = (double) overlap / larguraPrescrita;

        if (overlapPct < 0.50) {
            // Corrigir para o quartil central da zona esperada
            int amplitude = espMax - espMin;
            int centroMin = espMin + amplitude / 4;
            int centroMax = espMax - amplitude / 4;
            String fcCorrigida = centroMin + "-" + centroMax + " bpm";
            log.warn("FC fora da zona esperada: tipo='{}', prescrito='{}', esperado='{}-{} bpm', corrigindo para '{}'",
                    etapa.tipoEtapa(), etapa.fcAlvoEtapa(), espMin, espMax, fcCorrigida);
            return new EtapaTreinoLlmDto(
                    etapa.ordem(), etapa.tipoEtapa(), etapa.descricaoEtapa(),
                    etapa.duracaoMin(), etapa.distanciaKm(), fcCorrigida, etapa.repeticoes(), etapa.ritmoAlvo()
            );
        }
        return etapa;
    }

    /**
     * Normaliza treino intervalado/tiro ajustando distâncias das etapas.
     * Abordagem puramente funcional: cria novas listas e records em cada passo.
     */
    private TreinoPlanejadoLlmDto normalizarTreinoIntervalado(TreinoPlanejadoLlmDto treino, NivelExperiencia nivel, List<ZonaFC> zonas) {
        if (!"INTERVALADO".equalsIgnoreCase(treino.tipoTreino()) && !"TIRO".equalsIgnoreCase(treino.tipoTreino())) {
            return treino;
        }

        if (treino.etapas() == null || treino.etapas().isEmpty()) return treino;

        double alvo = treino.distanciaKm() != null ? treino.distanciaKm() : 0.0;
        if (alvo <= 0.0) return treino;

        // Cópia mutável para trabalhar sem alterar o record original
        List<EtapaTreinoLlmDto> etapas = new java.util.ArrayList<>(treino.etapas());

        // 1) Ajustar aquecimento / desaquecimento para faixas fisiológicas
        etapas = clampDistanciaPorTipo(etapas, "AQUECIMENTO", 1.0, 2.0);
        etapas = clampDistanciaPorTipo(etapas, "DESAQUECIMENTO", 0.8, 1.5);

        // 2) Recalcular gap de distância
        double soma = somarDistancias(etapas);
        double gap = alvo - soma; // >0: faltando, <0: sobrando

        if (Math.abs(gap) > 0.05) {
            // 2.1) Se sobrar bastante distância e ainda dá pra ter mais tiros → adiciona tiro/rec
            int maxTiros = maxTirosPorNivel(nivel);
            int tirosAtuais = contarPorTipo(etapas, "INTERVALADO");

            while (gap > 0.6 && tirosAtuais < maxTiros) {
                etapas = adicionarTiroERecuperacao(etapas, 0.8, 0.3, 4, 2, zonas);
                tirosAtuais++;
                gap -= 1.1; // aproximado
            }

            // 2.2) Recalcular depois de adicionar tiros
            soma = somarDistancias(etapas);
            gap = alvo - soma;

            // 2.3) Distribuir delta restante nos tiros e recuperações
            if (Math.abs(gap) > 0.05) {
                var resultadoTiros = distribuirDeltaPorTipo(etapas, "INTERVALADO", gap, 0.4, 1.2);
                etapas = resultadoTiros.etapas();
                gap = resultadoTiros.restante();

                var resultadoRecs = distribuirDeltaPorTipo(etapas, "RECUPERACAO", gap, 0.2, 0.5);
                etapas = resultadoRecs.etapas();

                double somaFinal = somarDistancias(etapas);
                double deltaFinal = alvo - somaFinal;

                if (Math.abs(deltaFinal) > 0.2) {
                    log.warn("NORMALIZADOR: ainda há desvio de distância (alvo={} km, final={} km, delta={})",
                            alvo, somaFinal, deltaFinal);
                }
            }
        }

        // 3) Retornar novo record com etapas ajustadas e duração recalculada
        return recalcularDuracaoTreino(treino, etapas);
    }

    /**
     * Reconcilia distanciaKm do treino com a soma real das etapas geradas.
     *
     * <p>Após expansão de etapas (Fartlek, Intervalado), a distância declarada no nível
     * do treino pode divergir da soma das etapas individuais. Se o desvio for superior
     * a 10%, substitui distanciaKm pela soma das etapas (que representa a realidade).</p>
     */
    private TreinoPlanejadoLlmDto reconciliarDistanciaComEtapas(TreinoPlanejadoLlmDto treino) {
        if (treino.etapas() == null || treino.etapas().isEmpty()) return treino;

        double somaEtapas = somarDistancias(treino.etapas());
        double distanciaAtual = treino.distanciaKm() != null ? treino.distanciaKm() : 0.0;

        if (distanciaAtual <= 0) {
            log.info("RECONCILIAÇÃO [{}]: distanciaKm não definida → usando soma das etapas: {} km",
                    treino.tipoTreino(), somaEtapas);
            return new TreinoPlanejadoLlmDto(
                    treino.diaSemana(), treino.tipoTreino(), treino.fcAlvo(),
                    treino.tssPlanejado(), treino.intensidadePlanejada(),
                    treino.percepcaoEsforcoEsperada(), treino.justificativaIa(),
                    treino.duracaoMin(), somaEtapas, treino.ritmoAlvo(), treino.etapas(),
                    treino.descricao(), treino.zonaAlvo(), treino.provaId()
            );
        }

        double desvioPercent = Math.abs(somaEtapas - distanciaAtual) / distanciaAtual;
        if (desvioPercent > 0.10) {
            log.warn("RECONCILIAÇÃO [{}]: distanciaKm={} km, soma_etapas={} km → desvio {}% > 10%, reconciliando",
                    treino.tipoTreino(), distanciaAtual, String.format("%.2f", somaEtapas),
                    Math.round(desvioPercent * 100));
            return new TreinoPlanejadoLlmDto(
                    treino.diaSemana(), treino.tipoTreino(), treino.fcAlvo(),
                    treino.tssPlanejado(), treino.intensidadePlanejada(),
                    treino.percepcaoEsforcoEsperada(), treino.justificativaIa(),
                    treino.duracaoMin(), somaEtapas, treino.ritmoAlvo(), treino.etapas(),
                    treino.descricao(), treino.zonaAlvo(), treino.provaId()
            );
        }

        return treino;
    }

    /**
     * Resultado da distribuição de delta: nova lista de etapas + delta restante.
     */
    private record DistribuicaoResult(List<EtapaTreinoLlmDto> etapas, double restante) {}

    private double somarDistancias(List<EtapaTreinoLlmDto> etapas) {
        return etapas.stream()
                .mapToDouble(e -> e.distanciaKm() != null ? e.distanciaKm() : 0.0)
                .sum();
    }

    private int somarDuracoesMin(List<EtapaTreinoLlmDto> etapas) {
        return etapas.stream()
                .mapToInt(e -> e.duracaoMin() != null ? e.duracaoMin() : 0)
                .sum();
    }

    private int contarPorTipo(List<EtapaTreinoLlmDto> etapas, String tipo) {
        return (int) etapas.stream()
                .filter(e -> tipo.equalsIgnoreCase(e.tipoEtapa()))
                .count();
    }

    /**
     * Retorna nova lista com distâncias clamped para etapas do tipo especificado.
     */
    private List<EtapaTreinoLlmDto> clampDistanciaPorTipo(List<EtapaTreinoLlmDto> etapas,
                                                           String tipo,
                                                           double min, double max) {
        return etapas.stream().map(e -> {
            if (!tipo.equalsIgnoreCase(e.tipoEtapa())) return e;
            Double d = e.distanciaKm();
            if (d == null || d <= 0) return e;
            if (d >= min && d <= max) return e;

            return new EtapaTreinoLlmDto(
                    e.ordem(), e.tipoEtapa(), e.descricaoEtapa(),
                    e.duracaoMin(), Math.max(min, Math.min(max, d)),
                    e.fcAlvoEtapa(), e.repeticoes(), e.ritmoAlvo()
            );
        }).collect(Collectors.toList());
    }

    /**
     * Distribui delta de distância entre etapas do tipo especificado.
     * Retorna nova lista completa + delta restante.
     */
    private DistribuicaoResult distribuirDeltaPorTipo(List<EtapaTreinoLlmDto> etapas,
                                                      String tipo,
                                                      double delta,
                                                      double min, double max) {
        List<EtapaTreinoLlmDto> resultado = new java.util.ArrayList<>(etapas);
        long count = resultado.stream().filter(e -> tipo.equalsIgnoreCase(e.tipoEtapa())).count();
        if (count == 0) return new DistribuicaoResult(resultado, delta);

        double restante = delta;

        for (int round = 0; round < 3 && Math.abs(restante) > 0.01; round++) {
            double passo = restante / count;

            for (int i = 0; i < resultado.size(); i++) {
                if (Math.abs(restante) < 0.01) break;

                EtapaTreinoLlmDto e = resultado.get(i);
                if (!tipo.equalsIgnoreCase(e.tipoEtapa())) continue;

                double atual = e.distanciaKm() != null ? e.distanciaKm() : 0.0;
                double proposto = atual + passo;

                double novo;
                if (restante > 0) {
                    novo = Math.min(proposto, max);
                } else {
                    novo = Math.max(proposto, min);
                }

                double aplicado = novo - atual;
                if ((restante > 0 && aplicado > 0) || (restante < 0 && aplicado < 0)) {
                    resultado.set(i, new EtapaTreinoLlmDto(
                            e.ordem(), e.tipoEtapa(), e.descricaoEtapa(),
                            e.duracaoMin(), atual + aplicado,
                            e.fcAlvoEtapa(), e.repeticoes(), e.ritmoAlvo()
                    ));
                    restante -= aplicado;
                }
            }
        }

        return new DistribuicaoResult(resultado, restante);
    }

    private int maxTirosPorNivel(NivelExperiencia nivel) {
        if (nivel == null) return 5; // default

        return switch (nivel) {
            case INICIANTE      -> 4;
            case INTERMEDIARIO  -> 5;
            case AVANCADO       -> 7;
            case ELITE          -> 10;
        };
    }

    /**
     * Retorna nova lista com tiro e recuperação inseridos antes do desaquecimento.
     */
    private List<EtapaTreinoLlmDto> adicionarTiroERecuperacao(List<EtapaTreinoLlmDto> etapas,
                                                               double distTiro,
                                                               double distRec,
                                                               int duracaoTiroMin,
                                                               int duracaoRecMin,
                                                               List<ZonaFC> zonas) {
        if (etapas.isEmpty()) return etapas;

        String fcTiro = bpmDaZona(zonas, 4); // Z5
        if (fcTiro == null) fcTiro = "90-95% FCmax";
        String fcRec = bpmDaZona(zonas, 0); // Z1
        if (fcRec == null) fcRec = "70-80% FCmax";

        List<EtapaTreinoLlmDto> resultado = new java.util.ArrayList<>(etapas);

        // Inserir antes do desaquecimento (ou no fim)
        int idxDesaq = -1;
        for (int i = 0; i < resultado.size(); i++) {
            if ("DESAQUECIMENTO".equalsIgnoreCase(resultado.get(i).tipoEtapa())) {
                idxDesaq = i;
                break;
            }
        }
        int insertIndex = (idxDesaq >= 0) ? idxDesaq : resultado.size();

        resultado.add(insertIndex, new EtapaTreinoLlmDto(
                0, "INTERVALADO", "Tiro extra em Z5",
                duracaoTiroMin, distTiro, fcTiro, 1, null
        ));
        resultado.add(insertIndex + 1, new EtapaTreinoLlmDto(
                0, "RECUPERACAO", "Recuperação extra em Z2",
                duracaoRecMin, distRec, fcRec, 1, null
        ));

        // Reordenar ordens 1..N
        return reordenarEtapas(resultado);
    }

    /**
     * Retorna nova lista com ordens sequenciais 1..N.
     */
    private List<EtapaTreinoLlmDto> reordenarEtapas(List<EtapaTreinoLlmDto> etapas) {
        List<EtapaTreinoLlmDto> resultado = new java.util.ArrayList<>(etapas.size());
        for (int i = 0; i < etapas.size(); i++) {
            EtapaTreinoLlmDto e = etapas.get(i);
            resultado.add(new EtapaTreinoLlmDto(
                    i + 1, e.tipoEtapa(), e.descricaoEtapa(),
                    e.duracaoMin(), e.distanciaKm(),
                    e.fcAlvoEtapa(), e.repeticoes(), e.ritmoAlvo()
            ));
        }
        return resultado;
    }

    private static final double PACE_Z2_DEFAULT_MIN_KM = 7.0;  // 7:00/km — Z2 genérico sem limiar cadastrado
    private static final double PACE_Z1_DEFAULT_MIN_KM = 8.0;  // 8:00/km — Z1 genérico sem limiar cadastrado
    private static final double FATOR_PACE_Z2 = 1.20;          // Z2 ≈ limiar × 1.20
    private static final double FATOR_PACE_Z1 = 1.35;          // Z1 ≈ limiar × 1.35

    // Detecta "NxDist" como "6x400m", "8 x 200", "5×1000m"
    // O lookahead (?!\s*min) impede que "5x 2min" seja lido como 5 tiros de 2 metros: sem ele o
    // grupo (m|km)? casa o "m" de "min" e o treino por tempo cai no caminho de distância.
    private static final Pattern REPETICOES_PATTERN =
            Pattern.compile("(\\d{1,2})\\s*[xX×]\\s*(\\d+)(?!\\s*min)\\s*(m|km)?", Pattern.CASE_INSENSITIVE);

    // Detecta "Nx (AccelMin + RecovMin)" como "4x (1min Z2 + 2min Z1)", "6 x (2min Z4 + 1min Z2)"
    private static final Pattern FARTLEK_TEMPO_PATTERN =
            Pattern.compile("(\\d{1,2})\\s*[xX×]\\s*\\(?\\s*(\\d+)\\s*min[^+]*\\+\\s*(\\d+)\\s*min",
                    Pattern.CASE_INSENSITIVE);

    /** Tipos de etapa que podem carregar uma série comprimida na descrição. */
    private static final java.util.Set<String> ETAPAS_EXPANSIVEIS =
            java.util.Set.of("INTERVALADO", "PRINCIPAL");

    private static String normalizarTipoEtapa(String tipoEtapa) {
        return tipoEtapa == null ? "" : tipoEtapa.trim().toUpperCase();
    }

    private record FartlekParams(int n, int duracaoAceleracao, int duracaoRecuperacao,
                                  String zonaAceleracao, String zonaRecuperacao) {}

    /**
     * Detecta etapas INTERVALADO comprimidas pelo LLM e as expande em etapas individuais.
     *
     * <p>Suporta dois padrões de compressão:</p>
     * <ul>
     *   <li><b>NxDist</b> (intervalados): "6x400m Z5" → 6× (INTERVALADO 0.4km + RECUPERACAO)</li>
     *   <li><b>Nx(Accel+Recov)</b> (fartlek): "4x (1min Z2 + 2min Z1)" → 4× (INTERVALADO 1min + RECUPERACAO 2min)</li>
     * </ul>
     */
    private TreinoPlanejadoLlmDto expandirEtapasAgregadas(TreinoPlanejadoLlmDto treino, List<ZonaFC> zonas) {
        if (treino.etapas() == null || treino.etapas().isEmpty()) return treino;

        List<EtapaTreinoLlmDto> etapas = treino.etapas();
        List<EtapaTreinoLlmDto> resultado = new java.util.ArrayList<>();
        boolean expandiu = false;

        int i = 0;
        while (i < etapas.size()) {
            EtapaTreinoLlmDto etapa = etapas.get(i);

            // O LLM tipa a série ora como INTERVALADO, ora como PRINCIPAL — ambos são válidos no
            // schema, e restringir a INTERVALADO fazia o fartlek escapar da expansão em silêncio.
            // Quem decide não é o tipo, é o padrão na descrição: PRINCIPAL sem padrão cai no
            // "nenhum padrão detectado" ao final do laço e é preservada intacta.
            if (!ETAPAS_EXPANSIVEIS.contains(normalizarTipoEtapa(etapa.tipoEtapa()))) {
                resultado.add(etapa);
                i++;
                continue;
            }

            // --- Caminho 1: padrão distância "6x400m" ---
            int n = detectarRepeticoesNaDescricao(etapa.descricaoEtapa());
            if (n > 1) {
                EtapaTreinoLlmDto recTemplate = recuperacaoAdjacenteOu(etapas, i + 1);
                if (recTemplate != null) i++;

                double distTiro = extrairDistanciaUnitariaDaDescricao(etapa.descricaoEtapa(), etapa.distanciaKm(), n);
                int durTiro   = Math.max(1, etapa.duracaoMin() != null ? etapa.duracaoMin() / n : 4);
                int durRec    = recTemplate != null && recTemplate.duracaoMin() != null
                        ? Math.max(1, recTemplate.duracaoMin() / n) : Math.max(1, durTiro / 2);
                double distRec = recTemplate != null && recTemplate.distanciaKm() != null
                        ? arredondar2(recTemplate.distanciaKm() / n) : arredondar2(distTiro * 0.4);
                String fcTiro = etapa.fcAlvoEtapa() != null ? etapa.fcAlvoEtapa() : "90-95% FCmax";
                String fcRec  = recTemplate != null && recTemplate.fcAlvoEtapa() != null
                        ? recTemplate.fcAlvoEtapa() : "60-70% FCmax";

                String ritmoTiro = etapa.ritmoAlvo();
                String ritmoRec  = recTemplate != null ? recTemplate.ritmoAlvo() : null;
                for (int rep = 1; rep <= n; rep++) {
                    resultado.add(new EtapaTreinoLlmDto(0, "INTERVALADO",
                            "Intervalo " + rep + "/" + n + " - Z5", durTiro, distTiro, fcTiro, 1, ritmoTiro));
                    resultado.add(new EtapaTreinoLlmDto(0, "RECUPERACAO",
                            "Recuperação " + rep + " - trote Z2", durRec, distRec, fcRec, 1, ritmoRec));
                }
                expandiu = true;
                log.info("EXPANSÃO NxDist [{}]: '{}' → {} tiros ({} etapas)",
                        treino.tipoTreino(), etapa.descricaoEtapa(), n, n * 2);
                i++;
                continue;
            }

            // --- Caminho 2: padrão tempo "4x (1min Z2 + 2min Z1)" ---
            FartlekParams fp = detectarFartlekNaDescricao(etapa.descricaoEtapa());
            if (fp != null) {
                EtapaTreinoLlmDto recTemplate = recuperacaoAdjacenteOu(etapas, i + 1);
                if (recTemplate != null) i++;

                int totalMinPorRep = fp.duracaoAceleracao() + fp.duracaoRecuperacao();
                Double distTotal   = etapa.distanciaKm();
                double distPorRep  = (distTotal != null && distTotal > 0 && totalMinPorRep > 0)
                        ? arredondar2(distTotal / fp.n()) : 0.0;
                double distAccel   = distPorRep > 0
                        ? arredondar2(distPorRep * fp.duracaoAceleracao() / totalMinPorRep) : 0.0;
                double distRecov   = distPorRep > 0 ? arredondar2(distPorRep - distAccel) : 0.0;

                String fcAccel = fp.zonaAceleracao() != null ? zonaParaFc(fp.zonaAceleracao(), zonas)
                        : (etapa.fcAlvoEtapa() != null ? etapa.fcAlvoEtapa() : "75-85% FCmax");
                String fcRecov = fp.zonaRecuperacao() != null ? zonaParaFc(fp.zonaRecuperacao(), zonas)
                        : (recTemplate != null && recTemplate.fcAlvoEtapa() != null
                                ? recTemplate.fcAlvoEtapa() : "60-70% FCmax");

                String ritmoAccel = etapa.ritmoAlvo();
                String ritmoRecov = recTemplate != null ? recTemplate.ritmoAlvo() : null;
                for (int rep = 1; rep <= fp.n(); rep++) {
                    resultado.add(new EtapaTreinoLlmDto(0, "INTERVALADO",
                            "Aceleração " + rep + "/" + fp.n() + " - " + fp.duracaoAceleracao() + "min",
                            fp.duracaoAceleracao(), distAccel, fcAccel, 1, ritmoAccel));
                    resultado.add(new EtapaTreinoLlmDto(0, "RECUPERACAO",
                            "Recuperação " + rep + " - " + fp.duracaoRecuperacao() + "min trote",
                            fp.duracaoRecuperacao(), distRecov, fcRecov, 1, ritmoRecov));
                }
                expandiu = true;
                log.info("EXPANSÃO Fartlek [{}]: '{}' → {} acelerações ({} etapas)",
                        treino.tipoTreino(), etapa.descricaoEtapa(), fp.n(), fp.n() * 2);
                i++;
                continue;
            }

            // Nenhum padrão de compressão detectado
            resultado.add(etapa);
            i++;
        }

        if (!expandiu) return treino;
        return recalcularDuracaoTreino(treino, reordenarEtapas(resultado));
    }

    /** Retorna o próximo estágio se for RECUPERACAO, ou null caso contrário. */
    private EtapaTreinoLlmDto recuperacaoAdjacenteOu(List<EtapaTreinoLlmDto> etapas, int proximoIdx) {
        if (proximoIdx < etapas.size()
                && "RECUPERACAO".equalsIgnoreCase(etapas.get(proximoIdx).tipoEtapa())) {
            return etapas.get(proximoIdx);
        }
        return null;
    }

    /**
     * Extrai o número de repetições de padrões como "6x400m", "8 x 200", "5×1000m".
     * Retorna 1 se nenhum padrão for encontrado.
     */
    private int detectarRepeticoesNaDescricao(String descricao) {
        if (descricao == null || descricao.isBlank()) return 1;
        var matcher = REPETICOES_PATTERN.matcher(descricao);
        if (!matcher.find()) return 1;
        try {
            int n = Integer.parseInt(matcher.group(1));
            return (n >= 2 && n <= 20) ? n : 1; // sanity bounds
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * Extrai a distância individual a partir da descrição (ex: "6x400m" → 0.4km).
     * Se não encontrar, divide o total por N.
     */
    private double extrairDistanciaUnitariaDaDescricao(String descricao, Double totalKm, int n) {
        if (descricao != null) {
            var matcher = REPETICOES_PATTERN.matcher(descricao);
            if (matcher.find()) {
                try {
                    double dist = Double.parseDouble(matcher.group(2));
                    String unidade = matcher.group(3);
                    if (unidade == null || "m".equalsIgnoreCase(unidade)) {
                        dist = dist / 1000.0; // metros → km
                    }
                    if (dist > 0 && dist <= 5.0) {
                        return arredondar2(dist);
                    }
                } catch (NumberFormatException ignored) { /* fallback abaixo */ }
            }
        }
        return (totalKm != null && totalKm > 0) ? arredondar2(totalKm / n) : 0.0;
    }

    private double arredondar2(double valor) {
        return Math.round(valor * 100.0) / 100.0;
    }

    /**
     * Deriva distanciaKm para etapas time-based (AQUECIMENTO, DESAQUECIMENTO, RECUPERACAO)
     * via duracaoMin ÷ paceZona, substituindo o valor incorreto gerado pelo LLM
     * (que usa o pace de tiro em vez do pace fácil).
     *
     * Idempotent: YES · Side Effects: NONE · Tenant-aware: NO
     */
    private List<EtapaTreinoLlmDto> corrigirDistanciasEtapasTemporais(
            List<EtapaTreinoLlmDto> etapas, BigDecimal paceLimiar) {
        if (etapas == null || etapas.isEmpty()) return etapas;
        double paceZ2 = paceLimiar != null
                ? paceLimiar.doubleValue() * FATOR_PACE_Z2
                : PACE_Z2_DEFAULT_MIN_KM;
        double paceZ1 = paceLimiar != null
                ? paceLimiar.doubleValue() * FATOR_PACE_Z1
                : PACE_Z1_DEFAULT_MIN_KM;
        return etapas.stream()
                .map(e -> corrigirEtapaTemporal(e, paceZ1, paceZ2))
                .toList();
    }

    private EtapaTreinoLlmDto corrigirEtapaTemporal(EtapaTreinoLlmDto e, double paceZ1, double paceZ2) {
        if (e.tipoEtapa() == null) return e;
        if (e.duracaoMin() == null || e.duracaoMin() <= 0) return e;
        double pace = switch (e.tipoEtapa().toUpperCase()) {
            case "AQUECIMENTO", "DESAQUECIMENTO" -> paceZ2;
            case "RECUPERACAO" -> paceZ1;
            default -> -1.0;
        };
        if (pace <= 0) return e;
        double distancia = arredondar2(e.duracaoMin() / pace);
        return new EtapaTreinoLlmDto(
                e.ordem(), e.tipoEtapa(), e.descricaoEtapa(),
                e.duracaoMin(), distancia, e.fcAlvoEtapa(), e.repeticoes(), e.ritmoAlvo());
    }

    /** Tipos contínuos cuja etapa PRINCIPAL é tempo-baseada (distância = duração/pace). */
    private static final java.util.Set<String> TIPOS_CONTINUOS =
            java.util.Set.of("REGENERATIVO", "FACIL", "CONTINUO", "LONGO", "TEMPO_RUN");

    /**
     * Deriva a distância de um treino CONTÍNUO cujas etapas nasceram sem distância (ex.: REGENERATIVO
     * sintetizado pelo reparo estrutural / substituição por lesão). Para cada etapa tempo-baseada sem
     * distância, calcula {@code duração / paceZ2} — inclusive a PRINCIPAL, que
     * {@code corrigirEtapaTemporal} deliberadamente ignora (nos estruturados a PRINCIPAL é distância
     * fixa). Não age em INTERVALADO/TIRO/FARTLEK nem sobrescreve distância já válida.
     */
    private TreinoPlanejadoLlmDto garantirDistanciaContinuo(TreinoPlanejadoLlmDto treino, BigDecimal paceLimiar) {
        if (treino.tipoTreino() == null || !TIPOS_CONTINUOS.contains(treino.tipoTreino())) return treino;
        if (treino.distanciaKm() != null && treino.distanciaKm() > 0) return treino;
        if (treino.etapas() == null || treino.etapas().isEmpty()) return treino;
        double pace = paceLimiar != null ? paceLimiar.doubleValue() * FATOR_PACE_Z2 : PACE_Z2_DEFAULT_MIN_KM;
        List<EtapaTreinoLlmDto> etapas = treino.etapas().stream().map(e -> {
            if (e.duracaoMin() == null || e.duracaoMin() <= 0) return e;
            if (e.distanciaKm() != null && e.distanciaKm() > 0) return e;
            return new EtapaTreinoLlmDto(e.ordem(), e.tipoEtapa(), e.descricaoEtapa(),
                    e.duracaoMin(), arredondar2(e.duracaoMin() / pace), e.fcAlvoEtapa(), e.repeticoes(), e.ritmoAlvo());
        }).toList();
        double total = somarDistancias(etapas);
        if (total <= 0) return treino;
        return new TreinoPlanejadoLlmDto(
                treino.diaSemana(), treino.tipoTreino(), treino.fcAlvo(),
                treino.tssPlanejado(), treino.intensidadePlanejada(), treino.percepcaoEsforcoEsperada(),
                treino.justificativaIa(), treino.duracaoMin(), total, treino.ritmoAlvo(), etapas,
                treino.descricao(), treino.zonaAlvo(), treino.provaId());
    }

    /**
     * Detecta padrão "Nx (AccelMin ZoneA + RecovMin ZoneB)" em descrições de fartlek.
     * Exemplos: "4x (1min Z2 + 2min Z1)", "6 x (2min Z4 + 1min Z2)", "8x(3min+2min)".
     * Retorna null se o padrão não for encontrado.
     */
    private FartlekParams detectarFartlekNaDescricao(String descricao) {
        if (descricao == null || descricao.isBlank()) return null;
        var matcher = FARTLEK_TEMPO_PATTERN.matcher(descricao);
        if (!matcher.find()) return null;
        try {
            int n      = Integer.parseInt(matcher.group(1));
            int accel  = Integer.parseInt(matcher.group(2));
            int recov  = Integer.parseInt(matcher.group(3));
            if (n < 2 || n > 20 || accel < 1 || recov < 1) return null;

            // Tenta extrair zona da aceleração e recuperação do texto completo
            String zonaAccel = extrairZonaDaDescricao(descricao, matcher.start(2));
            String zonaRecov = extrairZonaDaDescricao(descricao, matcher.start(3));
            return new FartlekParams(n, accel, recov, zonaAccel, zonaRecov);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Procura a primeira ocorrência de "Z<N>" após a posição informada. */
    private String extrairZonaDaDescricao(String descricao, int aPartirDe) {
        var m = Pattern.compile("Z(\\d)(?:-Z(\\d))?", Pattern.CASE_INSENSITIVE)
                .matcher(descricao.substring(aPartirDe));
        return m.find() ? m.group(0).toUpperCase() : null;
    }

    /**
     * Converte índice de zona (0-based: 0=Z1 … 4=Z5) em string "fcMin-fcMax bpm".
     * Retorna null quando zonas é null ou o índice está fora do range.
     */
    private String bpmDaZona(List<ZonaFC> zonas, int index) {
        if (zonas == null || index < 0 || index >= zonas.size()) return null;
        ZonaFC z = zonas.get(index);
        return z.fcMin() + "-" + z.fcMax() + " bpm";
    }

    /**
     * Converte "Z1"–"Z5" em range de FC.
     * Quando {@code zonas} não é null, retorna o range absoluto em bpm (formato que
     * {@code parseFcRange} consegue validar). Sem zonas, cai no fallback de percentual FCmax.
     */
    private String zonaParaFc(String zona, List<ZonaFC> zonas) {
        if (zona == null) return null;
        String z = zona.trim().toUpperCase();
        if (zonas != null) {
            if (z.startsWith("Z5")) return bpmDaZona(zonas, 4);
            if (z.startsWith("Z4")) return bpmDaZona(zonas, 3);
            if (z.startsWith("Z3")) return bpmDaZona(zonas, 2);
            if (z.startsWith("Z2")) return bpmDaZona(zonas, 1);
            if (z.startsWith("Z1")) return bpmDaZona(zonas, 0);
        }
        if (z.startsWith("Z5")) return "90-95% FCmax";
        if (z.startsWith("Z4")) return "80-90% FCmax";
        if (z.startsWith("Z3")) return "70-80% FCmax";
        if (z.startsWith("Z2")) return "65-75% FCmax";
        if (z.startsWith("Z1")) return "60-70% FCmax";
        return null;
    }

    /**
     * Retorna novo record com duração recalculada e etapas atualizadas.
     */
    private TreinoPlanejadoLlmDto recalcularDuracaoTreino(TreinoPlanejadoLlmDto treino,
                                                           List<EtapaTreinoLlmDto> etapas) {
        int totalMin = somarDuracoesMin(etapas);
        String novaDuracao = String.format("%02d:00", totalMin);

        return new TreinoPlanejadoLlmDto(
                treino.diaSemana(),
                treino.tipoTreino(),
                treino.fcAlvo(),
                treino.tssPlanejado(),
                treino.intensidadePlanejada(),
                treino.percepcaoEsforcoEsperada(),
                treino.justificativaIa(),
                novaDuracao,
                treino.distanciaKm(),
                treino.ritmoAlvo(),
                etapas,
                treino.descricao(),
                treino.zonaAlvo(),
                treino.provaId()
        );
    }


    /**
     * Valida treino intervalado: mínimo 8 etapas, tiros e recuperações balanceados
     */
    private void validarTreinoIntervalado(br.com.menthoros.backend.dto.llm.TreinoPlanejadoLlmDto treino, Object atletaId) {
        var etapas = treino.etapas();

        // 1) Existência e quantidade mínima de etapas
        if (etapas == null || etapas.isEmpty()) {
            log.error("VALIDAÇÃO FALHOU [Atleta {}]: Treino {} sem etapas",
                    atletaId, treino.tipoTreino());
            throw new LLMException(String.format(
                    "Treino %s inválido: não foram geradas etapas",
                    treino.tipoTreino()
            ));
        }

        if (etapas.size() < 6) {
            log.error("VALIDAÇÃO FALHOU [Atleta {}]: Treino {} tem apenas {} etapas (mínimo 8)",
                    atletaId, treino.tipoTreino(), etapas.size());
            throw new LLMException(String.format(
                    "Treino %s inválido: gerou apenas %d etapas (mínimo 6 para intervalados)",
                    treino.tipoTreino(), etapas.size()
            ));
        }

        // 2) Tipos básicos de etapa
        boolean temAquecimento = etapas.stream()
                .anyMatch(e -> "AQUECIMENTO".equals(e.tipoEtapa()));
        boolean temDesaquecimento = etapas.stream()
                .anyMatch(e -> "DESAQUECIMENTO".equals(e.tipoEtapa()));

        if (!temAquecimento) {
            log.error("VALIDAÇÃO FALHOU [Atleta {}]: Treino {} não possui etapa de aquecimento",
                    atletaId, treino.tipoTreino());
            throw new LLMException(String.format(
                    "Treino %s inválido: não possui etapa de aquecimento",
                    treino.tipoTreino()
            ));
        }

        if (!temDesaquecimento) {
            log.error("VALIDAÇÃO FALHOU [Atleta {}]: Treino {} não possui etapa de desaquecimento",
                    atletaId, treino.tipoTreino());
            throw new LLMException(String.format(
                    "Treino %s inválido: não possui etapa de desaquecimento",
                    treino.tipoTreino()
            ));
        }

        // 3) Ordem lógica: primeiro AQUECIMENTO, último DESAQUECIMENTO
        var primeiraEtapa = etapas.get(0);
        var ultimaEtapa   = etapas.get(etapas.size() - 1);

        if (!"AQUECIMENTO".equals(primeiraEtapa.tipoEtapa())) {
            log.error("VALIDAÇÃO FALHOU [Atleta {}]: Treino {} não inicia com aquecimento (inicia com {})",
                    atletaId, treino.tipoTreino(), primeiraEtapa.tipoEtapa());
            throw new LLMException(String.format(
                    "Treino %s inválido: deve iniciar com aquecimento",
                    treino.tipoTreino()
            ));
        }

        if (!"DESAQUECIMENTO".equals(ultimaEtapa.tipoEtapa())) {
            log.error("VALIDAÇÃO FALHOU [Atleta {}]: Treino {} não termina com desaquecimento (termina com {})",
                    atletaId, treino.tipoTreino(), ultimaEtapa.tipoEtapa());
            throw new LLMException(String.format(
                    "Treino %s inválido: deve terminar com desaquecimento",
                    treino.tipoTreino()
            ));
        }

        // 4) Contar tiros e recuperações
        long numTiros = etapas.stream()
                .filter(e -> "INTERVALADO".equals(e.tipoEtapa()))
                .count();
        long numRecuperacoes = etapas.stream()
                .filter(e -> "RECUPERACAO".equals(e.tipoEtapa()))
                .count();

        if (numTiros < 3) {
            log.warn("VALIDAÇÃO ALERTA [Atleta {}]: Treino {} tem apenas {} tiros (recomendado: 3+)",
                    atletaId, treino.tipoTreino(), numTiros);
        }

        // Balanceamento tiros x recuperações (pode ter 1 rec a menos se o último tiro não tiver rec)
        if (Math.abs(numTiros - numRecuperacoes) > 1) {
            log.error("VALIDAÇÃO FALHOU [Atleta {}]: Treino {} desbalanceado: {} tiros vs {} recuperações",
                    atletaId, treino.tipoTreino(), numTiros, numRecuperacoes);
            throw new LLMException(String.format(
                    "Treino %s inválido: %d tiros mas %d recuperações (devem ser iguais ou diferença de 1)",
                    treino.tipoTreino(), numTiros, numRecuperacoes
            ));
        }

        // 5) Validar sequência: recuperação só pode vir após tiro
        boolean ultimoFoiTiro = false;
        boolean jaTeveTiro = false;
        int recuperacoesInvalidas = 0;

        for (var etapa : etapas) {
            String tipo = etapa.tipoEtapa();

            if ("INTERVALADO".equals(tipo)) {
                jaTeveTiro = true;
                ultimoFoiTiro = true;
            } else if ("RECUPERACAO".equals(tipo)) {
                if (!ultimoFoiTiro) {
                    recuperacoesInvalidas++;
                }
                ultimoFoiTiro = false;
            } else if ("AQUECIMENTO".equals(tipo)) {
                // Se aquecimento for gerado depois de tiro, é estranho (mas vamos só logar)
                if (jaTeveTiro) {
                    log.warn("VALIDAÇÃO ALERTA [Atleta {}]: Aquecimento após tiro detectado em treino {}",
                            atletaId, treino.tipoTreino());
                }
                ultimoFoiTiro = false;
            } else if ("DESAQUECIMENTO".equals(tipo)) {
                // Desaquecimento antes de qualquer tiro também é estranho
                if (!jaTeveTiro) {
                    log.warn("VALIDAÇÃO ALERTA [Atleta {}]: Desaquecimento antes de qualquer tiro em treino {}",
                            atletaId, treino.tipoTreino());
                }
                ultimoFoiTiro = false;
            } else {
                // Outros tipos, se existirem
                ultimoFoiTiro = false;
            }
        }

        if (recuperacoesInvalidas > 0) {
            log.error("VALIDAÇÃO FALHOU [Atleta {}]: Treino {} possui {} recuperações sem tiro anterior",
                    atletaId, treino.tipoTreino(), recuperacoesInvalidas);
            throw new LLMException(String.format(
                    "Treino %s inválido: existem recuperações sem tiro imediatamente anterior",
                    treino.tipoTreino()
            ));
        }

        // 6) Distâncias: soma total e proporções
        double somaDistancias = etapas.stream()
                .mapToDouble(e -> e.distanciaKm() != null ? e.distanciaKm() : 0.0)
                .sum();
        double distanciaPlanejada = treino.distanciaKm() != null ? treino.distanciaKm() : 0.0;

        double diferenca = Math.abs(somaDistancias - distanciaPlanejada);
        if (diferenca > 0.5) { // Tolerância de 500m
            log.warn("VALIDAÇÃO ALERTA [Atleta {}]: Soma das etapas ({} km) difere da distância planejada ({} km) em {} km",
                    atletaId, somaDistancias, distanciaPlanejada, diferenca);
        }

        double distanciaTiros = etapas.stream()
                .filter(e -> "INTERVALADO".equals(e.tipoEtapa()))
                .mapToDouble(e -> e.distanciaKm() != null ? e.distanciaKm() : 0.0)
                .sum();

        double distanciaRecuperacoes = etapas.stream()
                .filter(e -> "RECUPERACAO".equals(e.tipoEtapa()))
                .mapToDouble(e -> e.distanciaKm() != null ? e.distanciaKm() : 0.0)
                .sum();

        if (distanciaPlanejada > 0.0) {
            // Pelo menos 20% da distância em tiros (garante estímulo mínimo)
            if (distanciaTiros < distanciaPlanejada * 0.20) {
                log.warn("VALIDAÇÃO ALERTA [Atleta {}]: Distância em tiros ({}) muito baixa para total de {} km no treino {}",
                        atletaId, distanciaTiros, distanciaPlanejada, treino.tipoTreino());
            }

            // Recuperação não deve ser a maior parte do treino
            if (distanciaRecuperacoes > distanciaPlanejada * 0.65) {
                log.warn("VALIDAÇÃO ALERTA [Atleta {}]: Distância em recuperação ({}) muito alta para total de {} km no treino {}",
                        atletaId, distanciaRecuperacoes, distanciaPlanejada, treino.tipoTreino());
            }
        }

        // 7) Duração dos tiros (coerência fisiológica geral)
        long tirosInvalidos = etapas.stream()
                .filter(e -> "INTERVALADO".equals(e.tipoEtapa()))
                .filter(e -> {
                    if (e.duracaoMin() == null) return true;
                    double duracao = e.duracaoMin();
                    // mínimo ~18s (0.3 min) e máximo 10 min
                    return duracao < 0.3 || duracao > 10.0;
                })
                .count();

        if (tirosInvalidos > 0) {
            log.error("VALIDAÇÃO FALHOU [Atleta {}]: Treino {} possui {} tiros com duração incoerente",
                    atletaId, treino.tipoTreino(), tirosInvalidos);
            throw new LLMException(String.format(
                    "Treino %s inválido: existem tiros com duração incoerente (muito curtos ou muito longos)",
                    treino.tipoTreino()
            ));
        }

        // 8) Log final de sucesso
        log.info("VALIDAÇÃO OK [Atleta {}]: Treino {} - {} etapas ({} tiros, {} recuperações, {} km - tiros: {} km, rec: {} km)",
                atletaId,
                treino.tipoTreino(),
                etapas.size(),
                numTiros,
                numRecuperacoes,
                somaDistancias,
                distanciaTiros,
                distanciaRecuperacoes
        );
    }

    /**
     * Validação estrutural compartilhada de treinos "3 etapas" (AQUECIMENTO → PRINCIPAL → DESAQUECIMENTO).
     * Hard-fail (lança {@link LLMException}) em: número de etapas ≠ 3 e — quando {@code validarOrdem} —
     * AQUECIMENTO/DESAQUECIMENTO fora de posição. Unifica REGENERATIVO/CONTINUO/TEMPO_RUN/LONGO
     * (LONGO valida só a contagem, por isso o flag). As regras não mudam — só deixam de ser copiadas.
     */
    void validarEstrutura3Etapas(TreinoPlanejadoLlmDto treino, String tipo, Object atletaId, boolean validarOrdem) {
        var etapas = treino.etapas();
        if (etapas == null || etapas.size() != 3) {
            log.error("VALIDAÇÃO FALHOU [Atleta {}]: Treino {} tem {} etapas (esperado: 3)",
                    atletaId, tipo, etapas != null ? etapas.size() : 0);
            contarViolacaoEstrutural(tipo);
            throw new LLMException(String.format(
                    "Treino %s inválido: gerou %d etapas (esperado 3: aquec, principal, desaq)",
                    tipo, etapas != null ? etapas.size() : 0));
        }
        if (validarOrdem
                && (!"AQUECIMENTO".equals(etapas.get(0).tipoEtapa()) || !"DESAQUECIMENTO".equals(etapas.get(2).tipoEtapa()))) {
            log.error("VALIDAÇÃO FALHOU [Atleta {}]: Treino {} fora de ordem: [{}→{}→{}]",
                    atletaId, tipo, etapas.get(0).tipoEtapa(), etapas.get(1).tipoEtapa(), etapas.get(2).tipoEtapa());
            contarViolacaoEstrutural(tipo);
            throw new LLMException(String.format(
                    "Treino %s inválido: deve ser AQUECIMENTO → PRINCIPAL → DESAQUECIMENTO", tipo));
        }
    }

    /** Telemetria: violação estrutural residual (não reparada) por tipo de treino. */
    private void contarViolacaoEstrutural(String tipo) {
        Counter.builder("plano_violacao_estrutural").tag("tipo", tipo).register(meterRegistry).increment();
    }

    private void validarTreinoLongo(br.com.menthoros.backend.dto.llm.TreinoPlanejadoLlmDto treino, Object atletaId) {
        validarEstrutura3Etapas(treino, "LONGO", atletaId, false);
        log.info("VALIDAÇÃO OK [Atleta {}]: Treino LONGO - 3 etapas conforme esperado", atletaId);
    }

    /**
     * Valida que todas as etapas têm repeticoes = 1
     */
    private void validarRepeticoes(br.com.menthoros.backend.dto.llm.TreinoPlanejadoLlmDto treino, Object atletaId) {
        var etapas = treino.etapas();
        if (etapas == null) return;

        etapas.forEach(etapa -> {
            if (etapa.repeticoes() != null && etapa.repeticoes() != 1) {
                log.error("VALIDAÇÃO FALHOU [Atleta {}]: Etapa '{}' tem repeticoes={} (deve ser sempre 1)",
                        atletaId, etapa.descricaoEtapa(), etapa.repeticoes());
                throw new LLMException(String.format(
                        "Etapa '%s' inválida: repeticoes=%d (deve ser sempre 1 - expandir etapas individualmente)",
                        etapa.descricaoEtapa(), etapa.repeticoes()
                ));
            }
        });
    }

    // ======================== P2-B — TRIÂNGULO pace × distância × duração ========================

    /**
     * Valida a consistência entre ritmoAlvo, distanciaKm e duracaoMin (identidade física).
     * Se desvio > 20%, registra WARN. Não corrige: os três valores são prescrições do LLM e
     * nenhum deles tem precedência clara sobre os outros.
     */
    private void validarTrianguloPaceDuracaoDistancia(TreinoPlanejadoLlmDto treino) {
        if (treino.ritmoAlvo() == null || treino.distanciaKm() == null || treino.duracaoMin() == null) return;

        var paceMediaOpt = paceValidator.calcularPaceMedia(treino.ritmoAlvo());
        if (paceMediaOpt.isEmpty()) return;

        double distanciaKm = treino.distanciaKm();
        if (distanciaKm <= 0) return;

        var mDuracao = java.util.regex.Pattern.compile("^(\\d{1,3}):(\\d{2})$").matcher(treino.duracaoMin().trim());
        if (!mDuracao.matches()) return;
        double duracaoMin;
        try {
            duracaoMin = Integer.parseInt(mDuracao.group(1)) + Integer.parseInt(mDuracao.group(2)) / 60.0;
        } catch (NumberFormatException e) {
            return;
        }
        if (duracaoMin <= 0) return;

        double paceMedia = paceMediaOpt.getAsDouble();
        double duracaoEsperada = paceMedia * distanciaKm;
        double desvio = Math.abs(duracaoEsperada - duracaoMin) / duracaoEsperada;

        if (desvio > 0.20) {
            log.warn("TRIÂNGULO pace×dist×dur [{}]: ritmoAlvo='{}', dist={} km, duracao={} min → esperado {} min (desvio {}%)",
                    treino.tipoTreino(), treino.ritmoAlvo(), distanciaKm, duracaoMin,
                    String.format("%.1f", duracaoEsperada), String.format("%.0f", desvio * 100));
        }
    }

    // ======================== P3-A — VALIDAÇÃO ESTRUTURAL POR TIPO ========================

    /**
     * Valida treino REGENERATIVO: 3 etapas (AQUECIMENTO → PRINCIPAL → DESAQUECIMENTO),
     * duração 20–45 min.
     */
    private void validarTreinoRegenerativo(TreinoPlanejadoLlmDto treino, Object atletaId) {
        validarEstrutura3Etapas(treino, "REGENERATIVO", atletaId, true);

        if (treino.duracaoMin() != null) {
            var m = java.util.regex.Pattern.compile("^(\\d{1,3}):(\\d{2})$").matcher(treino.duracaoMin().trim());
            if (m.matches()) {
                try {
                    int minutos = Integer.parseInt(m.group(1));
                    if (minutos > 45) {
                        log.warn("VALIDAÇÃO ALERTA [Atleta {}]: Treino REGENERATIVO com {} min (máximo recomendado: 45 min)",
                                atletaId, minutos);
                    } else if (minutos < 20) {
                        log.warn("VALIDAÇÃO ALERTA [Atleta {}]: Treino REGENERATIVO com {} min (mínimo recomendado: 20 min)",
                                atletaId, minutos);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        log.info("VALIDAÇÃO OK [Atleta {}]: Treino REGENERATIVO - 3 etapas conforme esperado", atletaId);
    }

    /**
     * Valida treino CONTINUO: 3 etapas (AQUECIMENTO → PRINCIPAL → DESAQUECIMENTO),
     * distância mínima de 5 km.
     */
    private void validarTreinoContinuo(TreinoPlanejadoLlmDto treino, Object atletaId) {
        validarEstrutura3Etapas(treino, "CONTINUO", atletaId, true);

        if (treino.distanciaKm() != null && treino.distanciaKm() < 5.0) {
            log.warn("VALIDAÇÃO ALERTA [Atleta {}]: Treino CONTINUO com {} km (mínimo recomendado: 5 km)",
                    atletaId, treino.distanciaKm());
        }

        log.info("VALIDAÇÃO OK [Atleta {}]: Treino CONTINUO - 3 etapas conforme esperado", atletaId);
    }

    /**
     * Valida treino TEMPO_RUN: 3 etapas (AQUECIMENTO → PRINCIPAL → DESAQUECIMENTO),
     * PRINCIPAL mínimo 15 min, ritmoAlvo do PRINCIPAL dentro de ±10% do paceLimiar do atleta.
     */
    private void validarTreinoTempoRun(TreinoPlanejadoLlmDto treino, Object atletaId,
                                        br.com.menthoros.backend.entity.Atleta atleta) {
        validarEstrutura3Etapas(treino, "TEMPO_RUN", atletaId, true);

        var etapaPrincipal = treino.etapas().get(1);

        if (etapaPrincipal.duracaoMin() != null && etapaPrincipal.duracaoMin() < 15) {
            log.warn("VALIDAÇÃO ALERTA [Atleta {}]: TEMPO_RUN principal com {} min (mínimo para indução de limiar: 15 min)",
                    atletaId, etapaPrincipal.duracaoMin());
        }

        if (atleta.getPaceLimiar() != null && etapaPrincipal.ritmoAlvo() != null) {
            var paceMediaOpt = paceValidator.calcularPaceMedia(etapaPrincipal.ritmoAlvo());
            if (paceMediaOpt.isPresent()) {
                double paceMedia = paceMediaOpt.getAsDouble();
                double limiar = atleta.getPaceLimiar().doubleValue();
                double tolerancia = limiar * 0.10;
                if (paceMedia < limiar - tolerancia || paceMedia > limiar + tolerancia) {
                    log.warn("VALIDAÇÃO ALERTA [Atleta {}]: TEMPO_RUN principal ritmoAlvo='{}' (média={} min/km) fora da faixa limiar ±10% [{}-{} min/km]",
                            atletaId, etapaPrincipal.ritmoAlvo(),
                            String.format("%.2f", paceMedia),
                            String.format("%.2f", limiar - tolerancia),
                            String.format("%.2f", limiar + tolerancia));
                }
            }
        }

        log.info("VALIDAÇÃO OK [Atleta {}]: Treino TEMPO_RUN - 3 etapas conforme esperado", atletaId);
    }

    // ======================== P3-B — DISTRIBUIÇÃO DE CARGA SEMANAL ========================

    /**
     * Verifica se existem treinos "duros" (INTERVALADO, TIRO, TEMPO_RUN) em dias consecutivos
     * e registra WARN. Não rejeita o plano — apenas alerta.
     */
    private void validarDistribuicaoCargaSemanal(List<TreinoPlanejadoLlmDto> treinos) {
        if (treinos == null || treinos.size() < 2) return;

        java.util.Set<String> tiposDuros = java.util.Set.of("INTERVALADO", "TIRO", "TEMPO_RUN", "LONGO");

        java.util.Map<Integer, String> ordemParaTipo = new java.util.TreeMap<>();
        for (TreinoPlanejadoLlmDto treino : treinos) {
            if (treino.diaSemana() == null || treino.tipoTreino() == null) continue;
            try {
                DiaSemana dia = DiaSemana.valueOf(treino.diaSemana().toUpperCase());
                ordemParaTipo.put(dia.getOrder(), treino.tipoTreino());
            } catch (IllegalArgumentException ignored) {}
        }

        List<java.util.Map.Entry<Integer, String>> entradas = new java.util.ArrayList<>(ordemParaTipo.entrySet());
        for (int i = 0; i < entradas.size() - 1; i++) {
            var atual = entradas.get(i);
            var proximo = entradas.get(i + 1);
            if ((proximo.getKey() - atual.getKey()) == 1
                    && tiposDuros.contains(atual.getValue())
                    && tiposDuros.contains(proximo.getValue())) {
                DiaSemana diaAtual   = diaPorOrdem(atual.getKey());
                DiaSemana diaProximo = diaPorOrdem(proximo.getKey());
                log.warn("CARGA SEMANAL: treinos duros em dias consecutivos — {} ({}) e {} ({})",
                        diaAtual   != null ? diaAtual.getLabel()   : atual.getKey(),   atual.getValue(),
                        diaProximo != null ? diaProximo.getLabel() : proximo.getKey(), proximo.getValue());
            }
        }
    }

    private DiaSemana diaPorOrdem(int order) {
        for (DiaSemana d : DiaSemana.values()) {
            if (d.getOrder() == order) return d;
        }
        return null;
    }

    @Override
    public Map<Long, PlanoTreinoOutputDto> gerarPlanosEmLote(Map<AtletaOutputDto, List<TreinoRealizadoOutputDto>> atletaDtoListMap) {
        log.warn("Método gerarPlanosEmLote ainda não implementado");
        return Map.of();
    }

}
