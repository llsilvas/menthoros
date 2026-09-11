package br.com.menthoros.backend.services;

import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.entity.BatchPlanJob;
import br.com.menthoros.backend.entity.PlanoSemanal;
import br.com.menthoros.backend.enums.BatchJobStatus;
import br.com.menthoros.backend.enums.ModoGeracaoPlano;
import br.com.menthoros.backend.exception.DomainRuleViolationException;
import br.com.menthoros.backend.exception.LLMException;
import br.com.menthoros.backend.exception.PlanoJaExistenteException;
import br.com.menthoros.backend.multitenancy.TenantContext;
import br.com.menthoros.backend.repository.AtletaRepository;
import br.com.menthoros.backend.repository.BatchPlanJobRepository;
import br.com.menthoros.backend.services.helper.LlmConcurrencyLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BatchPlanProcessor")
class BatchPlanProcessorTest {

    @Mock
    private BatchPlanJobRepository jobRepository;
    @Mock
    private AtletaRepository atletaRepository;
    @Mock
    private PlanoService planoService;
    @Mock
    private LlmConcurrencyLimiter llmConcurrencyLimiter;
    @Mock
    private TransactionTemplate transactionTemplate;

    private BatchPlanProcessor processor;

    private UUID jobId;
    private UUID tenantId;

