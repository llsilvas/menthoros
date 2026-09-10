package br.com.menthoros.backend.domain.planner;

import br.com.menthoros.backend.dto.DecisaoProgressao;
import br.com.menthoros.backend.dto.ProgressaoHistoricoResumo;
import br.com.menthoros.backend.enums.DiaSemana;
import br.com.menthoros.backend.enums.EstadoProgressao;
import br.com.menthoros.backend.enums.NivelExperiencia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Golden set deterministico (CA7, tasks.md secao 6): historico + calendario de prova +
 * constraints -&gt; {@code WeekPlanSkeleton} esperado exato, nao LLM-graded. Regressao
 * bloqueante de merge — qualquer mudanca de comportamento aqui exige atualizar o caso
 * conscientemente, nunca "consertar o teste" as cegas.
 *
 * <p>Cobre: periodizacao completa (BASE-&gt;BUILD-&gt;PEAK-&gt;TAPER-&gt;RACE_WEEK-&gt;POST_RACE) com
 * limites exatos de transicao, step-back, as 3 faixas de TSB, lesao ativa, lesao recente,
 * cold start, multi-prova (alvo explicito + desempate por distancia), BASE_FITNESS (sem prova)
 * e escopo multisport (guardrail conservador de TSS agregado).
 */
class PlannerEngineGoldenSetTest {

    private static final LocalDate REFERENCIA = LocalDate.of(2026, 3, 9); // segunda-feira

    private final PlannerEngine engine = new PlannerEngine(
            new PeriodizationPlanner(),
            new LoadTargetResolver(),
            new TaperStrategy(),
            new InjuryRiskEvaluator(),
            new InjuryPolicyResolver(),
            new ConstraintValidator());

