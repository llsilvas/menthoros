package br.com.menthoros.backend.dto.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Dados do convite de atleta que a página {@code /#/cadastro?convite=} usa para montar o
 * formulário. Não expõe estado interno do convite.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Dados públicos de um convite de atleta ativo")
public record AthleteInviteLookupOutputDto(
        @Schema(description = "Nome do atleta convidado", example = "Ana Silva")
        String nomeAtleta,

        @Schema(description = "Nome da assessoria que convidou", example = "Assessoria Alfa")
        String assessoria,

        @Schema(description = "E-mail sugerido (o do cadastro do atleta); o formulário permite trocar",
                example = "ana@exemplo.com")
        String emailSugerido
) {}
