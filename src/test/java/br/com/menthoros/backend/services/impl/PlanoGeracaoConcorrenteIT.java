package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.AbstractIntegrationTest;
import br.com.menthoros.backend.dto.llm.PlanoSemanalLlmDto;
import br.com.menthoros.backend.dto.llm.TreinoPlanejadoLlmDto;
import br.com.menthoros.backend.entity.Assessoria;
import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.entity.PlanoSemanal;
import br.com.menthoros.backend.enums.AtletaStatus;
import br.com.menthoros.backend.enums.DiaSemana;
import br.com.menthoros.backend.enums.ModoGeracaoPlano;
import br.com.menthoros.backend.enums.NivelExperiencia;
import br.com.menthoros.backend.enums.PlanoAssessoria;
import br.com.menthoros.backend.exception.PlanoJaExistenteException;
import br.com.menthoros.backend.multitenancy.TenantContext;
import br.com.menthoros.backend.repository.AssessoriaRepository;
import br.com.menthoros.backend.repository.AtletaRepository;
import br.com.menthoros.backend.repository.PlanoSemanalRepository;
import br.com.menthoros.backend.services.IaService;
import br.com.menthoros.backend.services.PlanoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * CA4 de refactor-llm-call-outside-transaction: com a chamada ao LLM fora da transação, duas
 * gerações para o mesmo atleta e semana podem passar juntas pela checagem cedo (fase 1) — as duas
 * veem "sem plano" — e chegar juntas à fase de escrita. Uma tem de vencer e a outra falhar limpo
 * com {@link PlanoJaExistenteException}, sem linha duplicada, seja pela re-checagem dentro da
 * transação de escrita, seja pelo índice parcial {@code uk_plano_semanal_atleta_semana_ativo}
 * (V52), que só existe no Postgres real — por isso Testcontainers.
 *
 * <p>O LLM é um stub sincronizado por latch: as duas threads só recebem a resposta depois que
 * ambas já estão dentro da chamada, o que garante que a corrida acontece na fase 3 e não na 1.
 */
@DisplayName("Geração concorrente do mesmo plano — o índice da V52 decide")
@TestPropertySource(properties = "onboarding.migrate-existing.enabled=false")
class PlanoGeracaoConcorrenteIT extends AbstractIntegrationTest {

    @MockitoBean private IaService iaService;

    @Autowired private PlanoService planoService;
    @Autowired private AssessoriaRepository assessoriaRepository;
    @Autowired private AtletaRepository atletaRepository;
    @Autowired private PlanoSemanalRepository planoSemanalRepository;

    private Assessoria assessoria;
    private Atleta atleta;

    @BeforeEach
    void preparar() {
        assessoria = new Assessoria();
        assessoria.setNome("Assessoria Corrida");
        assessoria.setDominio("corrida-" + UUID.randomUUID());
        assessoria.setPlano(PlanoAssessoria.BASIC);
        assessoria = assessoriaRepository.save(assessoria);

        Atleta novo = new Atleta();
        novo.setNome("Atleta Concorrente");
        novo.setObjetivo("Terminar os 10k");
        novo.setNivelExperiencia(NivelExperiencia.INICIANTE);
        novo.setAtivo(AtletaStatus.ATIVO);
        novo.setDiasDisponiveis(List.of(DiaSemana.SEGUNDA, DiaSemana.QUARTA, DiaSemana.SABADO));
        novo.setDiaPreferidoLongo(DiaSemana.SABADO);
        novo.setAssessoria(assessoria);
        atleta = atletaRepository.save(novo);

        TenantContext.setTenantId(assessoria.getId());
    }

    @AfterEach
    void limpar() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("duas gerações simultâneas: uma vence, a outra recebe PlanoJaExistenteException, uma linha só")
    void umaVenceOutraFalhaLimpo() throws Exception {
        CountDownLatch ambasNoLlm = new CountDownLatch(2);
        CountDownLatch libera = new CountDownLatch(1);

        when(iaService.geraPlanoSemanalAvancado(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    ambasNoLlm.countDown();
                    if (!libera.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("latch do LLM não foi liberado");
                    }
                    return planoDeUmTreino();
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Resultado> a = executor.submit(gerar());
            Future<Resultado> b = executor.submit(gerar());

            assertThat(ambasNoLlm.await(15, TimeUnit.SECONDS))
                    .as("as duas gerações têm de passar pela fase 1 antes de qualquer uma persistir")
                    .isTrue();
            libera.countDown();

            List<Resultado> resultados = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));

            long sucessos = resultados.stream().filter(r -> r.plano() != null).count();
            long duplicados = resultados.stream().filter(r -> r.erro() instanceof PlanoJaExistenteException).count();

            assertThat(sucessos).as("exatamente uma geração persiste").isEqualTo(1);
            assertThat(duplicados)
                    .as("a outra falha limpo com PlanoJaExistenteException, não com 500 — erros: "
                            + resultados.stream().map(Resultado::erro).toList())
                    .isEqualTo(1);
            assertThat(planoSemanalRepository.findAtivosPorAtleta(atleta.getId(), assessoria.getId()))
                    .as("nenhuma linha duplicada")
                    .hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<Resultado> gerar() {
        UUID tenantId = assessoria.getId();
        UUID atletaId = atleta.getId();
        return () -> {
            // TenantContext é ThreadLocal: cada thread do pool precisa do seu.
            TenantContext.setTenantId(tenantId);
            try {
                return new Resultado(planoService.gerarPlanoTreino(atletaId, ModoGeracaoPlano.PROXIMA_SEMANA), null);
            } catch (Exception e) {
                return new Resultado(null, e);
            } finally {
                TenantContext.clear();
            }
        };
    }

    private record Resultado(PlanoSemanal plano, Exception erro) {
    }

    private static PlanoSemanalLlmDto planoDeUmTreino() {
        TreinoPlanejadoLlmDto treino = new TreinoPlanejadoLlmDto(
                "SEGUNDA", "FACIL", "130-140 bpm", 40, 1.0, 4,
                "Base aeróbica", "45", 8.0, "5:40", List.of());
        return new PlanoSemanalLlmDto(8.0, 8.0, null, null, "PLANEJADO", "Semana de base", List.of(treino));
    }
}
