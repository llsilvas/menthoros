package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.AbstractIntegrationTest;
import br.com.menthoros.backend.dto.input.BatchGeracaoPlanoInputDto;
import br.com.menthoros.backend.dto.llm.PlanoSemanalLlmDto;
import br.com.menthoros.backend.dto.llm.TreinoPlanejadoLlmDto;
import br.com.menthoros.backend.dto.output.BatchJobStatusOutputDto;
import br.com.menthoros.backend.entity.Assessoria;
import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.enums.AtletaStatus;
import br.com.menthoros.backend.enums.BatchJobStatus;
import br.com.menthoros.backend.enums.DiaSemana;
import br.com.menthoros.backend.enums.ModoGeracaoPlano;
import br.com.menthoros.backend.enums.NivelExperiencia;
import br.com.menthoros.backend.enums.PlanoAssessoria;
import br.com.menthoros.backend.multitenancy.TenantContext;
import br.com.menthoros.backend.repository.AssessoriaRepository;
import br.com.menthoros.backend.repository.AtletaRepository;
import br.com.menthoros.backend.repository.PlanoSemanalRepository;
import br.com.menthoros.backend.services.BatchPlanService;
import br.com.menthoros.backend.services.IaService;
import br.com.menthoros.backend.services.PlanoService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Prova de carga de refactor-llm-call-outside-transaction (CA1 + CA2) no cenário das assessorias
 * fundadoras (design.md D0): <b>10 assessorias × 10 atletas = 100 gerações</b> disparadas na mesma
 * janela, pelo lote real ({@code iniciarLote} → {@code @Async processarLote} →
 * {@code LlmConcurrencyLimiter}).
 *
 * <p>Condições deliberadamente hostis: pool do Hikari com <b>2</b> conexões e
 * {@code llm-concorrencia} = <b>10</b>. Antes desta change cada chamada ao LLM segurava uma
 * conexão pela duração inteira — com 10 em voo e pool 2, o lote travava no
 * {@code connection-timeout} e derrubava o app junto. Depois dela, o LLM roda sem conexão em
 * posse e o pool volta a ser transiente.
 *
 * <p>O LLM é um stub com latência fixa que também instrumenta: quantas chamadas estão em voo,
 * se há transação ativa na thread (CA1) e quantas conexões o pool tem ativas naquele instante.
 */