    @BeforeEach
    void setUp() throws InterruptedException {
        // Executor síncrono (Runnable::run) — as subtasks rodam inline no teste.
        processor = new BatchPlanProcessor(
                Runnable::run, jobRepository, atletaRepository,
                planoService, llmConcurrencyLimiter, new ObjectMapper(), transactionTemplate);

        jobId = UUID.randomUUID();
        tenantId = UUID.randomUUID();

        // finalizarJob: carrega o job (tenant-scoped) e persiste dentro do TransactionTemplate.
        BatchPlanJob job = new BatchPlanJob();
        job.setId(jobId);
        job.setTenantId(tenantId);
        lenient().when(jobRepository.findByIdAndTenantId(jobId, tenantId)).thenReturn(Optional.of(job));
        lenient().doAnswer(inv -> {
            Consumer<TransactionStatus> c = inv.getArgument(0);
            c.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        // limiter roda o supplier passado (só nos testes que chamam gerarPlanoTreino).
        lenient().when(llmConcurrencyLimiter.executarLote(any(), any())).thenAnswer(inv -> {
            Supplier<?> s = inv.getArgument(1);
            return s.get();
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("corte por falhas consecutivas")
    class CorteDoLote {

        @Test
        @DisplayName("após 3 falhas seguidas, os atletas restantes não chamam o LLM (CA6)")
        void cortaAposLimite() {
            UUID a1 = atletaValido("A1");
            UUID a2 = atletaValido("A2");
            UUID a3 = atletaValido("A3");
            // A4 e A5 propositalmente NÃO stubados: se o corte falhar, o lookup do
            // atleta seria chamado e o motivo viria "não encontrado" — o que faria a
            // asserção de MOTIVO_LOTE_INTERROMPIDO abaixo quebrar.
            UUID a4 = UUID.randomUUID();
            UUID a5 = UUID.randomUUID();
            when(planoService.gerarPlanoTreino(any(), any())).thenThrow(new LLMException("provider fora do ar"));

            processor.processarLote(jobId, List.of(a1, a2, a3, a4, a5),
                    ModoGeracaoPlano.PROXIMA_SEMANA, tenantId);

            // 3 tentativas reais; a 4ª e a 5ª são curto-circuitadas.
            verify(planoService, org.mockito.Mockito.times(3)).gerarPlanoTreino(any(), any());
            verify(jobRepository, org.mockito.Mockito.times(5)).incrementarErros(jobId);
            assertThat(statusFinalPersistido()).isEqualTo(BatchJobStatus.CONCLUIDO_COM_ERROS);
            assertThat(errosPersistidos())
                    .extracting(br.com.menthoros.backend.dto.output.BatchJobStatusOutputDto.BatchErroItemDto::motivo)
                    .filteredOn(m -> m.equals(BatchPlanProcessor.MOTIVO_LOTE_INTERROMPIDO))
                    .hasSize(2);
        }

        @Test
        @DisplayName("sucesso no meio zera o contador — falhas esparsas não cortam o lote")
        void sucessoZeraContador() {
            UUID f1 = atletaValido("F1");
            UUID f2 = atletaValido("F2");
            UUID ok = atletaValido("OK");
            UUID f3 = atletaValido("F3");
            UUID f4 = atletaValido("F4");
            when(planoService.gerarPlanoTreino(eq(ok), any())).thenReturn(planoComId());
            when(planoService.gerarPlanoTreino(eq(f1), any())).thenThrow(new LLMException("x"));
            when(planoService.gerarPlanoTreino(eq(f2), any())).thenThrow(new LLMException("x"));
            when(planoService.gerarPlanoTreino(eq(f3), any())).thenThrow(new LLMException("x"));
            when(planoService.gerarPlanoTreino(eq(f4), any())).thenThrow(new LLMException("x"));

            processor.processarLote(jobId, List.of(f1, f2, ok, f3, f4),
                    ModoGeracaoPlano.PROXIMA_SEMANA, tenantId);

            // Nenhum corte: a sequência nunca chega a 3 falhas seguidas.
            verify(planoService, org.mockito.Mockito.times(5)).gerarPlanoTreino(any(), any());
            assertThat(errosPersistidos())
                    .extracting(br.com.menthoros.backend.dto.output.BatchJobStatusOutputDto.BatchErroItemDto::motivo)
                    .doesNotContain(BatchPlanProcessor.MOTIVO_LOTE_INTERROMPIDO);
        }

        @Test
        @DisplayName("plano já existente não conta como falha de geração")
        void planoJaExistenteNaoConta() {
            UUID a1 = atletaValido("A1");
            UUID a2 = atletaValido("A2");
            UUID a3 = atletaValido("A3");
            UUID a4 = atletaValido("A4");
            when(planoService.existePlanoParaSemana(any(), any())).thenReturn(true);

            processor.processarLote(jobId, List.of(a1, a2, a3, a4),
                    ModoGeracaoPlano.PROXIMA_SEMANA, tenantId);

            // Resultado de negócio esperado, não degradação do provider — não corta.
            assertThat(errosPersistidos())
                    .extracting(br.com.menthoros.backend.dto.output.BatchJobStatusOutputDto.BatchErroItemDto::motivo)
                    .containsOnly(BatchPlanProcessor.MOTIVO_PLANO_JA_EXISTE);
        }
    }

    @Nested
    @DisplayName("processarLote")
    class ProcessarLote {

        @Test
        @DisplayName("gera planos para todos os atletas válidos e conclui como CONCLUIDO")
        void geraTodosComSucesso() {
            UUID a1 = atletaValido("Ana");
            UUID a2 = atletaValido("Bia");
            when(planoService.gerarPlanoTreino(any(), any())).thenReturn(planoComId());

            processor.processarLote(jobId, List.of(a1, a2), ModoGeracaoPlano.PROXIMA_SEMANA, tenantId);

            verify(jobRepository, org.mockito.Mockito.times(2)).incrementarGerados(jobId);
            verify(jobRepository, never()).incrementarErros(jobId);
            assertThat(statusFinalPersistido()).isEqualTo(BatchJobStatus.CONCLUIDO);
            assertThat(geradosPersistidos())
                    .extracting(br.com.menthoros.backend.dto.output.BatchJobStatusOutputDto.BatchGeradoItemDto::atletaNome)
                    .containsExactlyInAnyOrder("Ana", "Bia");
        }

        @Test
        @DisplayName("registra erro individual sem abortar o lote (CONCLUIDO_COM_ERROS)")
        void erroIndividualNaoAborta() {
            UUID ok = atletaValido("Ana");
            UUID falha = atletaValido("Bia");
            when(planoService.gerarPlanoTreino(eq(ok), any())).thenReturn(planoComId());
            when(planoService.gerarPlanoTreino(eq(falha), any()))
                    .thenThrow(new LLMException("timeout"));

            processor.processarLote(jobId, List.of(ok, falha), ModoGeracaoPlano.PROXIMA_SEMANA, tenantId);

            verify(jobRepository).incrementarGerados(jobId);
            verify(jobRepository).incrementarErros(jobId);
            assertThat(statusFinalPersistido()).isEqualTo(BatchJobStatus.CONCLUIDO_COM_ERROS);
        }

        @Test
        @DisplayName("atleta de outro tenant → motivo 'Atleta não encontrado', sem chamar o LLM")
        void atletaOutroTenant() {
            UUID id = UUID.randomUUID();
            when(atletaRepository.findByIdAndTenantId(id, tenantId)).thenReturn(Optional.empty());

            processor.processarLote(jobId, List.of(id), ModoGeracaoPlano.PROXIMA_SEMANA, tenantId);

            verify(planoService, never()).gerarPlanoTreino(any(), any());
            verify(jobRepository).incrementarErros(jobId);
            assertThat(motivoErroPersistido()).isEqualTo(BatchPlanProcessor.MOTIVO_ATLETA_NAO_ENCONTRADO);
        }

        @Test
        @DisplayName("atleta com plano ativo na semana → 'Plano já existe' via fast-path, sem LLM")
        void planoJaExisteFastPath() {
            UUID id = atletaValido("Ana");
            when(planoService.existePlanoParaSemana(id, ModoGeracaoPlano.PROXIMA_SEMANA)).thenReturn(true);

            processor.processarLote(jobId, List.of(id), ModoGeracaoPlano.PROXIMA_SEMANA, tenantId);

            verify(planoService, never()).gerarPlanoTreino(any(), any());
            verify(jobRepository).incrementarErros(jobId);
            assertThat(motivoErroPersistido()).isEqualTo(BatchPlanProcessor.MOTIVO_PLANO_JA_EXISTE);
        }

        @Test
        @DisplayName("DataIntegrityViolationException (corrida de lotes) → 'Plano já existe'")
        void corridaEntreLotes() {
            UUID id = atletaValido("Ana");
            when(planoService.gerarPlanoTreino(eq(id), any()))
                    .thenThrow(new DataIntegrityViolationException("uk_plano_semanal_atleta_semana_ativo"));

            processor.processarLote(jobId, List.of(id), ModoGeracaoPlano.PROXIMA_SEMANA, tenantId);

            verify(jobRepository).incrementarErros(jobId);
            assertThat(motivoErroPersistido()).isEqualTo(BatchPlanProcessor.MOTIVO_PLANO_JA_EXISTE);
        }

        @Test
        @DisplayName("DataIntegrityViolationException de OUTRA constraint → 'Erro ao gerar plano', não 'já existe'")
        void outraConstraintNaoEMascarada() {
            UUID id = atletaValido("Ana");
            when(planoService.gerarPlanoTreino(eq(id), any()))
                    .thenThrow(new DataIntegrityViolationException("violates foreign key constraint \"fk_outra\""));

            processor.processarLote(jobId, List.of(id), ModoGeracaoPlano.PROXIMA_SEMANA, tenantId);

            verify(jobRepository).incrementarErros(jobId);
            assertThat(motivoErroPersistido()).isEqualTo(BatchPlanProcessor.MOTIVO_ERRO_GERACAO);
        }

        @Test
        @DisplayName("PlanoJaExistenteException (corrida fast-path/checagem interna) → 'Plano já existe'")
        void dupViaExcecaoTipada() {
            UUID id = atletaValido("Ana");
            when(planoService.gerarPlanoTreino(eq(id), any()))
                    .thenThrow(new PlanoJaExistenteException("Já existe um plano semanal ativo"));

            processor.processarLote(jobId, List.of(id), ModoGeracaoPlano.PROXIMA_SEMANA, tenantId);

            assertThat(motivoErroPersistido()).isEqualTo(BatchPlanProcessor.MOTIVO_PLANO_JA_EXISTE);
        }

        @Test
        @DisplayName("DomainRuleViolationException não-duplicidade → 'Erro ao gerar plano'")
        void erroDominioNaoDuplicidade() {
            UUID id = atletaValido("Ana");
            when(planoService.gerarPlanoTreino(eq(id), any()))
                    .thenThrow(new DomainRuleViolationException("Não há dias disponíveis para treino"));

            processor.processarLote(jobId, List.of(id), ModoGeracaoPlano.PROXIMA_SEMANA, tenantId);

            assertThat(motivoErroPersistido()).isEqualTo(BatchPlanProcessor.MOTIVO_ERRO_GERACAO);
        }

        @Test
        @DisplayName("marca EM_PROGRESSO no início e limpa o TenantContext ao final")
        void transicaoStatusELimpezaTenant() {
            UUID id = atletaValido("Ana");
            when(planoService.gerarPlanoTreino(any(), any())).thenReturn(planoComId());

            processor.processarLote(jobId, List.of(id), ModoGeracaoPlano.PROXIMA_SEMANA, tenantId);

            verify(jobRepository).atualizarStatus(jobId, BatchJobStatus.EM_PROGRESSO);
            assertThat(TenantContext.getTenantId()).isNull();
        }
    }

    @Nested
    @DisplayName("compliance do planner no lote (planner-engine-enforcement §6)")
    class ComplianceNoLote {

        // A falha de compliance (estagio 1 esgotado ou estagio 2 fail-closed) chega ao lote como
        // DomainRuleViolationException — a mesma que gerarComResiliencia lanca ao esgotar o orcamento.
        private static final String MSG_COMPLIANCE_INTERNA =
                "Plano diverge da estrutura prescrita pelo planner: FASE_DIVERGENTE: esperado BASE; "
                        + "TSS_FORA_DA_FAIXA: 600 fora de 350±10%";

        @Test
        @DisplayName("um atleta falha compliance apos retry → erro individual sanitizado; o outro conclui; CONCLUIDO_COM_ERROS")
        void falhaComplianceIndividualNaoAbortaLote() {
            UUID ok = atletaValido("Ana");
            UUID falha = atletaValido("Bia");
            when(planoService.gerarPlanoTreino(eq(ok), any())).thenReturn(planoComId());
            when(planoService.gerarPlanoTreino(eq(falha), any()))
                    .thenThrow(new DomainRuleViolationException(MSG_COMPLIANCE_INTERNA));

            processor.processarLote(jobId, List.of(ok, falha), ModoGeracaoPlano.PROXIMA_SEMANA, tenantId);

            verify(jobRepository).incrementarGerados(jobId);
            verify(jobRepository).incrementarErros(jobId);
            assertThat(statusFinalPersistido()).isEqualTo(BatchJobStatus.CONCLUIDO_COM_ERROS);

            assertThat(geradosPersistidos())
                    .extracting(br.com.menthoros.backend.dto.output.BatchJobStatusOutputDto.BatchGeradoItemDto::atletaNome)
                    .containsExactly("Ana");

            // Motivo sanitizado no relatorio; o detalhe tecnico (keys/mensagens de PlannerViolation)
            // NUNCA vaza para o job — fica so no log estruturado.
            assertThat(errosPersistidos())
                    .extracting(br.com.menthoros.backend.dto.output.BatchJobStatusOutputDto.BatchErroItemDto::motivo)
                    .containsExactly(BatchPlanProcessor.MOTIVO_ERRO_GERACAO);
            assertThat(errosPersistidos().get(0).motivo())
                    .doesNotContain("FASE_DIVERGENTE", "TSS_FORA_DA_FAIXA", "skeleton", "planner");
        }

        @Test
        @DisplayName("falha de compliance conta para o corte por falhas consecutivas (degradacao real)")
        void falhaComplianceContaNoCorte() {
            UUID a1 = atletaValido("A1");
            UUID a2 = atletaValido("A2");
            UUID a3 = atletaValido("A3");
            UUID a4 = UUID.randomUUID(); // nao stubado: so alcançado se o corte falhar
            when(planoService.gerarPlanoTreino(any(), any()))
                    .thenThrow(new DomainRuleViolationException(MSG_COMPLIANCE_INTERNA));

            processor.processarLote(jobId, List.of(a1, a2, a3, a4), ModoGeracaoPlano.PROXIMA_SEMANA, tenantId);

            verify(planoService, org.mockito.Mockito.times(3)).gerarPlanoTreino(any(), any());
            assertThat(errosPersistidos())
                    .extracting(br.com.menthoros.backend.dto.output.BatchJobStatusOutputDto.BatchErroItemDto::motivo)
                    .filteredOn(m -> m.equals(BatchPlanProcessor.MOTIVO_LOTE_INTERROMPIDO))
                    .hasSize(1);
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private UUID atletaValido(String nome) {
        UUID id = UUID.randomUUID();
        Atleta atleta = new Atleta();
        atleta.setNome(nome);
        when(atletaRepository.findByIdAndTenantId(id, tenantId)).thenReturn(Optional.of(atleta));
        return id;
    }

    private PlanoSemanal planoComId() {
        PlanoSemanal plano = new PlanoSemanal();
        plano.setId(UUID.randomUUID());
        return plano;
    }

    private BatchJobStatus statusFinalPersistido() {
        return jobPersistido().getStatus();
    }

    private String motivoErroPersistido() {
        return resultadoPersistido().erros().get(0).motivo();
    }

    private List<br.com.menthoros.backend.dto.output.BatchJobStatusOutputDto.BatchGeradoItemDto> geradosPersistidos() {
        return resultadoPersistido().gerados();
    }

    private List<br.com.menthoros.backend.dto.output.BatchJobStatusOutputDto.BatchErroItemDto> errosPersistidos() {
        return resultadoPersistido().erros();
    }

    private BatchResultadoJson resultadoPersistido() {
        BatchPlanJob job = jobPersistido();
        try {
            return new ObjectMapper().readValue(job.getResultado(), BatchResultadoJson.class);
        } catch (Exception e) {
            throw new AssertionError("resultado inválido: " + job.getResultado(), e);
        }
    }

    private BatchPlanJob jobPersistido() {
        ArgumentCaptor<BatchPlanJob> captor = ArgumentCaptor.forClass(BatchPlanJob.class);
        verify(jobRepository).save(captor.capture());
        return captor.getValue();
    }
}
