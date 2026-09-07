package br.com.menthoros.backend.controller;

import br.com.menthoros.backend.dto.input.AthleteInviteAcceptInputDto;
import br.com.menthoros.backend.dto.output.AthleteInviteLookupOutputDto;
import br.com.menthoros.backend.services.AthleteInviteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Convite de atleta — consulta e aceite públicos ({@code /api/public/**}, isento de tenant e sob
 * rate limit por IP no {@code PublicEndpointRateLimitFilter}). O aceite provisiona a conta
 * server-side (Keycloak + role ATLETA + Organization) e efetiva o vínculo com o Atleta do convite;
 * o primeiro login já nasce com {@code tenant_id} e role corretos.
 */
@RestController
@RequestMapping("/api/public/athlete-invites")
@RequiredArgsConstructor
@Tag(name = "athlete-invite", description = "Consulta e aceite públicos do convite de atleta")
public class AthleteInviteController {

    private final AthleteInviteService athleteInviteService;

    @GetMapping("/{token}")
    @Operation(summary = "Dados do convite para a página de cadastro",
            description = "404 para qualquer token que não esteja ativo, sem distinguir o motivo.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Convite ativo",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = AthleteInviteLookupOutputDto.class))),
            @ApiResponse(responseCode = "404", description = "Convite inexistente, expirado, invalidado ou já aceito"),
            @ApiResponse(responseCode = "429", description = "Muitas consultas do mesmo IP")
    })
    public ResponseEntity<AthleteInviteLookupOutputDto> consultar(
            @Parameter(description = "Token recebido por e-mail") @PathVariable("token") String token) {
        return ResponseEntity.ok(athleteInviteService.lookup(token));
    }

    @PostMapping("/aceitar")
    @Operation(summary = "Aceita o convite: cria a conta e vincula ao atleta",
            description = "Cria o usuário no Keycloak com role ATLETA e membership na Organization "
                    + "do tenant do convite; o vínculo Usuario-Atleta é efetivado pelo token, "
                    + "independente do e-mail escolhido.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Conta criada e vinculada"),
            @ApiResponse(responseCode = "404", description = "Token desconhecido", content = @Content),
            @ApiResponse(responseCode = "409", description = "E-mail já possui conta ou atleta já vinculado", content = @Content),
            @ApiResponse(responseCode = "410", description = "Convite expirado, invalidado ou já utilizado", content = @Content),
            @ApiResponse(responseCode = "400", description = "Payload inválido (Bean Validation)", content = @Content),
            @ApiResponse(responseCode = "422", description = "Assessoria sem onboarding no Keycloak", content = @Content),
            @ApiResponse(responseCode = "429", description = "Muitas tentativas do mesmo IP", content = @Content),
            @ApiResponse(responseCode = "502", description = "Falha no Keycloak", content = @Content)
    })
    public ResponseEntity<Void> aceitar(@Valid @RequestBody AthleteInviteAcceptInputDto input) {
        athleteInviteService.aceitar(input);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
