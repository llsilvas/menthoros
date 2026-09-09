package br.com.menthoros.backend.services.helper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GenerationBudget")
class GenerationBudgetTest {

    @Nested
    @DisplayName("tentarDebitar")
    class TentarDebitar {

        @Test
        @DisplayName("debita até o teto e então recusa")
        void debitaAteOTeto() {
            var b = new GenerationBudget(2, Duration.ofSeconds(30));
            assertThat(b.tentarDebitar()).isTrue();
            assertThat(b.tentarDebitar()).isTrue();
            assertThat(b.tentarDebitar()).isFalse(); // esgotado por contagem
            assertThat(b.gastas()).isEqualTo(2);
            assertThat(b.deadlineEstourado()).isFalse();
        }

        @Test
        @DisplayName("recusa quando o deadline estourou, sem debitar")
        void recusaNoDeadline() throws InterruptedException {
            var b = new GenerationBudget(5, Duration.ofMillis(20));
            Thread.sleep(40);
            assertThat(b.deadlineEstourado()).isTrue();
            assertThat(b.tentarDebitar()).isFalse();
            assertThat(b.gastas()).isZero();
        }
    }

    @Test
    @DisplayName("rejeita argumentos inválidos")
    void argumentosInvalidos() {
        assertThatThrownBy(() -> new GenerationBudget(0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GenerationBudget(2, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
