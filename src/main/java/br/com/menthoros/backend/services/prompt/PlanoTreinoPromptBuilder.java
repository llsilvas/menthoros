package br.com.menthoros.backend.services.prompt;

import br.com.menthoros.backend.dto.DecisaoProgressao;
import br.com.menthoros.backend.dto.output.AtletaOutputDto;
import br.com.menthoros.backend.dto.output.PlanoSemanalOutputDto;
import br.com.menthoros.backend.dto.output.ProvaOutputDto;
import br.com.menthoros.backend.dto.output.TreinoRealizadoOutputDto;
import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.entity.PlanoMetaDados;
import br.com.menthoros.backend.entity.Prova;
import br.com.menthoros.backend.entity.RevisaoSemanal;
import br.com.menthoros.backend.entity.TreinoRealizado;
import br.com.menthoros.backend.domain.planner.SessionSlot;
import br.com.menthoros.backend.domain.planner.WeekPlanSkeleton;
import java.time.DayOfWeek;
import br.com.menthoros.backend.enums.DiaSemana;
import br.com.menthoros.backend.enums.TipoTreino;
import br.com.menthoros.backend.services.helper.IntervaladoElegibilidadeService;
import br.com.menthoros.backend.services.helper.PaceZoneCalculator;
import br.com.menthoros.backend.services.helper.RecomendacaoIntervalado;
import br.com.menthoros.backend.services.helper.TreinoHistoricoProvider;
import br.com.menthoros.backend.services.helper.TreinoHistoricoProvider.ContextoTreino;
import br.com.menthoros.backend.services.helper.ZonaTreinoService;
import br.com.menthoros.backend.services.helper.ZonaTreinoService.ZonaCompleta;
import br.com.menthoros.backend.services.impl.MetricasAlertaService;
import br.com.menthoros.backend.services.prompt.constraint.Constraint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

@Component
public class PlanoTreinoPromptBuilder {

    private final String promptTemplate;
    private final PromptTemplateLoader templateLoader;
    private final MetricasAlertaService metricasAlertaService;
    private final ZonaTreinoService zonaTreinoService;
    private final TreinoHistoricoProvider treinoHistoricoProvider;
    private final MetricasPromptFormatter metricasPromptFormatter;
    private final AlertasPromptFormatter alertasPromptFormatter;
    private final RecuperacaoPromptFormatter recuperacaoPromptFormatter;
    private final PeriodizacaoPromptFormatter periodizacaoPromptFormatter;
    private final VariabilidadePromptFormatter variabilidadePromptFormatter;
    private final DisponibilidadePromptFormatter disponibilidadePromptFormatter;
    private final IntervaladoElegibilidadeService intervaladoElegibilidadeService;
    private final PaceHistoricoFormatter paceHistoricoFormatter;
    private final PaceZoneCalculator paceZoneCalculator;
    private final ThresholdConstraintFormatter thresholdConstraintFormatter;
    private final ReadinessPromptFormatter readinessPromptFormatter;
    private final WeeklyReviewPromptFormatter weeklyReviewPromptFormatter;

