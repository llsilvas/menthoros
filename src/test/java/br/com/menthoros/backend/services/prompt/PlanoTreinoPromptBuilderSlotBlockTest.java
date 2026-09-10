package br.com.menthoros.backend.services.prompt;

import br.com.menthoros.backend.domain.planner.SessionSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bloco de slots prescritivos no prompt (planner-engine-enforcement §3): presente com skeleton,
 * ausente sem ele (CA9 — flag off = prompt legado).
 */
@DisplayName("PlanoTreinoPromptBuilder — bloco de slots (skeleton)")
class PlanoTreinoPromptBuilderSlotBlockTest {

    @Test
    @DisplayName("com sessões: renderiza o bloco mandatório com dia/tipo/TSS/zona e marca a chave")
    void comSessoes() {
        List<SessionSlot> sessoes = List.of(
                new SessionSlot(DayOfWeek.SATURDAY, "LONGO", 90.0, "Zona 2", true, 90),
                new SessionSlot(DayOfWeek.TUESDAY, "INTERVALADO", 70.0, "Zona 5 (VO2max)", false, 60));

        String bloco = PlanoTreinoPromptBuilder.formatarBlocoSlots(sessoes);

        assertThat(bloco)
                .contains("ESTRUTURA OBRIGATÓRIA DA SEMANA")
                .contains("Sábado: LONGO")
                .contains("Terça: INTERVALADO")
                .contains("Zona 2")
                .contains("[SESSÃO-CHAVE]")
                .contains("não altere dia/tipo/TSS/zona");
    }

    @Test
    @DisplayName("sem skeleton (null) ou vazio: bloco vazio — prompt idêntico ao legado (CA9)")
    void semSkeleton() {
        assertThat(PlanoTreinoPromptBuilder.formatarBlocoSlots(null)).isEmpty();
        assertThat(PlanoTreinoPromptBuilder.formatarBlocoSlots(List.of())).isEmpty();
    }
}
