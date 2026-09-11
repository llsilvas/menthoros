package br.com.menthoros.backend.mapper;

import br.com.menthoros.backend.dto.input.PlanoSemanalInputDto;
import br.com.menthoros.backend.dto.llm.PlanoSemanalLlmDto;
import br.com.menthoros.backend.dto.llm.TreinoPlanejadoLlmDto;
import br.com.menthoros.backend.dto.output.PlanoSemanalOutputDto;
import br.com.menthoros.backend.domain.compliance.PlannerAuditMetadata;
import br.com.menthoros.backend.domain.compliance.PlannerComplianceStatus;
import br.com.menthoros.backend.domain.compliance.PlannerViolation;
import br.com.menthoros.backend.entity.PlanoSemanal;
import br.com.menthoros.backend.entity.TreinoPlanejado;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mapstruct.*;

import java.util.List;

@Mapper(
        componentModel = "spring",
        unmappedSourcePolicy = ReportingPolicy.IGNORE,
        uses = {TreinoMapper.class}
)
public interface PlanoSemanalMapper {

    @Mapping(source = "atletaId", target = "atleta.id")
    @Mapping(source = "planoTreinoId", target = "planoTreino.id")
    @Mapping(source = "treinosPlanejados", target = "treinosPlanejados")
    PlanoSemanal toEntity(PlanoSemanalInputDto dto);

    // planner-engine-enforcement §7.1: leitura apenas — status/review pelas colunas (verdict do
    // enforcement) e os motivos parseados do planner_metadata_json (detalhe estruturado).
    ObjectMapper PLANNER_METADATA_MAPPER = new ObjectMapper();
    org.slf4j.Logger PLANNER_LOG = org.slf4j.LoggerFactory.getLogger(PlanoSemanalMapper.class);

    @Mapping(target = "atletaNome", expression = "java(resolveAtletaNome(entity))")
    @Mapping(target = "plannerComplianceStatus", expression = "java(resolvePlannerComplianceStatus(entity))")
    @Mapping(target = "plannerReviewReasons", expression = "java(resolvePlannerReviewReasons(entity))")
    PlanoSemanalOutputDto toOutputDto(PlanoSemanal entity);

    /** String da coluna -> enum, tolerante a plano legado (null/blank) e a valor desconhecido. */
    default PlannerComplianceStatus resolvePlannerComplianceStatus(PlanoSemanal entity) {
        String status = entity.getPlannerComplianceStatus();
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return PlannerComplianceStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            PLANNER_LOG.warn("planner_compliance_status desconhecido no plano {}: '{}' — expondo null",
                    entity.getId(), status);
            return null;
        }
    }

    /**
     * Resumo legivel dos motivos (uma frase por {@link PlannerViolation}) lido do
     * planner_metadata_json. Plano legado sem metadata, JSON ilegivel ou sem violacoes -> null
     * (sem NPE, sem badge).
     */
    default List<String> resolvePlannerReviewReasons(PlanoSemanal entity) {
        String json = entity.getPlannerMetadataJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            PlannerAuditMetadata metadata = PLANNER_METADATA_MAPPER.readValue(json, PlannerAuditMetadata.class);
            if (metadata.violations() == null || metadata.violations().isEmpty()) {
                return null;
            }
            return metadata.violations().stream().map(PlannerViolation::mensagem).toList();
        } catch (Exception e) {
            PLANNER_LOG.warn("planner_metadata_json ilegivel no plano {}: {} — expondo motivos null",
                    entity.getId(), e.getMessage());
            return null;
        }
    }

    default String resolveAtletaNome(PlanoSemanal entity) {
        if (entity.getAtleta() == null) return null;
        String nome = entity.getAtleta().getNome();
        if (nome == null || nome.isBlank()) return null;
        String sobrenome = entity.getAtleta().getSobrenome();
        return (sobrenome != null && !sobrenome.isBlank()) ? nome + " " + sobrenome : nome;
    }

    default PlanoSemanalOutputDto toOutputDtoSafe(PlanoSemanal entity) {
        if (entity == null) throw new IllegalArgumentException("PlanoSemanal entity cannot be null");
        return toOutputDto(entity);
    }

    @Mapping(source = "treinosPlanejados", target = "treinosPlanejados")
    PlanoSemanal toEntity(PlanoSemanalLlmDto dto);

    /**
     * Após mapping, assegura que cada TreinoPlanejado referencie este PlanoSemanal
     */
    @AfterMapping
    default void linkPlanoSemanal(@MappingTarget PlanoSemanal plano) {

    }

    PlanoSemanal toEntity(TreinoPlanejadoLlmDto planoSemanalOutputDto);
}
