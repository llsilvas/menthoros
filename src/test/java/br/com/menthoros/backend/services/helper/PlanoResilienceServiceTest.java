package br.com.menthoros.backend.services.helper;

import br.com.menthoros.backend.dto.llm.PlanoSemanalLlmDto;
import br.com.menthoros.backend.exception.DomainRuleViolationException;
import br.com.menthoros.backend.exception.LLMException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PlanoResilienceService")
class PlanoResilienceServiceTest {

    private MeterRegistry registry;
    private PlanoResilienceService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new PlanoResilienceService(registry);
    }

    private static PlanoSemanalLlmDto plano() {
        return new PlanoSemanalLlmDto(0.0, 0.0, null, null, null, null, List.of());
    }

    private double contador(String nome) {
        var c = registry.find(nome).counter();
        return c == null ? 0.0 : c.count();
    }

    @Nested
    @DisplayName("deadline total")
    class DeadlineTotal {

        @Test
        @DisplayName("1ª tentativa que estoura o orçamento não inicia a 2ª (CA4)")
        void primeiraLentaNaoRetenta() {
            // Deadline curto para o teste; em produção são 100s contra o teto de 120s da rota.
            PlanoResilienceService comDeadlineCurto =
                    new PlanoResilienceService(registry, java.time.Duration.ofMillis(50));
            List<String> chamadas = new ArrayList<>();

            Function<String, PlanoSemanalLlmDto> gerarLento = prompt -> {
                chamadas.add(prompt);
                try {
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return plano();
            };
            Function<PlanoSemanalLlmDto, PlanoSemanalLlmDto> rejeita = p -> {
                throw new LLMException("estrutura invalida");
            };

            assertThatThrownBy(() -> comDeadlineCurto.gerarComResiliencia(gerarLento, rejeita, "base"))
                    .isInstanceOf(DomainRuleViolationException.class);

            assertThat(chamadas).hasSize(1);
            assertThat(contador("plano_deadline_estourado")).isEqualTo(1.0);
            assertThat(contador("plano_retry")).isZero();
        }

        @Test
        @DisplayName("1ª tentativa rápida com rejeição segue retentando — o caso comum não regride")
        void primeiraRapidaAindaRetenta() {
            PlanoResilienceService comDeadlineFolgado =
                    new PlanoResilienceService(registry, java.time.Duration.ofSeconds(30));
            List<String> chamadas = new ArrayList<>();

            Function<String, PlanoSemanalLlmDto> gerar = prompt -> {
                chamadas.add(prompt);
                return plano();
            };
            Function<PlanoSemanalLlmDto, PlanoSemanalLlmDto> rejeita = p -> {
                throw new LLMException("estrutura invalida");
            };

            assertThatThrownBy(() -> comDeadlineFolgado.gerarComResiliencia(gerar, rejeita, "base"))
                    .isInstanceOf(DomainRuleViolationException.class);

            assertThat(chamadas).hasSize(2);
            assertThat(contador("plano_retry")).isEqualTo(1.0);
            assertThat(contador("plano_deadline_estourado")).isZero();
        }
    }

    @Nested
    @DisplayName("gerarComResiliencia")
    class GerarComResiliencia {

    @Test
    @DisplayName("sucesso na 1ª tentativa → sem retry")
    void sucessoPrimeira() {
        PlanoSemanalLlmDto p = plano();
        PlanoSemanalLlmDto r = service.gerarComResiliencia(prompt -> p, plano -> plano, "base");
        assertThat(r).isSameAs(p);
        assertThat(contador("plano_retry")).isZero();
        assertThat(contador("plano_geracao_falha_final")).isZero();
    }

    @Test
    @DisplayName("falha estrutural → retry com feedback → sucesso")
    void falhaDepoisSucesso() {
        List<String> prompts = new ArrayList<>();
        Function<String, PlanoSemanalLlmDto> gerar = prompt -> { prompts.add(prompt); return plano(); };
        int[] chamadas = {0};
        Function<PlanoSemanalLlmDto, PlanoSemanalLlmDto> validar = plano -> {
            if (++chamadas[0] == 1) throw new LLMException("REGENERATIVO inválido: 2 etapas");
            return plano;
        };

        PlanoSemanalLlmDto r = service.gerarComResiliencia(gerar, validar, "base");

        assertThat(r).isNotNull();
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(0)).isEqualTo("base");
        assertThat(prompts.get(1)).contains("CORRECAO OBRIGATORIA").contains("2 etapas"); // feedback injetado
        assertThat(contador("plano_retry")).isEqualTo(1.0);
        assertThat(contador("plano_geracao_falha_final")).isZero();
    }

    @Test
    @DisplayName("falha estrutural em ambas → DomainRuleViolationException (não 503) + falha final contada")
    void falhaDupla() {
        Function<PlanoSemanalLlmDto, PlanoSemanalLlmDto> validar = plano -> { throw new LLMException("falta PRINCIPAL"); };

        assertThatThrownBy(() -> service.gerarComResiliencia(prompt -> plano(), validar, "base"))
                .isInstanceOf(DomainRuleViolationException.class)
                .hasMessageContaining("Não foi possível gerar o plano");
        assertThat(contador("plano_geracao_falha_final")).isEqualTo(1.0);
        assertThat(contador("plano_retry")).isEqualTo(1.0); // 1 retry tentado antes de desistir
    }

    @Test
    @DisplayName("falha de geração (infra) propaga — não vira retry nem erro de domínio")
    void falhaGeracaoPropaga() {
        Function<String, PlanoSemanalLlmDto> gerar = prompt -> { throw new LLMException("LLM indisponível"); };

        assertThatThrownBy(() -> service.gerarComResiliencia(gerar, plano -> plano, "base"))
                .isInstanceOf(LLMException.class);
        assertThat(contador("plano_geracao_falha_final")).isZero();
    }
    }

    @Nested
    @DisplayName("orçamento compartilhado por requisição (design 3b)")
    class OrcamentoCompartilhado {

        @Test
        @DisplayName("o mesmo orçamento não ultrapassa 2 gerações somando enforced + fallback")
        void naoUltrapassaTeto() {
            var orcamento = new GenerationBudget(2, java.time.Duration.ofSeconds(30));
            List<String> chamadas = new ArrayList<>();
            Function<String, PlanoSemanalLlmDto> gerar = p -> { chamadas.add(p); return plano(); };
            Function<PlanoSemanalLlmDto, PlanoSemanalLlmDto> rejeita = p -> { throw new LLMException("x"); };

            // 1º caminho (enforced): esgota as 2 gerações do orçamento
            assertThatThrownBy(() -> service.gerarComResiliencia(gerar, rejeita, "base", orcamento))
                    .isInstanceOf(DomainRuleViolationException.class);
            assertThat(chamadas).hasSize(2);

            // 2º caminho (fallback) com o MESMO orçamento: já esgotado -> nenhuma geração nova
            assertThatThrownBy(() -> service.gerarComResiliencia(gerar, rejeita, "base", orcamento))
                    .isInstanceOf(DomainRuleViolationException.class);
            assertThat(chamadas).hasSize(2); // não cresceu — fallback não gerou de novo
            assertThat(orcamento.gastas()).isEqualTo(2);
        }

        @Test
        @DisplayName("orçamento já esgotado não inicia nenhuma geração (gerar nunca é chamado)")
        void esgotadoNaoGera() {
            var orcamento = new GenerationBudget(1, java.time.Duration.ofSeconds(30));
            orcamento.tentarDebitar(); // esgota o único slot fora do service
            List<String> chamadas = new ArrayList<>();
            Function<String, PlanoSemanalLlmDto> gerar = p -> { chamadas.add(p); return plano(); };

            assertThatThrownBy(() -> service.gerarComResiliencia(gerar, p -> p, "base", orcamento))
                    .isInstanceOf(DomainRuleViolationException.class);
            assertThat(chamadas).isEmpty(); // débito antes da chamada: sem orçamento, não chama o LLM
        }
    }
}
