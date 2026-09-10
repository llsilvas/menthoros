package br.com.menthoros.backend.domain.planner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.List;

import static java.time.DayOfWeek.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SessionDayAllocator")
class SessionDayAllocatorTest {

    private final SessionDayAllocator allocator = new SessionDayAllocator();
    private static final List<DayOfWeek> SEMANA_TODA = List.of(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY);

    private static SessionSlot slot(String tipo, boolean chave) {
        return new SessionSlot(null, tipo, 50.0, "zona", chave, 45);
    }

    private static DayOfWeek diaDe(List<SessionSlot> s, String tipo) {
        return s.stream().filter(x -> x.sessionType().equals(tipo)).map(SessionSlot::day).findFirst().orElseThrow();
    }

    private static int distanciaCircular(DayOfWeek a, DayOfWeek b) {
        int diff = Math.abs(a.getValue() - b.getValue());
        return Math.min(diff, 7 - diff);
    }

    @Nested
    @DisplayName("sessão-chave LONGO")
    class Longo {

        @Test
        @DisplayName("ancora no dia preferido quando disponível")
        void diaPreferido() {
            var s = allocator.allocate(List.of(slot("LONGO", true), slot("FACIL", false)), SEMANA_TODA, SATURDAY, null);
            assertThat(diaDe(s, "LONGO")).isEqualTo(SATURDAY);
        }

        @Test
        @DisplayName("sem dia preferido, vai para o último dia disponível")
        void ultimoDia() {
            var s = allocator.allocate(List.of(slot("LONGO", true), slot("FACIL", false)),
                    List.of(MONDAY, WEDNESDAY, FRIDAY), null, null);
            assertThat(diaDe(s, "LONGO")).isEqualTo(FRIDAY);
        }
    }

    @Nested
    @DisplayName("sessões duras não-adjacentes")
    class Duras {

        @Test
        @DisplayName("duas duras ficam com ≥2 dias de distância (semana cheia)")
        void naoAdjacentes() {
            var s = allocator.allocate(
                    List.of(slot("LONGO", true), slot("INTERVALADO", false), slot("TEMPO_RUN", false)),
                    SEMANA_TODA, SATURDAY, null);
            assertThat(distanciaCircular(diaDe(s, "INTERVALADO"), diaDe(s, "TEMPO_RUN"))).isGreaterThanOrEqualTo(2);
        }
    }

    @Nested
    @DisplayName("PROVA")
    class Prova {

        @Test
        @DisplayName("fica no dia real da prova, ignorando disponibilidade")
        void diaRealDaProva() {
            var s = allocator.allocate(
                    List.of(slot("PROVA", true), slot("REGENERATIVO", false), slot("FACIL", false)),
                    List.of(MONDAY, TUESDAY, WEDNESDAY), null, SUNDAY);
            assertThat(diaDe(s, "PROVA")).isEqualTo(SUNDAY);
            assertThat(s).allSatisfy(x -> assertThat(x.day()).isNotNull());
        }
    }

    @Test
    @DisplayName("todos os slots recebem um dia (slots ≤ dias)")
    void todosComDia() {
        var s = allocator.allocate(List.of(slot("LONGO", true), slot("FACIL", false), slot("REGENERATIVO", false)),
                SEMANA_TODA, SUNDAY, null);
        assertThat(s).allSatisfy(x -> assertThat(x.day()).isNotNull());
        assertThat(s.stream().map(SessionSlot::day).distinct().count()).isEqualTo(3); // dias distintos
    }
}
