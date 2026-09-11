package br.com.menthoros.backend.mapper;

import br.com.menthoros.backend.domain.compliance.PlannerAuditMetadata;
import br.com.menthoros.backend.domain.compliance.PlannerComplianceStatus;
import br.com.menthoros.backend.domain.compliance.PlannerViolation;
import br.com.menthoros.backend.domain.compliance.PlannerViolationKey;
import br.com.menthoros.backend.domain.planner.TrainingPhase;
import br.com.menthoros.backend.dto.output.PlanoSemanalOutputDto;
import br.com.menthoros.backend.entity.PlanoSemanal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * planner-engine-enforcement §7.1: exposicao dos campos de compliance do planner no DTO da visao do
 * coach — status/requiresCoachReview pelas colunas, motivos legiveis parseados do
 * planner_metadata_json. Cobre o plano legado (sem metadata) contra NPE.
 */
@DisplayName("PlanoSemanalMapper — campos de compliance do planner (§7.1)")
class PlanoSemanalMapperPlannerTest {

    private final PlanoSemanalMapper mapper = new PlanoSemanalMapperImpl(new TreinoMapperImpl(null, null));
    private final ObjectMapper objectMapper = new ObjectMapper();

    private PlanoSemanal planoBase() {
        PlanoSemanal plano = new PlanoSemanal();
        plano.setId(UUID.randomUUID());
        plano.setTreinosPlanejados(List.of());
        return plano;
    }

    private String metadataJson(PlannerComplianceStatus status, List<PlannerViolation> violacoes) throws Exception {
        return objectMapper.writeValueAsString(new PlannerAuditMetadata(
                TrainingPhase.BASE, !violacoes.isEmpty(), "motivo geral",
                status, violacoes.size(), violacoes, "v1"));
    }

    @Nested
    @DisplayName("plano com metadata e violacoes")
    class ComViolacoes {

        @Test
        @DisplayName("expoe status FAILED, requiresCoachReview e os motivos legiveis (nao so a contagem)")
        void expoeMotivos() throws Exception {
            List<PlannerViolation> violacoes = List.of(
                    new PlannerViolation(PlannerViolationKey.DIA_INDISPONIVEL, "treino em dia nao disponivel"),
                    new PlannerViolation(PlannerViolationKey.TAPER_VIOLADO, "carga alta na semana de taper"));
            PlanoSemanal plano = planoBase();
            plano.setPlannerComplianceStatus(PlannerComplianceStatus.FAILED.name());
            plano.setPlannerRequiresCoachReview(true);
            plano.setPlannerMetadataJson(metadataJson(PlannerComplianceStatus.FAILED, violacoes));

            PlanoSemanalOutputDto dto = mapper.toOutputDto(plano);

            assertThat(dto.plannerComplianceStatus()).isEqualTo(PlannerComplianceStatus.FAILED);
            assertThat(dto.plannerRequiresCoachReview()).isTrue();
            assertThat(dto.plannerReviewMotivos())
                    .containsExactly("treino em dia nao disponivel", "carga alta na semana de taper");
        }
    }

    @Nested
    @DisplayName("plano compliant (sem violacoes)")
    class SemViolacoes {

        @Test
        @DisplayName("status PASSED, sem motivos (lista de violacoes vazia -> null)")
        void semMotivos() throws Exception {
            PlanoSemanal plano = planoBase();
            plano.setPlannerComplianceStatus(PlannerComplianceStatus.PASSED.name());
            plano.setPlannerRequiresCoachReview(false);
            plano.setPlannerMetadataJson(metadataJson(PlannerComplianceStatus.PASSED, List.of()));

            PlanoSemanalOutputDto dto = mapper.toOutputDto(plano);

            assertThat(dto.plannerComplianceStatus()).isEqualTo(PlannerComplianceStatus.PASSED);
            assertThat(dto.plannerRequiresCoachReview()).isFalse();
            assertThat(dto.plannerReviewMotivos()).isNull();
        }
    }

    @Nested
    @DisplayName("plano legado sem metadata")
    class Legado {

        @Test
        @DisplayName("todos os campos de planner nulos, sem NPE")
        void camposNulos() {
            PlanoSemanal plano = planoBase(); // sem plannerComplianceStatus/metadataJson

            PlanoSemanalOutputDto dto = mapper.toOutputDto(plano);

            assertThat(dto.plannerComplianceStatus()).isNull();
            assertThat(dto.plannerRequiresCoachReview()).isNull();
            assertThat(dto.plannerReviewMotivos()).isNull();
        }

        @Test
        @DisplayName("planner_metadata_json ilegivel -> motivos null (sem excecao)")
        void jsonIlegivel() {
            PlanoSemanal plano = planoBase();
            plano.setPlannerComplianceStatus("VALOR_DESCONHECIDO"); // enum inexistente
            plano.setPlannerMetadataJson("{ nao e json valido");

            PlanoSemanalOutputDto dto = mapper.toOutputDto(plano);

            assertThat(dto.plannerComplianceStatus()).isNull(); // valor desconhecido -> null
            assertThat(dto.plannerReviewMotivos()).isNull();
        }
    }
}