    public PlanoTreinoPromptBuilder(@Value("classpath:prompts/plano-treino-prompt.txt") Resource promptResource,
                                    PromptTemplateLoader templateLoader,
                                    MetricasAlertaService metricasAlertaService,
                                    ZonaTreinoService zonaTreinoService,
                                    TreinoHistoricoProvider treinoHistoricoProvider,
                                    MetricasPromptFormatter metricasPromptFormatter,
                                    AlertasPromptFormatter alertasPromptFormatter,
                                    RecuperacaoPromptFormatter recuperacaoPromptFormatter,
                                    PeriodizacaoPromptFormatter periodizacaoPromptFormatter,
                                    VariabilidadePromptFormatter variabilidadePromptFormatter,
                                    DisponibilidadePromptFormatter disponibilidadePromptFormatter,
                                    IntervaladoElegibilidadeService intervaladoElegibilidadeService,
                                    PaceHistoricoFormatter paceHistoricoFormatter,
                                    PaceZoneCalculator paceZoneCalculator,
                                    ThresholdConstraintFormatter thresholdConstraintFormatter,
                                    ReadinessPromptFormatter readinessPromptFormatter,
                                    WeeklyReviewPromptFormatter weeklyReviewPromptFormatter) {
        this.templateLoader = templateLoader;
        this.metricasAlertaService = metricasAlertaService;
        this.zonaTreinoService = zonaTreinoService;
        this.treinoHistoricoProvider = treinoHistoricoProvider;
        this.metricasPromptFormatter = metricasPromptFormatter;
        this.alertasPromptFormatter = alertasPromptFormatter;
        this.recuperacaoPromptFormatter = recuperacaoPromptFormatter;
        this.periodizacaoPromptFormatter = periodizacaoPromptFormatter;
        this.variabilidadePromptFormatter = variabilidadePromptFormatter;
        this.disponibilidadePromptFormatter = disponibilidadePromptFormatter;
        this.intervaladoElegibilidadeService = intervaladoElegibilidadeService;
        this.paceHistoricoFormatter = paceHistoricoFormatter;
        this.paceZoneCalculator = paceZoneCalculator;
        this.thresholdConstraintFormatter = thresholdConstraintFormatter;
        this.readinessPromptFormatter = readinessPromptFormatter;
        this.weeklyReviewPromptFormatter = weeklyReviewPromptFormatter;
        try {
            this.promptTemplate = new String(promptResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Não foi possível carregar o template do prompt.", e);
        }
    }

    // ======================== MÉTODO LEGADO (DTO-based) ========================

    public String buildRequest(AtletaOutputDto atleta, List<TreinoRealizadoOutputDto> treinosRecentes, PlanoSemanalOutputDto planoSemanalOutputDto) {
        return String.format(promptTemplate,
                atleta.nome(),
                atleta.idade(),
                atleta.objetivo(),
                atleta.nivelExperiencia(),
                atleta.diasDisponiveis(),
                atleta.diaPreferidoLongo(),
                formatarProvas(atleta.provas()),
                formatarTreinos(treinosRecentes),
                formatarHistorico(planoSemanalOutputDto)
        );
    }

    private String formatarHistorico(PlanoSemanalOutputDto planoSemanalOutputDto) {
        if (planoSemanalOutputDto == null) {
            return "### Volume da semana anterior:\n- Nenhum plano concluído encontrado.\n";
        }

        Double volumeAlvo = planoSemanalOutputDto.volumeAlvoKm();
        Double volumePlanejado = planoSemanalOutputDto.volumePlanejadoKm();
        Double volumeRealizado = planoSemanalOutputDto.volumeRealizadoKm();

        return String.format("""
                        ### Volume da semana anterior:
                        - volume alvo: %.1f km
                        - volume planejado: %.1f km
                        - volume realizado: %.1f km
                        """,
                volumeAlvo != null ? volumeAlvo : 0.0,
                volumePlanejado != null ? volumePlanejado : 0.0,
                volumeRealizado != null ? volumeRealizado : 0.0
        );
    }

    private String formatarProvas(List<ProvaOutputDto> provas) {
        if (provas == null || provas.isEmpty()) {
            return "Nenhuma prova cadastrada";
        }

        return provas.stream()
                .map(p -> String.format("- %s em %s (%.1f km)",
                        p.nomeProva(),
                        p.dataProva().toString(),
                        p.distanciaKm().doubleValue())).collect(Collectors.joining("\n"));
    }

    private String formatarTreinos(List<TreinoRealizadoOutputDto> treinos) {
        if (treinos == null || treinos.isEmpty()) {
            return "Nenhum treino recente encontrado.";
        }

        return treinos.stream()
                .map(t -> String.format("- %s\n Tipo: %s\n Distância: %.1f km | Duração: %d min | Ritmo alvo: %s | FC alvo: %s\n Observações: %s\n",
                        t.dataTreino().toString(),
                        t.tipoTreino(),
                        t.distanciaKm(),
                        t.duracaoMin(),
                        t.paceMedia() != null ? t.paceMedia() : "N/A",
                        t.fcMedia() != null ? t.fcMedia() : "N/A",
                        t.observacao() != null ? t.observacao() : "Sem observações")
                )
                .collect(Collectors.joining("\n"));
    }


    // ======================== MÉTODO OTIMIZADO (principal) ========================

    public PromptGerado buildOptimizedPrompt(Atleta atleta, PlanoMetaDados metaDados, Prova provaAlvo,
                                             LocalDate inicioSemana, List<DiaSemana> diasEfetivos) {
        return buildOptimizedPrompt(atleta, metaDados, provaAlvo, inicioSemana, diasEfetivos, null, null, null);
    }

    public PromptGerado buildOptimizedPrompt(Atleta atleta, PlanoMetaDados metaDados, Prova provaAlvo,
                                             LocalDate inicioSemana, List<DiaSemana> diasEfetivos,
                                             @Nullable DecisaoProgressao decisaoProgressao) {
        return buildOptimizedPrompt(atleta, metaDados, provaAlvo, inicioSemana, diasEfetivos, decisaoProgressao, null, null);
    }

    public PromptGerado buildOptimizedPrompt(Atleta atleta, PlanoMetaDados metaDados, Prova provaAlvo,
                                             LocalDate inicioSemana, List<DiaSemana> diasEfetivos,
                                             @Nullable DecisaoProgressao decisaoProgressao,
                                             @Nullable RevisaoSemanal revisaoConsumida) {
        return buildOptimizedPrompt(atleta, metaDados, provaAlvo, inicioSemana, diasEfetivos, decisaoProgressao, revisaoConsumida, null);
    }

    /**
     * @param revisaoConsumida revisão da semana anterior já resolvida pelo chamador
     *        ({@code PlanoServiceImpl}) — o builder não a resolve por conta própria para que a
     *        revisão vista pelo LLM e a gravada como consumida no plano sejam sempre a mesma.
     */
    public PromptGerado buildOptimizedPrompt(Atleta atleta, PlanoMetaDados metaDados, Prova provaAlvo,
                                             LocalDate inicioSemana, List<DiaSemana> diasEfetivos,
                                             @Nullable DecisaoProgressao decisaoProgressao,
                                             @Nullable RevisaoSemanal revisaoConsumida,
                                             @Nullable WeekPlanSkeleton skeleton) {
        var ctx = treinoHistoricoProvider.prepararContexto(atleta);

        // DECISÃO INTERVALADO — avaliação determinística pré-LLM (5 portões fisiológicos + readiness)
        RecomendacaoIntervalado recomIntervalado = intervaladoElegibilidadeService.avaliar(
                atleta, metaDados, ctx.treinosUltimas4Semanas(), ctx.dataReferencia(), ctx.nivelProntidaoHoje());

        // 1. Dados básicos do atleta

        String provas = null;
        if(provaAlvo != null) {
            provas = periodizacaoPromptFormatter.formatarProvas(provaAlvo, ctx.provasPreparatorias());
        }
        // 2. ALERTAS OBRIGATÓRIOS NO TOPO (prioridade máxima)
        int maxDiasConsecutivos = disponibilidadePromptFormatter.calcularMaxDiasConsecutivos(metaDados, atleta);
        String alertasObrigatorios = alertasPromptFormatter.gerarAlertasObrigatorios(
                atleta, metaDados, maxDiasConsecutivos, ctx.treinosUltimas4Semanas(), ctx.dataReferencia());

        // 3. HIERARQUIA DE DECISÃO (para resolver conflitos)
        String hierarquiaDecisao = alertasPromptFormatter.gerarHierarquiaDecisao(metaDados, atleta);

        // 4. EVENTO COMPETITIVO NA SEMANA (regra mandatória baseada na semana planejada)
        String eventoCompetitivoSemana = periodizacaoPromptFormatter.formatarEventoCompetitivoSemana(
                provaAlvo, ctx.provasPreparatorias(), inicioSemana);

        // 5. RESTRIÇÕES E LESÕES
        String restricoesLesoes = alertasPromptFormatter.formatarRestricoesLesoes(atleta, ctx.dataReferencia());

        // 6. VALIDAÇÃO E FALLBACKS DE DADOS
        String fallbacksDados = validarEFallbacksDadosFisiologicos(atleta);

        // 7. Análises de pré-planejamento
        var analiseEstimulos = variabilidadePromptFormatter.analisarEstimulosRecentes(
                ctx.treinosUltimas4Semanas(), ctx.dataReferencia());
        var volumeUltimas3Semanas = variabilidadePromptFormatter.calcularVolumeMedioUltimasTresSemanas(
                ctx.treinosUltimas3Semanas(), ctx.dataReferencia());
        var matrizVariabilidade = variabilidadePromptFormatter.identificarMatrizVariabilidade(
                ctx.treinosUltimas4Semanas(), ctx.dataReferencia());
        var alertasVariabilidade = variabilidadePromptFormatter.gerarAlertasVariabilidade(
                ctx.treinosUltimas4Semanas(), ctx.dataReferencia());
        var instrucoesRecuperacao = recuperacaoPromptFormatter.detalharRecuperacao(
                atleta, metaDados, ctx.treinosUltimaSemana());

        // 7. Compilar contexto histórico consolidado (sem repetição)
        StringBuilder historicoCompleto = new StringBuilder();

        // ETAPA 1: Dados fisiológicos e zonas (com fallbacks se necessário)
        historicoCompleto.append("## 📊 DADOS FISIOLÓGICOS E ZONAS DE TREINO\n\n");
        historicoCompleto.append(formatarDadosFisiologicos(atleta)).append("\n\n");
        if (!fallbacksDados.isEmpty()) {
            historicoCompleto.append(fallbacksDados);
        }

        // Ajuste de pace por TSB (Fase 2): nota explícita ao LLM sobre penalidade de fadiga
        String avisoTsb = paceZoneCalculator.formatarAvisoAjuste(metaDados != null ? metaDados.getTsbAtual() : null);
        if (!avisoTsb.isEmpty()) {
            historicoCompleto.append(avisoTsb).append("\n");
        }

        // ETAPA 1.5: Readiness (prontidão subjetiva diária) — sequência 7 dias + hoje
        historicoCompleto.append(readinessPromptFormatter.formatarReadiness(
                ctx.sequenciaUltimos7Dias(), ctx.nivelProntidaoHoje(), ctx.readinessScoreHoje())).append("\n\n");

        // ETAPA 1.6: Revisão da semana anterior — contexto, nunca comando (CA4/CA5). Bloco vazio
        // quando não há revisão consumível (ausente, fora da janela D11 ou injeção desligada);
        // a resolução é do chamador, ponto único que também grava o vínculo no plano.
        String blocoRevisao = weeklyReviewPromptFormatter.formatarRevisao(revisaoConsumida);
        if (!blocoRevisao.isEmpty()) {
            historicoCompleto.append(blocoRevisao).append("\n\n");
        }

        // ETAPA 2: Métricas de carga e fadiga (consolidadas)
        historicoCompleto.append(metricasPromptFormatter.formatarMetricas(metaDados)).append("\n\n");

        // ETAPA 3: Histórico recente de treinos
        historicoCompleto.append(formatarHistoricoTreinos(ctx.treinosUltimos14Dias())).append("\n\n");

        // ETAPA 3.5: Pace demonstrado por tipo nas últimas 4 semanas (Fase 1)
        historicoCompleto.append(paceHistoricoFormatter.formatarHistoricoPace(ctx.treinosUltimas4Semanas())).append("\n\n");

        // ETAPA 3.6: Teto de pace por tipo — calculado aqui, mas renderizado como Constraint no
        // bloco mandatório do topo (não mais disperso nesta posição).
        Map<TipoTreino, BigDecimal> tetoPorTipo = paceHistoricoFormatter.calcularTetoPorTipo(ctx.treinosUltimas4Semanas());

        // ETAPA 3.7: Aviso de paceLimiar desatualizado (Fase 5)
        String avisoPaceLimiar = paceHistoricoFormatter.verificarPaceLimiarAtualizado(atleta);
        if (!avisoPaceLimiar.isEmpty()) {
            historicoCompleto.append(avisoPaceLimiar).append("\n\n");
        }

        // ETAPA 4: Disponibilidade e padrões de treino
        historicoCompleto.append(disponibilidadePromptFormatter.formatarDisponibilidade(atleta, metaDados, inicioSemana, diasEfetivos)).append("\n\n");

        // ETAPA 5: Análise de estímulos recentes
        historicoCompleto.append(analiseEstimulos).append("\n\n");

        // ETAPA 6: Volume das últimas 3 semanas (consolidado)
        historicoCompleto.append("## 📈 VOLUME MÉDIO DAS ÚLTIMAS 3 SEMANAS\n");
        historicoCompleto.append(String.format("- **Volume Médio:** %.1f km\n", volumeUltimas3Semanas.get("volumeMedioKm")));
        historicoCompleto.append(String.format("- **Tendência:** %s\n", volumeUltimas3Semanas.get("tendencia")));
        historicoCompleto.append(String.format("- **Semana Mais Recente:** %.1f km\n", volumeUltimas3Semanas.get("volumeSemanaMaisRecente")));
        historicoCompleto.append(String.format("- **Semana Anterior:** %.1f km\n", volumeUltimas3Semanas.get("volumeSemanaAnterior")));
        historicoCompleto.append(String.format("- **2 Semanas Atrás:** %.1f km\n\n", volumeUltimas3Semanas.get("volumeDuasSemanas")));

        // ETAPA 7: Matriz de variabilidade
        historicoCompleto.append(matrizVariabilidade).append("\n");
        historicoCompleto.append(alertasVariabilidade).append("\n");

        // ETAPA 8: Instruções de recuperação
        historicoCompleto.append(instrucoesRecuperacao).append("\n\n");

        // ETAPA 9: Periodização estruturada
        historicoCompleto.append("## 📅 PERIODIZAÇÃO E FASE ATUAL\n\n");
        historicoCompleto.append(periodizacaoPromptFormatter.formatarPeriodizacaoProva(provaAlvo)).append("\n\n");

        // ETAPA 10: METAS CALCULADAS PARA ESTA SEMANA (ajustadas e consistentes)
        int tssAlvo = periodizacaoPromptFormatter.calcularTssAlvoAjustado(metaDados, atleta);

        // Determinar tipo de semana baseado nas recomendações
        String tipoSemana = periodizacaoPromptFormatter.determinarTipoSemana(metaDados, atleta, tssAlvo);

        historicoCompleto.append("## 🎯 METAS PARA ESTA SEMANA\n\n");
        historicoCompleto.append(String.format("**Tipo de Semana:** %s\n\n", tipoSemana));
        historicoCompleto.append(String.format("- **TSS Alvo Semanal:** %d pontos", tssAlvo));

        // Explicar se houve ajuste por regeneração
        Double tsb = metaDados != null ? metaDados.getTsbAtual() : null;
        int tssAlvoBase = periodizacaoPromptFormatter.calcularTssAlvo(metaDados);
        if (tssAlvo < tssAlvoBase) {
            int reducaoPercentual = (int) Math.round((1.0 - (double) tssAlvo / tssAlvoBase) * 100);
            historicoCompleto.append(String.format(" (reduzido %d%% por semana regenerativa)", reducaoPercentual));
        }
        historicoCompleto.append("\n");

        historicoCompleto.append(String.format("  - Baseado em CTL atual: %.1f\n", metaDados != null && metaDados.getCtlAtual() != null ? metaDados.getCtlAtual() : 0.0));
        historicoCompleto.append(String.format("  - Ajustado por TSB: %.1f (%s)\n",
                tsb != null ? tsb : 0.0,
                metaDados != null && metaDados.getInterpretacaoTsb() != null ?
                metricasPromptFormatter.interpretarTsb(metaDados != null ? metaDados.getTsbAtual() : null): "normal"));
        historicoCompleto.append(String.format("  - Ramp Rate atual: %.1f pts/sem (%s)\n\n",
                metaDados != null && metaDados.getRampRateAtual() != null ? metaDados.getRampRateAtual() : 0.0,
                metricasPromptFormatter.interpretarRampRate(metaDados != null ? metaDados.getRampRateAtual() : null)));
        historicoCompleto.append(String.format("  - Recomendação Ramp Rate:  (%s)",
                metricasPromptFormatter.getRecomendacaoRampRate(metaDados!= null ? metaDados.getRampRateAtual() : null)));
        historicoCompleto.append(String.format("- **Máximo de Dias Consecutivos Recomendado:** %d dias\n", maxDiasConsecutivos));
        historicoCompleto.append(String.format("  - Atual: %d dias consecutivos\n",
                metaDados != null && metaDados.getDiasConsecutivosTreino() != null ? metaDados.getDiasConsecutivosTreino() : 0));
        historicoCompleto.append("  - Baseado em TSB, experiência e histórico\n\n");
        historicoCompleto.append(String.format("- **Distribuição Semanal Sugerida:** %s\n\n",
                disponibilidadePromptFormatter.sugerirDistribuicaoSemanal(metaDados, atleta)));

        // ETAPA 10.5: Decisão de progressão — abaixo do teto de TSS (hierarquia explícita, D7)
        String blocoProgressao = periodizacaoPromptFormatter.formatarDecisaoProgressao(decisaoProgressao);
        if (!blocoProgressao.isEmpty()) {
            historicoCompleto.append(blocoProgressao);
        }

        // 8. Fallbacks para dados nulos
        String diaPreferidoLongo = atleta.getDiaPreferidoLongo() != null ?
                atleta.getDiaPreferidoLongo().toString() : "SABADO";

        // 9. BLOCO [1] — regras mandatórias consolidadas (declaradas como Constraint), no topo do prompt
        List<Constraint> regras = montarRegras(recomIntervalado, tetoPorTipo, diasEfetivos, metaDados, atleta);

        // 10. Montar histórico completo: regras no TOPO, depois alertas, depois dados
        StringBuilder historicoFinal = new StringBuilder();
        historicoFinal.append(formatarBlocoRegras(regras));
        historicoFinal.append(formatarBlocoSlots(skeleton != null ? skeleton.sessions() : null)); // vazio quando skeleton null (flag off) → prompt legado (CA9)
        historicoFinal.append(alertasObrigatorios);
        historicoFinal.append(hierarquiaDecisao);
        historicoFinal.append(eventoCompetitivoSemana);
        historicoFinal.append(restricoesLesoes);
        historicoFinal.append(historicoCompleto);

        historicoFinal.append(String.format("** STATUS GERAL ** \n"));
        historicoFinal.append(String.format("  - ** Status geral: (%s)", metricasPromptFormatter.avaliarStatusGeral(metaDados)));

        // 10. Carregar e formatar o novo template otimizado
        String prompt = templateLoader.loadAndFormat(
                "plano-treino-otimizado-claude.txt",
                atleta.getNome(),                                                                              // %s - Nome
                atleta.getIdade(),                                                                             // %d - Idade
                atleta.getObjetivo() != null ? atleta.getObjetivo() : "Melhorar condicionamento",             // %s - Objetivo
                atleta.getNivelExperiencia() != null ? atleta.getNivelExperiencia().toString() : "INTERMEDIARIO", // %s - Experiência
                disponibilidadePromptFormatter.formatarDias(atleta.getDiasDisponiveis()),                       // %s - Dias disponíveis
                diaPreferidoLongo,                                                                             // %s - Dia preferido longo
                provas,                                                                                        // %s - Provas
                historicoFinal.toString()                                                                      // %s - Histórico completo (com alertas no topo)
        );
        // Retorna o prompt + as Constraint já computadas (evita recomputar contexto pós-geração).
        return new PromptGerado(prompt, regras);
    }

    /** Prompt montado + as {@link Constraint} ativas usadas no bloco [1] e pelo {@code PlanQualityChecker}. */
    public record PromptGerado(String prompt, List<Constraint> regras) {}

    // ======================== MÉTODOS AUXILIARES (mantidos) ========================

    /**
     * Reúne as {@link Constraint} ativas do plano (intervalado, teto de pace, dias permitidos,
     * máx. consecutivos). Fonte única usada para renderizar o bloco [1] e — via {@link PromptGerado} —
     * para o {@code PlanQualityChecker} verificar o plano gerado (sem recomputar contexto).
     */
    private List<Constraint> montarRegras(RecomendacaoIntervalado recomIntervalado,
                                          Map<TipoTreino, BigDecimal> tetoPorTipo,
                                          List<DiaSemana> diasEfetivos,
                                          PlanoMetaDados metaDados, Atleta atleta) {
        List<Constraint> regras = new ArrayList<>();
        recomIntervalado.toConstraint().ifPresent(regras::add);
        paceHistoricoFormatter.tetoConstraint(tetoPorTipo).ifPresent(regras::add);
        disponibilidadePromptFormatter.diasPermitidosConstraint(diasEfetivos).ifPresent(regras::add);
        regras.add(disponibilidadePromptFormatter.maxConsecutivosConstraint(metaDados, atleta));
        LocalDate hoje = LocalDate.now();
        thresholdConstraintFormatter.constraintFc(metaDados, atleta, hoje).ifPresent(regras::add);
        thresholdConstraintFormatter.constraintPace(metaDados, atleta, hoje).ifPresent(regras::add);
        return regras;
    }

    /**
     * Compõe o bloco mandatório [1] no topo do prompt a partir das {@link Constraint} ativas —
     * consolidadas num único lugar proeminente (lever anti-alucinação). Vazio se não há regras.
     */
    private String formatarBlocoRegras(List<Constraint> regras) {
        if (regras == null || regras.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## ⛔ REGRAS QUE VOCÊ NÃO PODE VIOLAR\n\n");
        for (Constraint regra : regras) {
            sb.append("- ").append(regra.descricao()).append("\n");
        }
        sb.append("\n(Estas regras são determinísticas, baseadas em dados reais do atleta, e ");
        sb.append("SUBSTITUEM qualquer raciocínio independente. Não as viole.)\n\n");
        return sb.toString();
    }

    /**
     * Bloco mandatório da estrutura prescritiva do planner (planner-engine-enforcement §3). Renderiza
     * os {@link SessionSlot} (dia/tipo/TSS/zona) quando o skeleton está presente; VAZIO quando
     * {@code skeleton == null} (flag off) — nesse caso o prompt é idêntico ao legado (CA9).
     */
    static String formatarBlocoSlots(@Nullable List<SessionSlot> sessoes) {
        if (sessoes == null || sessoes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 📅 ESTRUTURA OBRIGATÓRIA DA SEMANA (planner)\n\n");
        sb.append("Gere exatamente estas sessões, respeitando dia, tipo, TSS-alvo e zona de cada uma:\n\n");
        for (SessionSlot s : sessoes) {
            sb.append(String.format("- %s: %s — TSS ~%.0f, %s%s%s%n",
                    s.day() != null ? diaEmPortugues(s.day()) : "(dia a definir)",
                    s.sessionType(),
                    s.targetTss(),
                    s.intensityZone() != null ? s.intensityZone() : "zona a definir",
                    s.durationMinutes() != null ? String.format(", ~%dmin", s.durationMinutes()) : "",
                    s.chave() ? " [SESSÃO-CHAVE]" : ""));
        }
        sb.append("\n(Estrutura determinística do planner — não altere dia/tipo/TSS/zona; preencha apenas ");
        sb.append("a estrutura fina de cada sessão.)\n\n");
        return sb.toString();
    }

    private static String diaEmPortugues(DayOfWeek dia) {
        return switch (dia) {
            case MONDAY -> "Segunda";
            case TUESDAY -> "Terça";
            case WEDNESDAY -> "Quarta";
            case THURSDAY -> "Quinta";
            case FRIDAY -> "Sexta";
            case SATURDAY -> "Sábado";
            case SUNDAY -> "Domingo";
        };
    }

    private String formatarHistoricoTreinos(List<TreinoRealizado> treinosRecentes) {
        if (treinosRecentes == null || treinosRecentes.isEmpty()) {
            return "Nenhum treino realizado nos últimos 14 dias.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**Últimos 14 dias:**\n\n");

        int totalTreinos = treinosRecentes.size();

        BigDecimal volumeTotal = treinosRecentes.stream()
                .map(t -> t.getDistanciaKm() != null ? t.getDistanciaKm() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int tssTotal = treinosRecentes.stream()
                .mapToInt(t -> t.getTssCalculado() != null ? t.getTssCalculado() : 0)
                .sum();

        BigDecimal volumeMedio = volumeTotal.divide(
                BigDecimal.valueOf(totalTreinos),
                2,
                RoundingMode.HALF_UP
        );

        int tssMedio = tssTotal / totalTreinos;

        sb.append(String.format("- **Total de treinos:** %d\n", totalTreinos));
        sb.append(String.format("- **Volume total:** %.1f km\n", volumeTotal.doubleValue()));
        sb.append(String.format("- **TSS total:** %d pontos\n", tssTotal));
        sb.append(String.format("- **Média por treino:** %.1f km | %d TSS\n\n",
                volumeMedio.doubleValue(),
                tssMedio));

        sb.append("**Últimos 5 treinos:**\n");
        treinosRecentes.stream()
                .limit(5)
                .forEach(treino -> {
                    sb.append(String.format("- %s: %s - %.1f km, %s min, TSS %d",
                            treino.getDataTreino(),
                            treino.getTipoTreinoEfetivo(),
                            treino.getDistanciaKm() != null ? treino.getDistanciaKm().doubleValue() : 0.0,
                            treino.getDuracaoMin() != null ? treino.getDuracaoMin() : 0,
                            treino.getTssCalculado() != null ? treino.getTssCalculado() : 0
                    ));

                    if (treino.getPercepcaoEsforco() != null) {
                        sb.append(String.format(" | RPE %d/10", treino.getPercepcaoEsforco()));
                    }
                    sb.append("\n");
                });

        return sb.toString();
    }

    private String formatarDadosFisiologicos(Atleta atleta) {
        List<ZonaCompleta> zonas = zonaTreinoService.calcularZonas(atleta);

        if (atleta.getFcLimiar() == null && atleta.getPaceLimiar() == null) {
            // Sem dados testados: calcular zonas pela fórmula etária (220 - idade → 85% FCmáx)
            // e fornecer ao LLM os valores numéricos exatos para evitar alucinação de BPMs.
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("""
                            **Frequência Cardíaca (estimada por fórmula etária — sem teste formal):**
                            - FC Máxima: %d bpm (estimada: 220 - idade)
                            - FC Repouso: %d bpm
                            - FC Limiar: %d bpm (estimativa: 85%% FCmáx)

                            **Pace/Velocidade:** sem dados — não prescrever pace até teste formal.

                            **⚠️ ATENÇÃO:** Use EXATAMENTE as zonas de FC listadas abaixo.
                            NÃO invente outros valores de BPM. Recomende teste de limiar urgente ao atleta.

                            **Zonas de Treino (estimadas — USE ESTES VALORES DE BPM):**
                            """,
                    atleta.getFcMaximaCalculada(),
                    atleta.getFcRepouso() != null ? atleta.getFcRepouso() : 60,
                    atleta.getFcLimiarCalculada()
            ));
            for (ZonaCompleta zona : zonas) {
                sb.append(String.format("- Z%d (%s): %d-%d bpm\n",
                        zona.numero(), zona.nome(), zona.fc().fcMin(), zona.fc().fcMax()));
            }
            return sb.toString();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("""
                        **Frequência Cardíaca:**
                        - FC Máxima: %d bpm %s
                        - FC Repouso: %d bpm
                        - FC Limiar: %d bpm (%.0f%%%% FCmáx)
                        - Última atualização: %s
                        """,
                atleta.getFcMaximaCalculada(),
                atleta.getFcMaxima() != null ? "" : "(estimada)",
                atleta.getFcRepouso() != null ? atleta.getFcRepouso() : 60,
                atleta.getFcLimiarCalculada(),
                (atleta.getFcLimiarCalculada() * 100.0 / atleta.getFcMaximaCalculada()),
                atleta.getDataUltimoTesteFc() != null ?
                        atleta.getDataUltimoTesteFc().toString() : "Nunca testado"
        ));

        BigDecimal paceLimiar = atleta.getPaceLimiar();
        if (paceLimiar != null) {
            sb.append(String.format("""

                            **Pace/Velocidade:**
                            - Pace Limiar: %.2f min/km
                            - Velocidade Limiar: %s
                            - Última atualização: %s
                            """,
                    paceLimiar,
                    atleta.getVelocidadeLimiar() != null
                            ? String.format("%.2f km/h", atleta.getVelocidadeLimiar()) : "não calculada",
                    atleta.getDataUltimoTestePace() != null ?
                            atleta.getDataUltimoTestePace().toString() : "Nunca testado"
            ));
        } else {
            sb.append("\n**Pace/Velocidade:** não cadastrado — sem dados para prescrição de pace.\n");
        }

        sb.append("\n**Zonas de Treino Calculadas:**\n");
        for (ZonaCompleta zona : zonas) {
            sb.append(String.format("- Z%d (%s): %s-%s min/km | %d-%d bpm\n",
                    zona.numero(),
                    zona.nome(),
                    zona.pace().paceMin(),
                    zona.pace().paceMax(),
                    zona.fc().fcMin(),
                    zona.fc().fcMax()));
        }

        return sb.toString();
    }

    private String validarEFallbacksDadosFisiologicos(Atleta atleta) {
        StringBuilder fallbacks = new StringBuilder();
        boolean temProblema = false;

        if (atleta.getPaceLimiar() == null || atleta.getPaceLimiar().compareTo(BigDecimal.ZERO) == 0) {
            temProblema = true;
            fallbacks.append("⚠️ **Pace Limiar inválido/zerado** → Usar estimativa por nível\n");
            String paceEstimado = estimarPacePorNivel(atleta);
            fallbacks.append("  - Pace estimado: ").append(paceEstimado).append(" min/km\n");
            fallbacks.append("  - **USAR APENAS FC para prescrição, não pace\n");
            fallbacks.append("⚠️ **Zonas de pace inutilizáveis** → Usar apenas FC (Z1-Z5 por percentual FCmax)\n");
        }

        if (atleta.getFcLimiarCalculada() == null || atleta.getFcLimiarCalculada() <= 0) {
            temProblema = true;
            Integer fcMax = atleta.getFcMaximaCalculada();
            if (fcMax != null && fcMax > 0) {
                int fcLimiarEstimada = (int) Math.round(fcMax * 0.85);
                fallbacks.append("⚠️ **FC Limiar não disponível** → Usar estimativa: ").append(fcLimiarEstimada).append(" bpm (85% FCmax)\n");
            } else {
                fallbacks.append("⚠️ **FC Limiar e FC Max não disponíveis** → Usar valores conservadores\n");
                fallbacks.append("  - Estimar FC Limiar: ~150-160 bpm\n");
            }
        }

        if (temProblema) {
            StringBuilder sb = new StringBuilder();
            sb.append("## ⚠️ FALLBACKS PARA DADOS INCOMPLETOS\n\n");
            sb.append(fallbacks);
            sb.append("\n**Ao prescrever treinos:**\n");
            sb.append("- Se pace inválido: usar formato \"Z2 (140-160 bpm)\" em vez de incluir pace\n");
            sb.append("- Se FC disponível: priorizar FC sobre pace estimado\n");
            sb.append("- Adicionar na justificativa: \"Zonas estimadas - recomenda-se teste de limiar\"\n\n");
            return sb.toString();
        }

        return "";
    }

    private String estimarPacePorNivel(Atleta atleta) {
        if (atleta.getNivelExperiencia() == null) {
            return "5:30-6:00";
        }

        return switch (atleta.getNivelExperiencia()) {
            case INICIANTE -> "6:30-7:00";
            case INTERMEDIARIO -> "5:30-6:00";
            case AVANCADO -> "4:30-5:00";
            case ELITE -> "3:30-4:30";
        };
    }
}
