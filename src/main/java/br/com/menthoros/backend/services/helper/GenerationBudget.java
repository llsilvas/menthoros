package br.com.menthoros.backend.services.helper;

import java.time.Duration;

/**
 * Orçamento de geração de plano com escopo de <b>requisição</b> (planner-engine-enforcement,
 * design Decisão 3b). Um único objeto é compartilhado por todos os caminhos de geração de uma
 * requisição — geração enforced, retry e eventual fallback legado — de forma que o total de
 * gerações lógicas nunca ultrapasse {@code maxGeracoes} e o relógio {@code deadlineTotal} seja
 * preservado entre as etapas (não reiniciado por um novo caminho).
 *
 * <p>Regras (design 3b): <b>débito antes da chamada</b> ao LLM (inclusive quando a chamada falha —
 * uma resposta inválida ou falha de infra consomem tentativa); esgotado o orçamento (contagem
 * <b>ou</b> deadline), nenhuma nova geração é iniciada.</p>
 *
 * <p>Idempotent: NO — {@link #tentarDebitar()} muta o contador. Não é thread-safe: uma requisição é
 * processada por uma thread; o batch usa um orçamento por atleta.</p>
 */
public final class GenerationBudget {

    private final int maxGeracoes;
    private final Duration deadlineTotal;
    private final long inicioNanos;
    private int gastas;

    public GenerationBudget(int maxGeracoes, Duration deadlineTotal) {
        if (maxGeracoes < 1) {
            throw new IllegalArgumentException("maxGeracoes deve ser >= 1");
        }
        if (deadlineTotal == null || deadlineTotal.isNegative() || deadlineTotal.isZero()) {
            throw new IllegalArgumentException("deadlineTotal deve ser positivo");
        }
        this.maxGeracoes = maxGeracoes;
        this.deadlineTotal = deadlineTotal;
        this.inicioNanos = System.nanoTime();
    }

    /**
     * Tenta debitar uma geração. Retorna {@code true} e incrementa o contador quando ainda há
     * orçamento (contagem e deadline); {@code false} quando esgotado — e então nenhuma geração deve
     * ser iniciada. Deve ser chamado <b>antes</b> da chamada ao LLM.
     */
    public boolean tentarDebitar() {
        if (gastas >= maxGeracoes) {
            return false;
        }
        if (deadlineEstourado()) {
            return false;
        }
        gastas++;
        return true;
    }

    /** True quando o relógio da requisição já passou do {@code deadlineTotal}. */
    public boolean deadlineEstourado() {
        return Duration.ofNanos(System.nanoTime() - inicioNanos).compareTo(deadlineTotal) >= 0;
    }

    /** Gerações já debitadas nesta requisição (todos os caminhos somados). */
    public int gastas() {
        return gastas;
    }
}