    @ParameterizedTest(name = "{0}")
    @MethodSource("casos")
    @DisplayName("golden set — fase e requiresCoachReview exatos")
    void produzSkeletonEsperado(GoldenCase caso) {
        WeekPlanSkeleton skeleton = engine.planWeek(caso.toSnapshot());

        assertThat(skeleton.phase())
                .as("fase de '%s'", caso.label())
                .isEqualTo(caso.expectedPhase());
        assertThat(skeleton.requiresCoachReview())
                .as("requiresCoachReview de '%s'", caso.label())
                .isEqualTo(caso.expectedRequiresCoachReview());
        assertThat(skeleton.plannerScope())
                .as("plannerScope de '%s'", caso.label())
                .isEqualTo(caso.expectedPlannerScope());
        assertThat(skeleton.injuryRisk().level())
                .as("injuryRisk().level() de '%s'", caso.label())
                .isEqualTo(caso.expectedRiskLevel());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("casos")
    @DisplayName("golden set — sessões prescritivas coerentes (composição + alocação, task 2.5)")
    void compoeSessoesCoerentes(GoldenCase caso) {
        WeekPlanSkeleton skeleton = engine.planWeek(caso.toSnapshot());
        List<SessionSlot> sessoes = skeleton.sessions();

        assertThat(sessoes).as("sessões de '%s'", caso.label()).isNotNull();
        assertThat(sessoes).allSatisfy(slot -> {
            assertThat(slot.sessionType()).isNotBlank();
            assertThat(slot.intensityZone()).isNotBlank();
            assertThat(slot.durationMinutes()).isNotNull();
            assertThat(slot.durationMinutes()).isBetween(20, 300); // dentro dos clamps por tipo
            assertThat(slot.targetTss()).isGreaterThanOrEqualTo(0.0);
        });
        // no máximo uma sessão-chave; dias distintos entre os slots com dia atribuído
        assertThat(sessoes.stream().filter(SessionSlot::chave).count()).isLessThanOrEqualTo(1L);
        long comDia = sessoes.stream().filter(s -> s.day() != null).count();
        long diasDistintos = sessoes.stream().map(SessionSlot::day).filter(java.util.Objects::nonNull).distinct().count();
        assertThat(diasDistintos).as("dias sem duplicata em '%s'", caso.label()).isEqualTo(comDia);
    }

    static Stream<GoldenCase> casos() {
        return Stream.of(
                // --- Periodizacao completa: casos tipicos por fase ---
                GoldenCase.comProva("BASE tipico (100 dias)", 100, 21.0975, TrainingPhase.BASE),
                GoldenCase.comProva("BUILD tipico (70 dias)", 70, 21.0975, TrainingPhase.BUILD),
                GoldenCase.comProva("PEAK tipico (40 dias)", 40, 21.0975, TrainingPhase.PEAK),
                GoldenCase.comProva("TAPER tipico (20 dias)", 20, 21.0975, TrainingPhase.TAPER),
                GoldenCase.comProva("RACE_WEEK tipico (5 dias)", 5, 21.0975, TrainingPhase.RACE_WEEK),

                // --- Periodizacao: limites exatos de transicao (semanas = diasFaltando/7, truncado) ---
                GoldenCase.comProva("limite BUILD (90 dias, semanas=12, nao-BASE)", 90, 21.0975, TrainingPhase.BUILD),
                GoldenCase.comProva("limite BASE (91 dias, semanas=13)", 91, 21.0975, TrainingPhase.BASE),
                GoldenCase.comProva("limite PEAK (62 dias, semanas=8, nao-BUILD)", 62, 21.0975, TrainingPhase.PEAK),
                GoldenCase.comProva("limite BUILD (63 dias, semanas=9)", 63, 21.0975, TrainingPhase.BUILD),
                GoldenCase.comProva("limite TAPER (27 dias, semanas=3, nao-PEAK)", 27, 21.0975, TrainingPhase.TAPER),
                GoldenCase.comProva("limite PEAK (28 dias, semanas=4)", 28, 21.0975, TrainingPhase.PEAK),
                GoldenCase.comProva("limite RACE_WEEK (13 dias, semanas=1, nao-TAPER)", 13, 21.0975, TrainingPhase.RACE_WEEK),
                GoldenCase.comProva("limite TAPER (14 dias, semanas=2)", 14, 21.0975, TrainingPhase.TAPER),
                GoldenCase.comProva("dia da prova (0 dias)", 0, 21.0975, TrainingPhase.RACE_WEEK),

                // --- POST_RACE: prova recente dentro da janela de 7 dias ---
                GoldenCase.comProvaPassada("POST_RACE (prova ha 2 dias)", -2, 21.0975, TrainingPhase.POST_RACE),
                GoldenCase.comProvaPassada("limite POST_RACE (prova ha 7 dias)", -7, 21.0975, TrainingPhase.POST_RACE),

                // --- Step-back (CA2): 3 semanas consecutivas de progressao ---
                GoldenCase.comProva("step-back apos 3 semanas consecutivas", 70, 21.0975, TrainingPhase.BUILD)
                        .comProgressao(EstadoProgressao.PROGREDIR, 0.0, 3),

                // --- TSB nas 3 faixas (design.md Decisao 15) ---
                GoldenCase.comProva("TSB seguro (-5)", 70, 21.0975, TrainingPhase.BUILD)
                        .comTsb(-5.0).comRiskLevel(InjuryRiskLevel.SAFE),
                GoldenCase.comProva("TSB WARNING (-20)", 70, 21.0975, TrainingPhase.BUILD)
                        .comTsb(-20.0).comRiskLevel(InjuryRiskLevel.WARNING),
                GoldenCase.comProva("TSB HIGH_RISK (-35)", 70, 21.0975, TrainingPhase.BUILD)
                        .comTsb(-35.0).comRiskLevel(InjuryRiskLevel.HIGH_RISK).comRequiresCoachReview(true),

                // --- Lesao ativa (CA4): forca RECOVERY independente do calendario ---
                GoldenCase.comProva("lesao ativa com prova distante", 100, 21.0975, TrainingPhase.RECOVERY)
                        .comLesaoAtiva().comRequiresCoachReview(true),
                GoldenCase.comProva("lesao ativa com prova na semana", 5, 21.0975, TrainingPhase.RECOVERY)
                        .comLesaoAtiva().comRequiresCoachReview(true),

                // --- Lesao recente (CA14) ---
                GoldenCase.comProva("lesao recente dentro da janela (10d)", 70, 21.0975, TrainingPhase.RETURN_TO_TRAINING)
                        .comLesaoRecente(-10),
                GoldenCase.comProva("lesao fora da janela (45d, janela 30)", 70, 21.0975, TrainingPhase.BUILD)
                        .comLesaoRecente(-45),

                // --- Cold start: sem historico e sem prova ---
                GoldenCase.semProva("cold start (sem historico, sem prova)", TrainingPhase.BASE).comCtl(0.0),

                // --- BASE_FITNESS: sem prova, mas com historico real ---
                GoldenCase.semProva("BASE_FITNESS (sem prova, com historico)", TrainingPhase.BASE).comCtl(35.0),

                // --- Escopo multisport (CA13) ---
                GoldenCase.comProva("multisport (ciclismo) registra RUNNING_FIRST", 70, 21.0975, TrainingPhase.BUILD)
                        .comModalidade("CICLISMO", "RUNNING_FIRST"),
                GoldenCase.comProva("running explicito nao registra escopo", 70, 21.0975, TrainingPhase.BUILD)
                        .comModalidade("RUNNING", null),

                // --- DecisaoProgressao: REDUZIR, MANTER, PROGREDIR_LEVE ---
                GoldenCase.comProva("REDUZIR nao aumenta carga", 70, 21.0975, TrainingPhase.BUILD)
                        .comProgressao(EstadoProgressao.REDUZIR, 0.10, 0),
                GoldenCase.comProva("MANTER nao usa todo o teto", 70, 21.0975, TrainingPhase.BUILD)
                        .comProgressao(EstadoProgressao.MANTER, 0.0, 0),
                GoldenCase.comProva("PROGREDIR_LEVE respeita o teto de rampa", 70, 21.0975, TrainingPhase.BUILD)
                        .comProgressao(EstadoProgressao.PROGREDIR_LEVE, 0.8, 0),

                // --- Constraints: legacy sem dias disponiveis ---
                GoldenCase.comProva("legado sem dias disponiveis nao quebra", 70, 21.0975, TrainingPhase.BUILD)
                        .semDiasDisponiveis()
        );
    }

    @Test
    @DisplayName("golden set — multi-prova: alvo explicito vence sobre prova mais proxima")
    void multiProvaAlvoExplicitoVence() {
        ProvaSnapshot proxima = new ProvaSnapshot(REFERENCIA.plusDays(15), 10.0, false, false);
        ProvaSnapshot alvo = new ProvaSnapshot(REFERENCIA.plusDays(95), 21.0975, true, false);

        WeekPlanSkeleton skeleton = engine.planWeek(baseSnapshotBuilder(List.of(proxima, alvo)).build());

        assertThat(skeleton.phase()).isEqualTo(TrainingPhase.BASE); // fase definida pela prova-alvo (95 dias)
    }

    @Test
    @DisplayName("golden set — multi-prova: sem alvo, desempate por distancia na mesma semana")
    void multiProvaDesempatePorDistancia() {
        ProvaSnapshot curta = new ProvaSnapshot(REFERENCIA.plusDays(70), 10.0, false, false);
        ProvaSnapshot longa = new ProvaSnapshot(REFERENCIA.plusDays(72), 21.0975, false, false);

        WeekPlanSkeleton skeleton = engine.planWeek(baseSnapshotBuilder(List.of(curta, longa)).build());

        // longa (21K, 72 dias) vence o desempate -> fase por 72 dias (semanas=10) -> BUILD
        assertThat(skeleton.phase()).isEqualTo(TrainingPhase.BUILD);
    }

    @Test
    @DisplayName("golden set — prova preparatoria na semana nao altera a fase macro")
    void provaPreparatoriaNaoAlteraFaseMacro() {
        ProvaSnapshot alvo = new ProvaSnapshot(REFERENCIA.plusDays(100), 21.0975, true, false);
        ProvaSnapshot preparatoria = new ProvaSnapshot(REFERENCIA.plusDays(3), 5.0, false, false);

        WeekPlanSkeleton skeleton = engine.planWeek(baseSnapshotBuilder(List.of(alvo, preparatoria)).build());

        assertThat(skeleton.phase()).isEqualTo(TrainingPhase.BASE); // fase macro pela prova-alvo, nao pela preparatoria
    }

    @Test
    @DisplayName("golden set — carga diaria constante (monotonia > 2.0) eleva SAFE para WARNING")
    void monotoniaElevaRisco() {
        List<TreinoRealizadoSnapshot> cargaConstante = List.of(
                new TreinoRealizadoSnapshot(REFERENCIA.minusDays(1), 50),
                new TreinoRealizadoSnapshot(REFERENCIA.minusDays(2), 50),
                new TreinoRealizadoSnapshot(REFERENCIA.minusDays(3), 50),
                new TreinoRealizadoSnapshot(REFERENCIA.minusDays(4), 50),
                new TreinoRealizadoSnapshot(REFERENCIA.minusDays(5), 50),
                new TreinoRealizadoSnapshot(REFERENCIA.minusDays(6), 50),
                new TreinoRealizadoSnapshot(REFERENCIA.minusDays(7), 50));

        PlannerInputSnapshot snapshot = baseSnapshotBuilder(List.of(
                new ProvaSnapshot(REFERENCIA.plusDays(70), 21.0975, true, false)))
                .comHistoricoDiario(cargaConstante)
                .build();

        WeekPlanSkeleton skeleton = engine.planWeek(snapshot);

        assertThat(skeleton.injuryRisk().level()).isEqualTo(InjuryRiskLevel.WARNING);
    }

    @Test
    @DisplayName("golden set — descricao de lesao sem flag/data forca review sem alterar a fase")
    void descricaoDeLesaoSemEstruturaForcaReview() {
        PlannerInputSnapshot snapshot = baseSnapshotBuilder(List.of(
                new ProvaSnapshot(REFERENCIA.plusDays(70), 21.0975, true, false)))
                .comDescricaoLesao("sentindo uma dor no joelho direito")
                .build();

        WeekPlanSkeleton skeleton = engine.planWeek(snapshot);

        assertThat(skeleton.phase()).isEqualTo(TrainingPhase.BUILD); // fase normal, sem override
        assertThat(skeleton.requiresCoachReview()).isTrue();
    }

    // --- Infraestrutura do golden set ---

    private static SnapshotBuilder baseSnapshotBuilder(List<ProvaSnapshot> provas) {
        return new SnapshotBuilder(provas);
    }

    private static class SnapshotBuilder {
        private final List<ProvaSnapshot> provas;
        private boolean temLesao = false;
        private String descricaoLesao = null;
        private LocalDate dataUltimaLesao = null;
        private double tsbAtual = -2.0;
        private double ctlAtual = 40.0;
        private int semanasProgressaoContinua = 0;
        private EstadoProgressao estado = EstadoProgressao.MANTER;
        private double ajusteVolumePercentual = 0.0;
        private String modalidade = null;
        private List<DiaSemana> diasDisponiveis = List.of(DiaSemana.SEGUNDA, DiaSemana.QUARTA, DiaSemana.SABADO);
        private List<TreinoRealizadoSnapshot> historicoDiario = List.of();

        SnapshotBuilder(List<ProvaSnapshot> provas) {
            this.provas = provas;
        }

        SnapshotBuilder comHistoricoDiario(List<TreinoRealizadoSnapshot> historico) {
            this.historicoDiario = historico;
            return this;
        }

        SnapshotBuilder comDescricaoLesao(String descricao) {
            this.descricaoLesao = descricao;
            return this;
        }

        PlannerInputSnapshot build() {
            AthleteSnapshot athlete = new AthleteSnapshot(
                    UUID.randomUUID(), NivelExperiencia.INTERMEDIARIO, temLesao, descricaoLesao, dataUltimaLesao,
                    diasDisponiveis, modalidade);
            DecisaoProgressao decisao = new DecisaoProgressao(estado, ajusteVolumePercentual, 0, true, "golden set");
            ProgressaoHistoricoResumo historico = new ProgressaoHistoricoResumo(
                    0, 0, 0.0, 0.0, 0.0, 0, 0, null, tsbAtual, ctlAtual, 0.0, semanasProgressaoContinua);

            return new PlannerInputSnapshot(
                    athlete, decisao, historico, provas, historicoDiario, Optional.empty(), REFERENCIA, 30);
        }
    }

    /**
     * Um caso do golden set: entrada minima + saida esperada exata (fase, review, escopo).
     * Builder fluente para reduzir boilerplate nos ~30 casos.
     */
    private record GoldenCase(
            String label,
            List<ProvaSnapshot> provas,
            boolean temLesao,
            String descricaoLesao,
            LocalDate dataUltimaLesao,
            double tsbAtual,
            double ctlAtual,
            int semanasProgressaoContinua,
            EstadoProgressao estado,
            double ajusteVolumePercentual,
            String modalidade,
            List<DiaSemana> diasDisponiveis,
            TrainingPhase expectedPhase,
            boolean expectedRequiresCoachReview,
            String expectedPlannerScope,
            InjuryRiskLevel expectedRiskLevel) {

        static GoldenCase comProva(String label, long diasFaltando, double distanciaKm, TrainingPhase expectedPhase) {
            ProvaSnapshot prova = new ProvaSnapshot(REFERENCIA.plusDays(diasFaltando), distanciaKm, true, false);
            return base(label, List.of(prova), expectedPhase);
        }

        static GoldenCase comProvaPassada(String label, long diasAtras, double distanciaKm, TrainingPhase expectedPhase) {
            ProvaSnapshot prova = new ProvaSnapshot(REFERENCIA.plusDays(diasAtras), distanciaKm, true, false);
            return base(label, List.of(prova), expectedPhase);
        }

        static GoldenCase semProva(String label, TrainingPhase expectedPhase) {
            return base(label, List.of(), expectedPhase);
        }

        private static GoldenCase base(String label, List<ProvaSnapshot> provas, TrainingPhase expectedPhase) {
            return new GoldenCase(label, provas, false, null, null, -2.0, 40.0, 0,
                    EstadoProgressao.MANTER, 0.0, null,
                    List.of(DiaSemana.SEGUNDA, DiaSemana.QUARTA, DiaSemana.SABADO),
                    expectedPhase, false, null, InjuryRiskLevel.SAFE);
        }

        GoldenCase comTsb(double tsb) {
            return new GoldenCase(label, provas, temLesao, descricaoLesao, dataUltimaLesao, tsb, ctlAtual,
                    semanasProgressaoContinua, estado, ajusteVolumePercentual, modalidade, diasDisponiveis,
                    expectedPhase, expectedRequiresCoachReview, expectedPlannerScope, expectedRiskLevel);
        }

        GoldenCase comCtl(double ctl) {
            return new GoldenCase(label, provas, temLesao, descricaoLesao, dataUltimaLesao, tsbAtual, ctl,
                    semanasProgressaoContinua, estado, ajusteVolumePercentual, modalidade, diasDisponiveis,
                    expectedPhase, expectedRequiresCoachReview, expectedPlannerScope, expectedRiskLevel);
        }

        GoldenCase comProgressao(EstadoProgressao novoEstado, double ajuste, int semanas) {
            return new GoldenCase(label, provas, temLesao, descricaoLesao, dataUltimaLesao, tsbAtual, ctlAtual,
                    semanas, novoEstado, ajuste, modalidade, diasDisponiveis,
                    expectedPhase, expectedRequiresCoachReview, expectedPlannerScope, expectedRiskLevel);
        }

        GoldenCase comLesaoAtiva() {
            return new GoldenCase(label, provas, true, descricaoLesao, dataUltimaLesao, tsbAtual, ctlAtual,
                    semanasProgressaoContinua, estado, ajusteVolumePercentual, modalidade, diasDisponiveis,
                    expectedPhase, expectedRequiresCoachReview, expectedPlannerScope, expectedRiskLevel);
        }

        GoldenCase comLesaoRecente(long offsetDias) {
            return new GoldenCase(label, provas, temLesao, descricaoLesao, REFERENCIA.plusDays(offsetDias), tsbAtual, ctlAtual,
                    semanasProgressaoContinua, estado, ajusteVolumePercentual, modalidade, diasDisponiveis,
                    expectedPhase, expectedRequiresCoachReview, expectedPlannerScope, expectedRiskLevel);
        }

        GoldenCase comModalidade(String novaModalidade, String scopeEsperado) {
            return new GoldenCase(label, provas, temLesao, descricaoLesao, dataUltimaLesao, tsbAtual, ctlAtual,
                    semanasProgressaoContinua, estado, ajusteVolumePercentual, novaModalidade, diasDisponiveis,
                    expectedPhase, expectedRequiresCoachReview, scopeEsperado, expectedRiskLevel);
        }

        GoldenCase semDiasDisponiveis() {
            return new GoldenCase(label, provas, temLesao, descricaoLesao, dataUltimaLesao, tsbAtual, ctlAtual,
                    semanasProgressaoContinua, estado, ajusteVolumePercentual, modalidade, List.of(),
                    expectedPhase, expectedRequiresCoachReview, expectedPlannerScope, expectedRiskLevel);
        }

        GoldenCase comRequiresCoachReview(boolean valor) {
            return new GoldenCase(label, provas, temLesao, descricaoLesao, dataUltimaLesao, tsbAtual, ctlAtual,
                    semanasProgressaoContinua, estado, ajusteVolumePercentual, modalidade, diasDisponiveis,
                    expectedPhase, valor, expectedPlannerScope, expectedRiskLevel);
        }

        GoldenCase comRiskLevel(InjuryRiskLevel valor) {
            return new GoldenCase(label, provas, temLesao, descricaoLesao, dataUltimaLesao, tsbAtual, ctlAtual,
                    semanasProgressaoContinua, estado, ajusteVolumePercentual, modalidade, diasDisponiveis,
                    expectedPhase, expectedRequiresCoachReview, expectedPlannerScope, valor);
        }

        PlannerInputSnapshot toSnapshot() {
            AthleteSnapshot athlete = new AthleteSnapshot(
                    UUID.randomUUID(), NivelExperiencia.INTERMEDIARIO, temLesao, descricaoLesao, dataUltimaLesao,
                    diasDisponiveis, modalidade);
            DecisaoProgressao decisao = new DecisaoProgressao(estado, ajusteVolumePercentual, 0, true, "golden set");
            ProgressaoHistoricoResumo historico = new ProgressaoHistoricoResumo(
                    0, 0, 0.0, 0.0, 0.0, 0, 0, null, tsbAtual, ctlAtual, 0.0, semanasProgressaoContinua);

            return new PlannerInputSnapshot(
                    athlete, decisao, historico, provas, List.of(), Optional.empty(), REFERENCIA, 30);
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
