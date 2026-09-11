package br.com.menthoros.backend.dto.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import br.com.menthoros.backend.enums.MotivoReaberturaRevisao;
import br.com.menthoros.backend.enums.OrigemEncerramento;
import br.com.menthoros.backend.enums.PlanoReviewStatus;
import br.com.menthoros.backend.enums.PlanoStatus;
import br.com.menthoros.backend.services.onboarding.ConfidenceTier;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.LocalDate;
import java.util.List;

@Schema(description = "Dados de saída de um plano semanal de treino")
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanoSemanalOutputDto(

        String id,

        @Schema(description = "Data de início da semana", example = "2024-01-15")
        LocalDate semanaInicio,

        @Schema(description = "Data de fim da semana", example = "2024-01-21")
        LocalDate semanaFim,

        @Schema(description = "Volume planejado em quilômetros", example = "50.0")
        double volumePlanejadoKm,

        @Schema(description = "Volume realizado em quilômetros", example = "48.5")
        double volumeRealizadoKm,

        @Schema(description = "Volume alvo em quilômetros", example = "52.0")
        double volumeAlvoKm,

        @Schema(description = "Training Stress Balance (TSB) no início da semana", example = "10.5")
        Double tsbInicio,

        @Schema(description = "Training Stress Balance (TSB) no fim da semana", example = "8.2")
        Double tsbFim,

        @Schema(description = "Status do plano semanal", example = "EM_PROGRESSO")
        PlanoStatus status,

        @Schema(description = "Observações sobre a semana", example = "Semana de regeneração")
        String observacoes,

        @Schema(description = "Objetivo da semana", example = "Aumentar volume de base aeróbica")
        String objetivoSemanal,

        @Schema(description = "Lista de treinos planejados da semana")
        List<TreinoPlanejadoOutputDto> treinosPlanejados,

        @Schema(description = "Status de revisão do plano pelo coach", example = "AGUARDANDO_REVISAO")
        PlanoReviewStatus reviewStatus,

        @Schema(description = "Origem do encerramento da semana (ON_DEMAND pelo treinador, AUTOMATICO pelo scheduler); null se ainda não encerrada", example = "ON_DEMAND")
        OrigemEncerramento origemEncerramento,

        @Schema(description = "Motivo informado pelo coach ao rejeitar o plano")
        String reviewComment,

        @Schema(description = "Nome completo do atleta dono do plano")
        String atletaNome,

        @Schema(description = "Nível de confiança do baseline de onboarding do atleta (A/B/C) — " +
                "B/C indicam que o plano não foi auto-aprovado por baixa confiança (Cenário B/C, " +
                "athlete-onboarding-baseline); null se o atleta ainda não passou pelo onboarding",
                example = "B")
        ConfidenceTier confidenceTier,

        @Schema(description = "Motivo pelo qual um plano aprovado voltou a AGUARDANDO_REVISAO por causa de uma prova; " +
                "null fora dessa reabertura (prova-no-plano-semanal)", example = "PROVA_INSERIDA")
        MotivoReaberturaRevisao motivoReabertura,

        @Schema(description = "Status de compliance do plano frente ao skeleton do planner " +
                "(planner-engine-enforcement); null em plano legado sem avaliacao", example = "FAILED")
        br.com.menthoros.backend.domain.compliance.PlannerComplianceStatus plannerComplianceStatus,

        @Schema(description = "Indica que o plano exige revisao do coach por divergencia do planner " +
                "(FAILED ou requiresCoachReview); null em plano legado", example = "true")
        Boolean plannerRequiresCoachReview,

        @Schema(description = "Resumo legivel dos motivos de divergencia do planner (uma frase por " +
                "violacao), para o badge de revisao; null/vazio quando nao ha violacao ou em plano legado")
        List<String> plannerReviewReasons
) {}
