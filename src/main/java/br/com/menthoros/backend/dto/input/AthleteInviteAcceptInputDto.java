package br.com.menthoros.backend.dto.input;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Aceite do convite de atleta: cria a conta e efetiva o vínculo com o {@code Atleta} do convite.
 * O e-mail é opcional — ausente, vale o e-mail do convite; presente e diferente, a conta nasce com
 * verificação de e-mail pendente.
 */
@Schema(description = "Aceite público do convite de atleta")
public record AthleteInviteAcceptInputDto(
        @NotBlank
        @Schema(description = "Token recebido por e-mail")
        String token,

        @NotBlank
        @Size(max = 120)
        @Schema(description = "Nome do atleta", example = "Ana Silva")
        String nome,

        @NotBlank
        @Size(min = TAMANHO_MINIMO_DA_SENHA, max = TAMANHO_MAXIMO_DA_SENHA)
        @Schema(description = "Senha da nova conta")
        String senha,

        @Email
        @Size(max = 100)
        @Schema(description = "E-mail da conta; ausente, vale o e-mail do convite",
                example = "ana@exemplo.com")
        String email
) {
    /** Mesmos limites do cadastro de coach ({@code CoachSignupInputDto}). */
    public static final int TAMANHO_MINIMO_DA_SENHA = 12;
    public static final int TAMANHO_MAXIMO_DA_SENHA = 128;

    @Override
    public String toString() {
        // Nunca imprimir token nem senha — o record padrão os colocaria em qualquer log acidental.
        return "AthleteInviteAcceptInputDto[nome=%s, email=%s, token=***, senha=***]"
                .formatted(nome, email);
    }
}
