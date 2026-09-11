package br.com.menthoros.backend.services;

import br.com.menthoros.backend.dto.output.BatchJobStatusOutputDto.BatchErroItemDto;
import br.com.menthoros.backend.dto.output.BatchJobStatusOutputDto.BatchGeradoItemDto;
import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.entity.BatchPlanJob;
import br.com.menthoros.backend.entity.PlanoSemanal;
import br.com.menthoros.backend.enums.BatchJobStatus;
import br.com.menthoros.backend.enums.ModoGeracaoPlano;
import br.com.menthoros.backend.exception.DomainRuleViolationException;
import br.com.menthoros.backend.exception.PlanoJaExistenteException;
import br.com.menthoros.backend.multitenancy.TenantContext;
import br.com.menthoros.backend.repository.AtletaRepository;
import br.com.menthoros.backend.repository.BatchPlanJobRepository;
import br.com.menthoros.backend.services.helper.LlmConcurrencyLimiter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Worker assíncrono do lote — uma virtual thread por atleta, com a concorrência
 * real ao LLM limitada pelo {@link LlmConcurrencyLimiter}.
 *
 * <p>Bean separado do {@code BatchPlanService} de propósito: o {@code @Async} só
 * é honrado quando chamado através do proxy Spring (nunca por self-invocation).
 */
@Slf4j
@Component
public class BatchPlanProcessor {

    static final String MOTIVO_ATLETA_NAO_ENCONTRADO = "Atleta não encontrado";
    static final String MOTIVO_PLANO_JA_EXISTE = "Plano já existe para esta semana";
    static final String MOTIVO_ERRO_GERACAO = "Erro ao gerar plano — tente novamente";
    static final String MOTIVO_LOTE_INTERROMPIDO =
            "Lote interrompido após falhas seguidas — verifique a integração e tente novamente";

    /**
     * Entrega o comportamento útil de um circuit breaker onde ele importa — o lote —
     * sem dependência nova (ADR-0008): com o provider fora do ar e 50 atletas na fila,
     * seguir em frente seria ~25 minutos segurando conexões para produzir 50 erros
     * previsíveis.
     *
     * <p>"Consecutivas" é aproximado por construção: as tarefas rodam em virtual
     * threads paralelas, então o que se conta são falhas sem nenhum sucesso entre
     * elas, na ordem em que terminam. Basta para o propósito — parar um lote
     * condenado —, e um sucesso zera o contador, de modo que falha esparsa de um
     * atleta específico nunca corta o lote.
     */
    static final int LIMITE_FALHAS_CONSECUTIVAS = 3;