@DisplayName("Lote das fundadoras — 10 assessorias × 10 atletas com pool 2 e concorrência 10")
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=3000",
        "app.batch-plan.llm-concorrencia=10",
        "app.batch-plan.llm-concorrencia-por-tenant=2",
        "app.batch-plan.llm-reserva-interativa=1",
        "onboarding.migrate-existing.enabled=false"
})
class LotePlanosFundadorasIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(LotePlanosFundadorasIT.class);

    private static final int ASSESSORIAS = 10;
    private static final int ATLETAS_POR_ASSESSORIA = 10;
    private static final int POOL = 2;
    private static final int LLM_CONCORRENCIA = 10;
    private static final int RESERVA_INTERATIVA = 1;
    private static final int CAPACIDADE_LOTE = LLM_CONCORRENCIA - RESERVA_INTERATIVA;
    private static final long LATENCIA_LLM_MS = 250;

    @MockitoBean private IaService iaService;

    @Autowired private BatchPlanService batchPlanService;
    @Autowired private PlanoService planoService;
    @Autowired private AssessoriaRepository assessoriaRepository;
    @Autowired private AtletaRepository atletaRepository;
    @Autowired private PlanoSemanalRepository planoSemanalRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private DataSource dataSource;

    private final AtomicInteger llmEmVoo = new AtomicInteger();
    private final AtomicInteger picoLlmEmVoo = new AtomicInteger();
    private final AtomicInteger chamadasComTransacaoAtiva = new AtomicInteger();
    private final AtomicInteger picoConexoesAtivasDuranteLlm = new AtomicInteger();
    private final AtomicInteger totalChamadasLlm = new AtomicInteger();
    // Justiça (fair-llm-concurrency-per-tenant): em voo por assessoria, medido pelo TenantContext
    // da thread dentro do stub — o BatchPlanProcessor o seta por virtual thread.
    private final java.util.concurrent.ConcurrentHashMap<UUID, AtomicInteger> emVooPorTenant = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicInteger picoEmVooDeUmTenant = new AtomicInteger();
    private final AtomicInteger picoTenantsSimultaneos = new AtomicInteger();

    @BeforeEach
    void instrumentarLlm() {
        when(iaService.geraPlanoSemanalAvancado(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    int emVoo = llmEmVoo.incrementAndGet();
                    picoLlmEmVoo.accumulateAndGet(emVoo, Math::max);
                    totalChamadasLlm.incrementAndGet();
                    if (TransactionSynchronizationManager.isActualTransactionActive()) {
                        chamadasComTransacaoAtiva.incrementAndGet();
                    }
                    picoConexoesAtivasDuranteLlm.accumulateAndGet(conexoesAtivas(), Math::max);
                    UUID tenant = TenantContext.getTenantId();
                    AtomicInteger doTenant = tenant == null ? null
                            : emVooPorTenant.computeIfAbsent(tenant, t -> new AtomicInteger());
                    if (doTenant != null) {
                        picoEmVooDeUmTenant.accumulateAndGet(doTenant.incrementAndGet(), Math::max);
                        picoTenantsSimultaneos.accumulateAndGet(
                                (int) emVooPorTenant.values().stream().filter(c -> c.get() > 0).count(),
                                Math::max);
                    }
                    try {
                        Thread.sleep(LATENCIA_LLM_MS);
                        return planoDeUmTreino();
                    } finally {
                        llmEmVoo.decrementAndGet();
                        if (doTenant != null) {
                            doTenant.decrementAndGet();
                        }
                    }
                });
    }

    @AfterEach
    void limpar() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("100 planos em 10 lotes simultâneos: tudo persiste, 10 LLM em voo, zero transação dentro do LLM")
    void dezAssessoriasDezAtletas() throws Exception {
        Map<UUID, List<UUID>> atletasPorAssessoria = new LinkedHashMap<>();
        for (int i = 1; i <= ASSESSORIAS; i++) {
            Assessoria assessoria = criarAssessoria("Fundadora " + i);
            List<UUID> ids = new ArrayList<>();
            for (int j = 1; j <= ATLETAS_POR_ASSESSORIA; j++) {
                ids.add(criarAtleta(assessoria, "Atleta " + i + "." + j).getId());
            }
            atletasPorAssessoria.put(assessoria.getId(), ids);
        }

        // Todos os treinadores clicam "gerar lote" na mesma janela (domingo à noite).
        Map<UUID, UUID> jobPorAssessoria = new LinkedHashMap<>();
        Instant inicio = Instant.now();
        for (Map.Entry<UUID, List<UUID>> e : atletasPorAssessoria.entrySet()) {
            UUID tenantId = e.getKey();
            TenantContext.setTenantId(tenantId);
            jobPorAssessoria.put(tenantId, batchPlanService.iniciarLote(
                    new BatchGeracaoPlanoInputDto(e.getValue(), ModoGeracaoPlano.PROXIMA_SEMANA), tenantId).jobId());
            TenantContext.clear();
        }

        Map<UUID, BatchJobStatusOutputDto> statusFinal = new LinkedHashMap<>();
        Map<UUID, Duration> duracaoPorAssessoria = new LinkedHashMap<>();
        Instant limite = inicio.plusSeconds(120);
        while (statusFinal.size() < ASSESSORIAS && Instant.now().isBefore(limite)) {
            for (Map.Entry<UUID, UUID> e : jobPorAssessoria.entrySet()) {
                if (statusFinal.containsKey(e.getKey())) {
                    continue;
                }
                BatchJobStatusOutputDto status = batchPlanService.consultarStatus(e.getValue(), e.getKey());
                if (status.status() == BatchJobStatus.CONCLUIDO || status.status() == BatchJobStatus.CONCLUIDO_COM_ERROS) {
                    statusFinal.put(e.getKey(), status);
                    duracaoPorAssessoria.put(e.getKey(), Duration.between(inicio, Instant.now()));
                }
            }
            Thread.sleep(100);
        }
        Duration total = Duration.between(inicio, Instant.now());

        // --- relatório (lido pelo founder na task 5.1) ---
        long teorico = (long) ASSESSORIAS * ATLETAS_POR_ASSESSORIA * LATENCIA_LLM_MS / CAPACIDADE_LOTE;
        log.info("[fundadoras] 100 planos em {} ms (teórico com concorrência {}: {} ms); pico LLM em voo = {}; " +
                        "chamadas com transação ativa = {}; pico de conexões ativas durante o LLM = {} (pool {})",
                total.toMillis(), LLM_CONCORRENCIA, teorico, picoLlmEmVoo.get(),
                chamadasComTransacaoAtiva.get(), picoConexoesAtivasDuranteLlm.get(), POOL);
        int n = 0;
        for (Map.Entry<UUID, Duration> e : duracaoPorAssessoria.entrySet()) {
            BatchJobStatusOutputDto s = statusFinal.get(e.getKey());
            log.info("[fundadoras] assessoria #{} terminou em {} ms — status {}, gerados {}, erros {}",
                    ++n, e.getValue().toMillis(), s.status(), s.gerados(), s.erros());
        }

        // --- asserções ---
        assertThat(statusFinal).as("todos os 10 lotes terminaram dentro do limite").hasSize(ASSESSORIAS);
        assertThat(statusFinal.values())
                .as("nenhum lote terminou com erro — com pool 2 e 10 LLM em voo, antes da change isso era connection-timeout")
                .allSatisfy(s -> {
                    assertThat(s.status()).isEqualTo(BatchJobStatus.CONCLUIDO);
                    assertThat(s.gerados()).isEqualTo(ATLETAS_POR_ASSESSORIA);
                    assertThat(s.erros()).isZero();
                });
        for (Map.Entry<UUID, List<UUID>> e : atletasPorAssessoria.entrySet()) {
            for (UUID atletaId : e.getValue()) {
                assertThat(planoSemanalRepository.findAtivosPorAtleta(atletaId, e.getKey()))
                        .as("um plano ativo por atleta").hasSize(1);
            }
        }
        assertThat(totalChamadasLlm.get()).as("uma chamada de LLM por atleta").isEqualTo(ASSESSORIAS * ATLETAS_POR_ASSESSORIA);
        assertThat(chamadasComTransacaoAtiva.get()).as("CA1: nenhuma chamada ao LLM dentro de transação").isZero();
        assertThat(picoLlmEmVoo.get())
                .as("a concorrência do LLM é limitada pelo semáforo, não pelo pool — e o lote só usa a capacidade dele (global − reserva interativa)")
                .isEqualTo(CAPACIDADE_LOTE);
        // Justiça (fair-llm-concurrency-per-tenant, CA1): contadores determinísticos, não duração.
        assertThat(picoEmVooDeUmTenant.get())
                .as("nenhuma assessoria passa do cap por tenant")
                .isLessThanOrEqualTo(2);
        assertThat(picoTenantsSimultaneos.get())
                .as("com global 10 e cap 2, pelo menos 5 assessorias progridem ao mesmo tempo")
                .isGreaterThanOrEqualTo(5);
        long primeira = duracaoPorAssessoria.values().stream().mapToLong(Duration::toMillis).min().orElse(0);
        long ultima = duracaoPorAssessoria.values().stream().mapToLong(Duration::toMillis).max().orElse(0);
        log.info("[fundadoras] justiça: razão última/primeira = {} (baseline pré-change: 2,6x) — métrica de log, não asserção",
                primeira == 0 ? "n/a" : String.format("%.2fx", (double) ultima / primeira));
        // Com 100 tarefas em voo, sempre há alguma fase de leitura/escrita usando o pool no instante
        // da amostra — a prova exata de "zero conexão presa durante o LLM" é o platô do teste A/B.
        assertThat(picoConexoesAtivasDuranteLlm.get())
                .as("as conexões ativas nunca passam do pool — e o pool é menor que a concorrência do LLM")
                .isLessThanOrEqualTo(POOL);
    }

    @Test
    @DisplayName("A/B do acoplamento: dentro da transação só `pool` gerações chegam ao LLM e o pool fica cheio; fora, todas chegam e o pool fica em zero")
    void acoplamentoAntesEDepois() throws Exception {
        Plato dentro = plato(true);
        Plato fora = plato(false);

        log.info("[fundadoras] platô no LLM — dentro da transação (antes da change): {} gerações, {} conexões ativas; fora (depois): {} gerações, {} conexões ativas",
                dentro.noLlm(), dentro.conexoesAtivas(), fora.noLlm(), fora.conexoesAtivas());

        assertThat(dentro.noLlm())
                .as("simulando o código antigo, cada geração segura uma conexão e o pool de %d é o teto", POOL)
                .isLessThanOrEqualTo(POOL);
        assertThat(dentro.conexoesAtivas())
                .as("e as que chegaram estão segurando o pool inteiro enquanto esperam o modelo")
                .isEqualTo(POOL);
        assertThat(fora.noLlm())
                .as("com a fronteira aberta, todas as %d gerações estão no LLM ao mesmo tempo com o mesmo pool", LLM_CONCORRENCIA)
                .isEqualTo(LLM_CONCORRENCIA);
        assertThat(fora.conexoesAtivas())
                .as("CA1/CA2 exatos: com as 10 paradas dentro do LLM, nenhuma conexão do pool está em posse")
                .isZero();
    }

    @Test
    @DisplayName("o gerar interativo entra no LLM enquanto os lotes saturam a faixa deles (reserva)")
    void interativoNaoEsperaOLote() throws Exception {
        // 5 assessorias × 2 atletas = 10 gerações de lote; capacidade do lote = 10 − reserva 1 = 9
        // em voo. O clique interativo usa o permit reservado e entra sem esperar o lote drenar.
        Map<UUID, List<UUID>> lotes = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) {
            Assessoria a = criarAssessoria("Reserva " + i);
            lotes.put(a.getId(), List.of(
                    criarAtleta(a, "Atleta R" + i + ".1").getId(),
                    criarAtleta(a, "Atleta R" + i + ".2").getId()));
        }
        Assessoria doCoach = criarAssessoria("Coach clicando");
        UUID atletaDoCoach = criarAtleta(doCoach, "Atleta do clique").getId();

        java.util.Set<UUID> tenantsDoLote = lotes.keySet();
        CountDownLatch loteSegura = new CountDownLatch(1);
        // Capacidade do lote = global − reserva = 9: só 9 gerações chegam ao stub enquanto o latch
        // segura; a chegada é sinalizada por latch, não por polling (achado do clean-code no QA).
        CountDownLatch noveNoLlm = new CountDownLatch(9);
        AtomicInteger loteNoLlm = new AtomicInteger();
        when(iaService.geraPlanoSemanalAvancado(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    if (tenantsDoLote.contains(TenantContext.getTenantId())) {
                        loteNoLlm.incrementAndGet();
                        noveNoLlm.countDown();
                        if (!loteSegura.await(20, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("latch do lote não liberado");
                        }
                    }
                    return planoDeUmTreino();
                });

        try {
            for (Map.Entry<UUID, List<UUID>> e : lotes.entrySet()) {
                TenantContext.setTenantId(e.getKey());
                jobIdPorTenant.put(e.getKey(), batchPlanService.iniciarLote(
                        new BatchGeracaoPlanoInputDto(e.getValue(), ModoGeracaoPlano.PROXIMA_SEMANA), e.getKey()).jobId());
                TenantContext.clear();
            }
            assertThat(noveNoLlm.await(10, TimeUnit.SECONDS))
                    .as("o lote saturou a capacidade dele (global − reserva)").isTrue();
            assertThat(loteNoLlm.get()).isEqualTo(9);

            TenantContext.setTenantId(doCoach.getId());
            Instant antes = Instant.now();
            planoService.gerarPlanoTreino(atletaDoCoach, ModoGeracaoPlano.PROXIMA_SEMANA);
            Duration espera = Duration.between(antes, Instant.now());
            TenantContext.clear();

            log.info("[fundadoras] interativo entrou com o lote saturado em {} ms", espera.toMillis());
            assertThat(espera).as("CA2: o interativo usa o permit reservado, não espera o lote drenar")
                    .isLessThan(Duration.ofSeconds(2));
        } finally {
            loteSegura.countDown();
        }

        // Drenar: os lotes são @Async e continuariam rodando dentro do PRÓXIMO teste da classe,
        // vazando chamadas de LLM para os contadores dele. Espera todos terminarem.
        Instant limite = Instant.now().plusSeconds(30);
        for (UUID tenantId : lotes.keySet()) {
            while (Instant.now().isBefore(limite)) {
                BatchJobStatusOutputDto st = jobsPorTenant(tenantId);
                if (st != null && (st.status() == BatchJobStatus.CONCLUIDO
                        || st.status() == BatchJobStatus.CONCLUIDO_COM_ERROS)) {
                    break;
                }
                Thread.sleep(100);
            }
        }
    }

    private final Map<UUID, UUID> jobIdPorTenant = new LinkedHashMap<>();

    private BatchJobStatusOutputDto jobsPorTenant(UUID tenantId) {
        UUID jobId = jobIdPorTenant.get(tenantId);
        return jobId == null ? null : batchPlanService.consultarStatus(jobId, tenantId);
    }

    /** Medida no instante em que as gerações estão paradas dentro do stub do LLM. */
    private record Plato(int noLlm, int conexoesAtivas) {
    }

    /**
     * Dispara {@code LLM_CONCORRENCIA} gerações simultâneas de um tenant, segura todas dentro do
     * stub do LLM e mede o platô: quantas chegaram e quantas conexões o pool tem ativas nesse
     * instante. Com {@code dentroDaTransacao}, cada geração é envolvida por um
     * {@code TransactionTemplate} — o que o antigo {@code @Transactional} de
     * {@code gerarPlanoTreino} fazia — e as que não conseguem conexão ficam presas no
     * {@code connection-timeout} de 3s enquanto as outras seguram o pool.
     */
    private Plato plato(boolean dentroDaTransacao) throws Exception {
        Assessoria assessoria = criarAssessoria(dentroDaTransacao ? "A/B dentro" : "A/B fora");
        List<UUID> atletas = new ArrayList<>();
        for (int j = 1; j <= LLM_CONCORRENCIA; j++) {
            atletas.add(criarAtleta(assessoria, "Atleta A/B " + j).getId());
        }
        UUID tenantId = assessoria.getId();

        CountDownLatch segura = new CountDownLatch(1);
        AtomicInteger noLlm = new AtomicInteger();
        when(iaService.geraPlanoSemanalAvancado(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    noLlm.incrementAndGet();
                    segura.await(10, TimeUnit.SECONDS);
                    return planoDeUmTreino();
                });

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> futuros = new ArrayList<>();
        try {
            for (UUID atletaId : atletas) {
                futuros.add(executor.submit(() -> {
                    TenantContext.setTenantId(tenantId);
                    try {
                        if (dentroDaTransacao) {
                            transactionTemplate.execute(s -> planoService.gerarPlanoTreino(atletaId, ModoGeracaoPlano.PROXIMA_SEMANA));
                        } else {
                            planoService.gerarPlanoTreino(atletaId, ModoGeracaoPlano.PROXIMA_SEMANA);
                        }
                    } catch (Exception e) {
                        // No modo "dentro", as gerações sem conexão morrem no connection-timeout — é o
                        // comportamento antigo que se quer expor, não um erro do teste.
                        log.debug("[fundadoras] geração falhou ({}): {}", dentroDaTransacao ? "dentro" : "fora", e.toString());
                    } finally {
                        TenantContext.clear();
                    }
                    return null;
                }));
            }

            // Janela generosa para todas chegarem ao stub; no modo "dentro" as que não têm conexão
            // não chegam nunca — é isso que a contagem mede.
            long fim = System.currentTimeMillis() + 2000;
            while (noLlm.get() < LLM_CONCORRENCIA && System.currentTimeMillis() < fim) {
                Thread.sleep(50);
            }
            // Todas as que vão chegar já chegaram e estão paradas no latch: o que o pool mostra
            // agora é só o que a geração segura enquanto espera o modelo.
            Thread.sleep(200);
            Plato plato = new Plato(noLlm.get(), conexoesAtivas());
            segura.countDown();
            for (Future<?> f : futuros) {
                f.get(30, TimeUnit.SECONDS);
            }
            return plato;
        } finally {
            executor.shutdownNow();
        }
    }

    private int conexoesAtivas() {
        if (dataSource instanceof HikariDataSource hikari && hikari.getHikariPoolMXBean() != null) {
            return hikari.getHikariPoolMXBean().getActiveConnections();
        }
        return -1;
    }

    private Assessoria criarAssessoria(String nome) {
        Assessoria assessoria = new Assessoria();
        assessoria.setNome(nome);
        assessoria.setDominio("fund-" + UUID.randomUUID());
        assessoria.setPlano(PlanoAssessoria.BASIC);
        return assessoriaRepository.save(assessoria);
    }

    private Atleta criarAtleta(Assessoria assessoria, String nome) {
        Atleta atleta = new Atleta();
        atleta.setNome(nome);
        atleta.setObjetivo("Terminar os 10k");
        atleta.setNivelExperiencia(NivelExperiencia.INICIANTE);
        atleta.setAtivo(AtletaStatus.ATIVO);
        atleta.setDiasDisponiveis(List.of(DiaSemana.SEGUNDA, DiaSemana.QUARTA, DiaSemana.SABADO));
        atleta.setDiaPreferidoLongo(DiaSemana.SABADO);
        atleta.setAssessoria(assessoria);
        return atletaRepository.save(atleta);
    }

    private static PlanoSemanalLlmDto planoDeUmTreino() {
        TreinoPlanejadoLlmDto treino = new TreinoPlanejadoLlmDto(
                "SEGUNDA", "FACIL", "130-140 bpm", 40, 1.0, 4,
                "Base aeróbica", "45", 8.0, "5:40", List.of());
        return new PlanoSemanalLlmDto(8.0, 8.0, null, null, "PLANEJADO", "Semana de base", List.of(treino));
    }
}
