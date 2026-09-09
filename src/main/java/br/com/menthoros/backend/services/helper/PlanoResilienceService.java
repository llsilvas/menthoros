package br.com.menthoros.backend.services.helper;

import br.com.menthoros.backend.dto.llm.PlanoSemanalLlmDto;
import br.com.menthoros.backend.exception.DomainRuleViolationException;
import br.com.menthoros.backend.exception.LLMException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Function;

/**
 * Orquestra a geração resiliente de plano: gera → valida (reparo já aplicado a montante) →, se a
 * validação ainda falhar com {@link LLMException} estrutural, re-chama o LLM <b>uma única vez</b>
 * injetando o motivo da rejeição; esgotado, falha com {@link DomainRuleViolationException}
 * (mensagem orientada ao treinador, mapeada no GlobalExceptionHandler — não 503).
 *
 * <p>Teto = 1 retry (cada tentativa ~80s; 2 retries seriam ~4min, inaceitável em fluxo síncrono).
 * Falhas de <i>geração</i> (LLM/infra) propagam — só falhas de <i>validação</i> disparam retry.</p>
 *
 * <p>Idempotent: NO — re-chama o LLM (saída não-determinística), mas sem escrita de estado por tentativa
 * (persistência acontece a jusante, uma vez). Side Effects: chamada ao LLM + contadores Micrometer.
 * Tenant-aware: NO — recebe as funções já vinculadas ao atleta/tenant pelo chamador.</p>
 */
@Slf4j
@Component
public class PlanoResilienceService {

    /** 1 geração + 1 retry. */
    static final int MAX_TENTATIVAS = 2;

    /**
     * Orçamento total da geração. Com o teto de 120s da rota {@code plano}, duas
     * tentativas dariam 240s — os "~4min" que o javadoc acima já chama de
     * inaceitável. Checado <b>antes</b> de iniciar a 2ª: se a 1ª já consumiu o
     * orçamento, pagar outra rodada só piora a espera do treinador.
     *
     * <p>Não corta o caso comum: a falha que dispara esse retry é estrutural
     * (resposta malformada) e normalmente chega bem antes dos 100s.
     */
    static final Duration DEADLINE_TOTAL = Duration.ofSeconds(100);

    private final MeterRegistry meterRegistry;
    private final Duration deadlineTotal;

    // @Autowired explícito: com mais de um construtor o Spring não elege candidato
    // sozinho e cai no construtor default, que não existe.
    @org.springframework.beans.factory.annotation.Autowired
    public PlanoResilienceService(MeterRegistry meterRegistry) {
        this(meterRegistry, DEADLINE_TOTAL);
    }

    /** Visível para teste: o orçamento real é medido em dezenas de segundos. */
    PlanoResilienceService(MeterRegistry meterRegistry, Duration deadlineTotal) {
        this.meterRegistry = meterRegistry;
        this.deadlineTotal = deadlineTotal;
    }

    /**
     * @param gerar    prompt → plano (chama o LLM); falhas de geração propagam (não são retry)
     * @param validar  plano → plano validado/normalizado; lança {@link LLMException} em violação estrutural residual
     * @param promptBase prompt inicial; no retry é acrescido do motivo da rejeição anterior
     */
    public PlanoSemanalLlmDto gerarComResiliencia(Function<String, PlanoSemanalLlmDto> gerar,
                                                  Function<PlanoSemanalLlmDto, PlanoSemanalLlmDto> validar,
                                                  String promptBase) {
        // Orçamento por invocação (comportamento atual). O overload abaixo recebe um orçamento
        // compartilhado por requisição (enforced + fallback) — ver design planner-engine-enforcement 3b.
        return gerarComResiliencia(gerar, validar, promptBase, new GenerationBudget(MAX_TENTATIVAS, deadlineTotal));
    }

    /**
     * Variante que recebe um {@link GenerationBudget} <b>compartilhado</b> pela requisição inteira:
     * o débito é feito <b>antes</b> de cada chamada ao LLM, o relógio é o do orçamento (não reinicia),
     * e esgotado o orçamento (contagem ou deadline) nenhuma nova geração é iniciada. Usado quando o
     * enforcement e um eventual fallback legado precisam somar no mesmo teto (design 3b).
     */
    public PlanoSemanalLlmDto gerarComResiliencia(Function<String, PlanoSemanalLlmDto> gerar,
                                                  Function<PlanoSemanalLlmDto, PlanoSemanalLlmDto> validar,
                                                  String promptBase,
                                                  GenerationBudget orcamento) {
        Counter.builder("plano_geracao_total").register(meterRegistry).increment(); // denominador da taxa de sucesso
        String prompt = promptBase;
        LLMException ultimaFalha = null;
        int geracoes = 0;

        while (orcamento.tentarDebitar()) { // débito ANTES da chamada; false = orçamento esgotado
            geracoes++;
            if (geracoes > 1) { // 2ª geração em diante = retry com feedback
                Counter.builder("plano_retry").tag("motivo", "estrutural").register(meterRegistry).increment();
                String motivo = truncar(ultimaFalha != null ? ultimaFalha.getMessage() : null);
                log.warn("Plano rejeitado; re-gerando com feedback ({})", motivo);
                prompt = promptBase
                        + "\n\n## CORRECAO OBRIGATORIA (a tentativa anterior foi rejeitada)\n"
                        + "Motivo: " + motivo + "\n"
                        + "Gere o plano novamente corrigindo exatamente esse ponto, mantendo as demais regras.";
            }
            PlanoSemanalLlmDto plano = gerar.apply(prompt); // falha de geração propaga (infra → 503); a tentativa já foi debitada
            try {
                return validar.apply(plano);
            } catch (LLMException e) {
                ultimaFalha = e; // loop: a próxima iteração debita (se houver orçamento) e retenta
            }
        }

        if (orcamento.deadlineEstourado()) {
            Counter.builder("plano_deadline_estourado").register(meterRegistry).increment();
            log.warn("Orcamento de {}s esgotado — nova geração não será iniciada", deadlineTotal.toSeconds());
        }
        Counter.builder("plano_geracao_falha_final").register(meterRegistry).increment();
        log.error("Geração de plano falhou após {} geração(ões): {}",
                geracoes, ultimaFalha != null ? truncar(ultimaFalha.getMessage()) : "desconhecido");
        throw new DomainRuleViolationException(
                "Não foi possível gerar o plano desta semana. Tente novamente ou ajuste os parâmetros do atleta.");
    }

    /** Teto defensivo: a mensagem da rejeição vai para o prompt do LLM e para o log — evita reinjetar
     *  payload extenso (ou eventual conteúdo de erro de SDK) por inteiro. */
    private static final int MAX_MOTIVO_CHARS = 300;

    private static String truncar(String motivo) {
        if (motivo == null) return "motivo desconhecido";
        return motivo.length() <= MAX_MOTIVO_CHARS ? motivo : motivo.substring(0, MAX_MOTIVO_CHARS) + "…";
    }
}