    private final Executor batchPlanExecutor;
    private final BatchPlanJobRepository jobRepository;
    private final AtletaRepository atletaRepository;
    private final PlanoService planoService;
    private final LlmConcurrencyLimiter llmConcurrencyLimiter;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public BatchPlanProcessor(@Qualifier("batchPlanExecutor") Executor batchPlanExecutor,
                              BatchPlanJobRepository jobRepository,
                              AtletaRepository atletaRepository,
                              PlanoService planoService,
                              LlmConcurrencyLimiter llmConcurrencyLimiter,
                              ObjectMapper objectMapper,
                              TransactionTemplate transactionTemplate) {
        this.batchPlanExecutor = batchPlanExecutor;
        this.jobRepository = jobRepository;
        this.atletaRepository = atletaRepository;
        this.planoService = planoService;
        this.llmConcurrencyLimiter = llmConcurrencyLimiter;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Processa o lote em background: submete todas as tasks de uma vez (uma virtual
     * thread por atleta) e finaliza o job quando todas concluem.
     *
     * Idempotent: NO — gera planos e muta o job.
     * Side Effects: chamadas ao LLM, INSERT de planos, UPDATEs no job.
     * Tenant-aware: YES — o TenantContext é setado no método externo E em cada subtask
     *   (virtual threads não herdam o ThreadLocal da thread que as submete).
     */
    @Async("batchPlanExecutor")
    public void processarLote(UUID jobId, List<UUID> atletaIds, ModoGeracaoPlano modo, UUID tenantId) {
        TenantContext.setTenantId(tenantId);
        try {
            jobRepository.atualizarStatus(jobId, BatchJobStatus.EM_PROGRESSO);

            AtomicInteger falhasConsecutivas = new AtomicInteger();
            List<CompletableFuture<ResultadoAtleta>> futures = atletaIds.stream()
                    .map(atletaId -> CompletableFuture.supplyAsync(
                            () -> processarAtleta(jobId, atletaId, modo, tenantId, falhasConsecutivas),
                            batchPlanExecutor))
                    .toList();

            List<ResultadoAtleta> resultados = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            finalizarJob(jobId, tenantId, resultados);
        } catch (Exception e) {
            log.error("[batch-plan] falha inesperada ao processar o lote {}: {}", jobId, e.getMessage(), e);
            fecharEmEmergencia(jobId);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Fecha o job em caso de falha inesperada no processamento — evita que fique
     * preso em EM_PROGRESSO até o recovery de startup. Best-effort: se esta
     * transição também falhar, o recovery ainda o alcança.
     */
    private void fecharEmEmergencia(UUID jobId) {
        try {
            jobRepository.atualizarStatus(jobId, BatchJobStatus.CONCLUIDO_COM_ERROS);
        } catch (Exception ex) {
            log.error("[batch-plan] falha ao fechar o job {} em emergência: {}", jobId, ex.getMessage());
        }
    }

    /**
     * Processa um único atleta. Roda em virtual thread própria (submetida ao
     * {@code batchPlanExecutor}): seta e limpa o TenantContext no seu próprio escopo.
     */
    private ResultadoAtleta processarAtleta(UUID jobId, UUID atletaId, ModoGeracaoPlano modo, UUID tenantId,
                                            AtomicInteger falhasConsecutivas) {
        TenantContext.setTenantId(tenantId);
        try {
            if (falhasConsecutivas.get() >= LIMITE_FALHAS_CONSECUTIVAS) {
                log.warn("[batch-plan] lote {} interrompido: {} falhas seguidas; atleta {} nao sera tentado",
                        jobId, LIMITE_FALHAS_CONSECUTIVAS, atletaId);
                return registrarErro(jobId, atletaId, MOTIVO_LOTE_INTERROMPIDO);
            }
            Optional<Atleta> atletaOpt = atletaRepository.findByIdAndTenantId(atletaId, tenantId);
            if (atletaOpt.isEmpty()) {
                return registrarErro(jobId, atletaId, MOTIVO_ATLETA_NAO_ENCONTRADO);
            }
            String atletaNome = atletaOpt.get().getNome();

            // Fast-path: evita a chamada ao LLM quando já existe plano ativo na semana alvo
            // (regra encapsulada no PlanoService — exclui REJEITADO, tenant-scoped).
            if (planoService.existePlanoParaSemana(atletaId, modo)) {
                return registrarErro(jobId, atletaId, MOTIVO_PLANO_JA_EXISTE);
            }

            // Faixa do lote: cap por assessoria + capacidade do lote + global (fair-llm-concurrency-per-tenant).
            PlanoSemanal plano = llmConcurrencyLimiter.executarLote(tenantId,
                    () -> planoService.gerarPlanoTreino(atletaId, modo));
            falhasConsecutivas.set(0);
            jobRepository.incrementarGerados(jobId);
            return ResultadoAtleta.ok(new BatchGeradoItemDto(atletaId, plano.getId(), atletaNome));

        } catch (PlanoJaExistenteException e) {
            // Corrida entre fast-path e a checagem interna do gerarPlanoTreino.
            return registrarErro(jobId, atletaId, MOTIVO_PLANO_JA_EXISTE);
        } catch (DataIntegrityViolationException e) {
            // Corrida entre lotes concorrentes: o índice único fechou a janela do fast-path. Só a
            // violação DESSE índice é "plano já existe" — outra constraint é erro real de dados e
            // não pode ganhar o rótulo errado no relatório do job.
            if (PlanoJaExistenteException.causadaPeloIndiceDePlanoAtivo(e)) {
                return registrarErro(jobId, atletaId, MOTIVO_PLANO_JA_EXISTE);
            }
            log.warn("[batch-plan] violação de integridade ao gerar plano do atleta {} (job {}): {}",
                    atletaId, jobId, e.getMostSpecificCause().getMessage());
            falhasConsecutivas.incrementAndGet();
            return registrarErro(jobId, atletaId, MOTIVO_ERRO_GERACAO);
        } catch (DomainRuleViolationException e) {
            // Outras violações de regra (ex.: "sem dias disponíveis", compliance do planner esgotado) —
            // erro genérico ao coach; o detalhe fica no log estruturado (nunca no relatório do job).
            log.warn("[batch-plan] regra de domínio ao gerar plano do atleta {} (job {}): {}",
                    atletaId, jobId, e.getMessage());
            falhasConsecutivas.incrementAndGet();
            return registrarErro(jobId, atletaId, MOTIVO_ERRO_GERACAO);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            falhasConsecutivas.incrementAndGet();
            return registrarErro(jobId, atletaId, MOTIVO_ERRO_GERACAO);
        } catch (Exception e) {
            log.warn("[batch-plan] erro ao gerar plano do atleta {} (job {}): {}", atletaId, jobId, e.getMessage());
            falhasConsecutivas.incrementAndGet();
            return registrarErro(jobId, atletaId, MOTIVO_ERRO_GERACAO);
        } finally {
            TenantContext.clear();
        }
    }

    private ResultadoAtleta registrarErro(UUID jobId, UUID atletaId, String motivo) {
        jobRepository.incrementarErros(jobId);
        return ResultadoAtleta.falha(new BatchErroItemDto(atletaId, motivo));
    }

    private void finalizarJob(UUID jobId, UUID tenantId, List<ResultadoAtleta> resultados) {
        List<BatchGeradoItemDto> gerados = resultados.stream()
                .map(ResultadoAtleta::gerado).filter(java.util.Objects::nonNull).toList();
        List<BatchErroItemDto> erros = resultados.stream()
                .map(ResultadoAtleta::erro).filter(java.util.Objects::nonNull).toList();

        BatchJobStatus statusFinal = erros.isEmpty()
                ? BatchJobStatus.CONCLUIDO
                : BatchJobStatus.CONCLUIDO_COM_ERROS;
        String json = serializar(new BatchResultadoJson(gerados, erros, null));

        transactionTemplate.executeWithoutResult(status -> {
            BatchPlanJob job = jobRepository.findByIdAndTenantId(jobId, tenantId).orElseThrow();
            job.setStatus(statusFinal);
            job.setConcluidoEm(Instant.now());
            job.setResultado(json);
            jobRepository.save(job);
        });
    }

    private String serializar(BatchResultadoJson resultado) {
        try {
            return objectMapper.writeValueAsString(resultado);
        } catch (JsonProcessingException e) {
            log.error("[batch-plan] falha ao serializar o resultado do job: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Resultado de um atleta: exatamente um dos campos é não-nulo.
     */
    private record ResultadoAtleta(BatchGeradoItemDto gerado, BatchErroItemDto erro) {
        static ResultadoAtleta ok(BatchGeradoItemDto gerado) {
            return new ResultadoAtleta(gerado, null);
        }

        static ResultadoAtleta falha(BatchErroItemDto erro) {
            return new ResultadoAtleta(null, erro);
        }
    }
}
