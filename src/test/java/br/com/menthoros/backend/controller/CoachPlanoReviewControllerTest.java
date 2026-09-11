package br.com.menthoros.backend.controller;

import br.com.menthoros.backend.dto.input.PlanoRejectionInputDto;
import br.com.menthoros.backend.dto.output.PlanoSemanalOutputDto;
import br.com.menthoros.backend.enums.PlanoReviewStatus;
import br.com.menthoros.backend.enums.PlanoStatus;
import br.com.menthoros.backend.exception.DomainNotFoundException;
import br.com.menthoros.backend.exception.DomainRuleViolationException;
import br.com.menthoros.backend.multitenancy.TenantContext;
import br.com.menthoros.backend.services.PlanoReviewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoachPlanoReviewControllerTest {

    @Mock private PlanoReviewService planoReviewService;
    @InjectMocks private CoachPlanoReviewController controller;

    private UUID tenantId;
    private UUID planoId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        planoId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // listarPendentes
    // =========================================================================

    @Nested
    @DisplayName("listarPendentes")
    class ListarPendentes {

        @Test
        @DisplayName("GET /pendentes → 200 com lista de planos pendentes")
        void retornaListaDePendentes() {
            PlanoSemanalOutputDto dto = outputDto(PlanoReviewStatus.AGUARDANDO_REVISAO);
            when(planoReviewService.listarPlanosPendentes(tenantId)).thenReturn(List.of(dto));

            ResponseEntity<List<PlanoSemanalOutputDto>> resp = controller.listarPendentes();

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).containsExactly(dto);
        }

        @Test
        @DisplayName("GET /pendentes → 200 com lista vazia quando não há pendentes")
        void retornaListaVaziaQuandoSemPendentes() {
            when(planoReviewService.listarPlanosPendentes(tenantId)).thenReturn(List.of());

            ResponseEntity<List<PlanoSemanalOutputDto>> resp = controller.listarPendentes();

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).isEmpty();
        }
    }

    // =========================================================================
    // aprovar
    // =========================================================================

    @Nested
    @DisplayName("aprovar")
    class Aprovar {

        @Test
        @DisplayName("POST /{id}/aprovar → 200 com plano APROVADO")
        void aprovaPlanoPendente() {
            PlanoSemanalOutputDto dto = outputDto(PlanoReviewStatus.APROVADO);
            when(planoReviewService.aprovarPlano(planoId, tenantId)).thenReturn(dto);

            ResponseEntity<PlanoSemanalOutputDto> resp = controller.aprovar(planoId);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).isEqualTo(dto);
            assertThat(resp.getBody().reviewStatus()).isEqualTo(PlanoReviewStatus.APROVADO);
        }

        @Test
        @DisplayName("POST /{id}/aprovar → propaga DomainNotFoundException quando plano não existe")
        void propagaNotFoundQuandoPlanoAusente() {
            when(planoReviewService.aprovarPlano(planoId, tenantId))
                    .thenThrow(new DomainNotFoundException("Plano não encontrado"));

            assertThatThrownBy(() -> controller.aprovar(planoId))
                    .isInstanceOf(DomainNotFoundException.class);
        }

        @Test
        @DisplayName("POST /{id}/aprovar → propaga DomainRuleViolationException em transição ilegal")
        void propagaRuleViolationEmTransicaoIlegal() {
            when(planoReviewService.aprovarPlano(planoId, tenantId))
                    .thenThrow(new DomainRuleViolationException("Transição inválida"));

            assertThatThrownBy(() -> controller.aprovar(planoId))
                    .isInstanceOf(DomainRuleViolationException.class);
        }
    }

    // =========================================================================
    // rejeitar
    // =========================================================================

    @Nested
    @DisplayName("rejeitar")
    class Rejeitar {

        @Test
        @DisplayName("POST /{id}/rejeitar → 200 com plano REJEITADO")
        void rejeitaPlanoPendente() {
            String motivo = "Volume excessivo";
            PlanoRejectionInputDto input = new PlanoRejectionInputDto(motivo);
            PlanoSemanalOutputDto dto = outputDto(PlanoReviewStatus.REJEITADO);
            when(planoReviewService.rejeitarPlano(planoId, tenantId, motivo)).thenReturn(dto);

            ResponseEntity<PlanoSemanalOutputDto> resp = controller.rejeitar(planoId, input);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).isEqualTo(dto);
            assertThat(resp.getBody().reviewStatus()).isEqualTo(PlanoReviewStatus.REJEITADO);
        }

        @Test
        @DisplayName("POST /{id}/rejeitar → propaga DomainNotFoundException quando plano não existe")
        void propagaNotFoundQuandoPlanoAusente() {
            PlanoRejectionInputDto input = new PlanoRejectionInputDto("motivo");
            when(planoReviewService.rejeitarPlano(planoId, tenantId, "motivo"))
                    .thenThrow(new DomainNotFoundException("Plano não encontrado"));

            assertThatThrownBy(() -> controller.rejeitar(planoId, input))
                    .isInstanceOf(DomainNotFoundException.class);
        }

        @Test
        @DisplayName("POST /{id}/rejeitar → propaga DomainRuleViolationException em transição ilegal")
        void propagaRuleViolationEmTransicaoIlegal() {
            PlanoRejectionInputDto input = new PlanoRejectionInputDto("motivo");
            when(planoReviewService.rejeitarPlano(planoId, tenantId, "motivo"))
                    .thenThrow(new DomainRuleViolationException("Transição inválida"));

            assertThatThrownBy(() -> controller.rejeitar(planoId, input))
                    .isInstanceOf(DomainRuleViolationException.class);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private PlanoSemanalOutputDto outputDto(PlanoReviewStatus reviewStatus) {
        return new PlanoSemanalOutputDto(
                planoId.toString(), LocalDate.now(), LocalDate.now().plusDays(6),
                40.0, 0.0, 40.0, null, null, PlanoStatus.PLANEJADO,
                null, "Semana base", List.of(), reviewStatus, null, null, "Ana Silva", null, null, null, null, null);
    }
}
