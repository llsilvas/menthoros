package br.com.menthoros.backend.domain.planner;

import br.com.menthoros.backend.domain.planner.SessionCompositionResolver.CompositionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Set;

/**
 * Harness de CALIBRAÇÃO (não roda no CI — opt-in com {@code -Dcalibration=true}).
 * Varre {@code targetTss × diasDisponiveis × fase} sobre o {@link SessionCompositionResolver}
 * (puro/determinístico, sem LLM) e imprime uma tabela com o que o skeleton compõe e onde ele
 * bate contra o compliance. Serve para ajustar os números do ADR-0011 (TAXA_BASE, clamps,
 * tabela por fase) e a fração de teto do checker.
 *
 * <p>Rodar: {@code ./mvnw test -Dtest=SessionCompositionCalibrationTest -Dcalibration=true}
 *
 * <p>Colunas: n=sessionCount (vs dias), TIPOS[TSS], somaTSS, capInt=teto de intensidade
 * ({@code targetTss × CAP_FRACAO}, espelha {@code SkeletonComplianceChecker.TETO_INTERVALADO_FRACAO}).
 * Flags: {@code <DIAS} = compôs menos treinos que dias disponíveis; {@code INT>CAP} = o próprio slot
 * intenso do planner já excede o teto do checker (skeleton auto-inconsistente → 422 garantido).
 */
@EnabledIfSystemProperty(named = "calibration", matches = "true")
@DisplayName("SessionCompositionResolver — harness de calibração")
class SessionCompositionCalibrationTest {

    private final SessionCompositionResolver resolver = new SessionCompositionResolver();

    /** Espelha SkeletonComplianceChecker.TETO_INTERVALADO_FRACAO — ajuste os dois juntos. */
    private static final double CAP_FRACAO = 0.25;
    /** TSS mínimo realista de um intervalado estruturado (aquec + tiros + desaq), para referência. */
    private static final double INTERVALADO_REALISTA_TSS = 60.0;

    private static final Set<String> INTENSOS =
            Set.of("INTERVALADO", "TIRO", "TEMPO_RUN", "FARTLEK", "SUBIDA");

    private static final double[] TARGETS = {80, 120, 156, 200, 260, 350, 450, 600};
    private static final int[] DIAS = {3, 4, 5, 6};
    private static final TrainingPhase[] FASES = {
            TrainingPhase.BASE, TrainingPhase.BUILD, TrainingPhase.PEAK,
            TrainingPhase.TAPER, TrainingPhase.RECOVERY, TrainingPhase.RETURN_TO_TRAINING,
    };

    @Test
    void report() {
        StringBuilder sb = new StringBuilder("\n");
        sb.append("=".repeat(120)).append("\n");
        sb.append(String.format("CALIBRAÇÃO — CAP_FRACAO=%.2f | INTERVALADO_REALISTA=%.0f TSS%n", CAP_FRACAO, INTERVALADO_REALISTA_TSS));
        sb.append("=".repeat(120)).append("\n");

        for (TrainingPhase fase : FASES) {
            sb.append("\n### ").append(fase).append("\n");
            sb.append(String.format("%-6s %-4s %-4s %-52s %-8s %-8s %s%n",
                    "target", "dias", "n", "tipos[TSS]", "soma", "capInt", "flags"));
            sb.append("-".repeat(120)).append("\n");

            for (double target : TARGETS) {
                for (int dias : DIAS) {
                    List<SessionSlot> slots = resolver.compose(
                            new CompositionRequest(fase, target, dias, null, null, 3, null));

                    double soma = slots.stream().mapToDouble(s -> s.targetTss()).sum();
                    double cap = target * CAP_FRACAO;

                    String tipos = slots.stream()
                            .map(s -> String.format("%s%s%.0f",
                                    s.sessionType().length() > 4 ? s.sessionType().substring(0, 4) : s.sessionType(),
                                    s.chave() ? "*" : ":",
                                    s.targetTss()))
                            .reduce((a, b) -> a + " " + b).orElse("(vazio)");

                    boolean underDias = slots.size() < dias && fase != TrainingPhase.RECOVERY;
                    double maiorIntenso = slots.stream()
                            .filter(s -> INTENSOS.contains(s.sessionType()))
                            .mapToDouble(s -> s.targetTss())
                            .max().orElse(0);
                    boolean intOverCap = maiorIntenso > cap + 1e-6;
                    boolean capAbaixoRealista = cap < INTERVALADO_REALISTA_TSS
                            && slots.stream().anyMatch(s -> INTENSOS.contains(s.sessionType()));

                    StringBuilder flags = new StringBuilder();
                    if (underDias) flags.append("<DIAS ");
                    if (intOverCap) flags.append("INT>CAP ");
                    if (capAbaixoRealista) flags.append("CAP<REAL ");

                    sb.append(String.format("%-6.0f %-4d %-4d %-52s %-8.0f %-8.0f %s%n",
                            target, dias, slots.size(), tipos, soma, cap, flags.toString().trim()));
                }
            }
        }
        sb.append("\n").append("=".repeat(120)).append("\n");
        sb.append("LEGENDA: n<dias => planner compôs menos treinos que dias disponíveis (minimums-win).\n");
        sb.append("         INT>CAP => slot intenso do PRÓPRIO planner excede o teto do checker (auto-inconsistente → 422).\n");
        sb.append("         CAP<REAL => teto de intensidade abaixo do custo mínimo de um intervalado real (~60 TSS).\n");
        try {
            java.nio.file.Path out = java.nio.file.Path.of("target", "calibration-report.txt");
            java.nio.file.Files.writeString(out, sb.toString());
            System.out.println("[calibração] relatório em " + out.toAbsolutePath());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        System.out.println(sb);
    }
}
