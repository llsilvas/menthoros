package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.config.keycloak.KeycloakAdminProperties;
import br.com.menthoros.backend.exception.KeycloakIntegrationException;
import br.com.menthoros.backend.services.KeycloakOrganizationGateway;
import br.com.menthoros.backend.services.NovoUsuarioKeycloak;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter real do gateway de Keycloak Organizations, via Keycloak Admin REST API
 * (Spring {@link RestClient}). Cada operação obtém um token de admin (password grant
 * no realm de token) e então executa a chamada autenticada no realm de aplicação.
 *
 * <p>Segredos (senha de admin, tokens) nunca são logados.
 */
@Slf4j
@Service
public class KeycloakOrganizationGatewayImpl implements KeycloakOrganizationGateway {

    private final RestClient restClient;
    private final KeycloakAdminProperties props;

    public KeycloakOrganizationGatewayImpl(RestClient keycloakAdminRestClient, KeycloakAdminProperties props) {
        this.restClient = keycloakAdminRestClient;
        this.props = props;
    }

    /**
     * Cria uma Organization no realm da aplicação e injeta o atributo {@code tenant_id}.
     *
     * <p><strong>Idempotent:</strong> NO — cada chamada cria uma nova Organization.
     * <p><strong>Side Effects:</strong> External API (Keycloak) — cria Organization.
     * <p><strong>Tenant-aware:</strong> N/A — recebe o {@code tenantId} explicitamente.
     *
     * @param nome nome da assessoria
     * @param dominio slug ({@code ^[a-z0-9-]+$}), usado como alias e domínio
     * @param tenantId tenant a ser gravado no atributo {@code tenant_id}
     * @return o id da Organization criada no Keycloak
     * @throws KeycloakIntegrationException em falha de token, resposta não-2xx ou Location ausente
     */
    @Override
    public String criarOrganization(String nome, String dominio, UUID tenantId) {
        log.info("Criando Organization no Keycloak: nome={}, dominio={}, tenantId={}", nome, dominio, tenantId);
        String token = obterTokenAdmin();

        Map<String, Object> body = Map.of(
                "name", nome,
                "alias", dominio,
                "domains", List.of(Map.of("name", dominio, "verified", false)),
                "attributes", Map.of("tenant_id", List.of(tenantId.toString()))
        );

        try {
            ResponseEntity<Void> response = restClient.post()
                    .uri("/admin/realms/{realm}/organizations", props.getRealm())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            URI location = response.getHeaders().getLocation();
            if (location == null) {
                throw new KeycloakIntegrationException(
                        "Keycloak não retornou header Location ao criar Organization (dominio=" + dominio + ")");
            }
            String orgId = extrairIdDaLocation(location.toString());
            log.info("Organization criada no Keycloak: orgId={}, dominio={}", orgId, dominio);
            return orgId;
        } catch (KeycloakIntegrationException e) {
            throw e;
        } catch (Exception e) {
            throw new KeycloakIntegrationException(
                    "Falha ao criar Organization no Keycloak (dominio=" + dominio + ")", e);
        }
    }


    /**
     * Busca o id do usuário pelo e-mail, com correspondência exata.
     *
     * <p><strong>Idempotent:</strong> YES — leitura pura.
     * <p><strong>Side Effects:</strong> External API (Keycloak) — somente leitura.
     * <p><strong>Tenant-aware:</strong> N/A — busca no realm inteiro.
     */
    @Override
    public Optional<String> buscarUsuarioIdPorEmail(String email) {
        String token = obterTokenAdmin();
        try {
            // exact=true é obrigatório: sem ele o Keycloak trata o parâmetro como prefixo e
            // devolveria "maria@exemplo.com.br" numa busca por "maria@exemplo.com".
            List<Map<String, Object>> encontrados = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/admin/realms/{realm}/users")
                            .queryParam("email", email)
                            .queryParam("exact", true)
                            .build(props.getRealm()))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (encontrados == null || encontrados.isEmpty()) {
                return Optional.empty();
            }
            return Optional.ofNullable((String) encontrados.getFirst().get("id"));
        } catch (Exception e) {
            throw new KeycloakIntegrationException("Falha ao buscar usuário por e-mail no Keycloak", e);
        }
    }

    /**
     * Cria um usuário no realm, com senha definitiva.
     *
     * <p><strong>Idempotent:</strong> NO — uma segunda chamada com o mesmo e-mail responde 409.
     * <p><strong>Side Effects:</strong> External API (Keycloak) — cria usuário e define credencial.
     * <p><strong>Tenant-aware:</strong> N/A — o vínculo com o tenant é a Organization, em chamada separada.
     *
     * @return o id do usuário criado
     * @throws KeycloakIntegrationException em falha de token, resposta não-2xx ou Location ausente
     */
    @Override
    public String criarUsuario(NovoUsuarioKeycloak dados) {
        // `dados` NÃO entra no log: mesmo com o toString() protegido, evitar o hábito.
        log.info("Criando usuário no Keycloak: email={}, habilitado={}", dados.email(), dados.habilitado());
        String token = obterTokenAdmin();

        Map<String, Object> body = Map.of(
                "username", dados.email(),
                "email", dados.email(),
                "firstName", dados.nome(),
                "enabled", dados.habilitado(),
                "emailVerified", dados.emailVerificado(),
                "requiredActions", dados.acoesObrigatorias(),
                // temporary=false: senha temporária forçaria UPDATE_PASSWORD no primeiro login,
                // logo depois de o usuário tê-la acabado de escolher no formulário.
                "credentials", List.of(Map.of(
                        "type", "password",
                        "value", dados.senha(),
                        "temporary", false))
        );

        try {
            ResponseEntity<Void> response = restClient.post()
                    .uri("/admin/realms/{realm}/users", props.getRealm())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            URI location = response.getHeaders().getLocation();
            if (location == null) {
                throw new KeycloakIntegrationException(
                        "Keycloak não retornou header Location ao criar usuário (email=" + dados.email() + ")");
            }
            String usuarioId = extrairIdDaLocation(location.toString());
            log.info("Usuário criado no Keycloak: usuarioId={}", usuarioId);
            return usuarioId;
        } catch (KeycloakIntegrationException e) {
            throw e;
        } catch (Exception e) {
            // A mensagem carrega o e-mail, nunca a senha nem o corpo enviado.
            throw new KeycloakIntegrationException(
                    "Falha ao criar usuário no Keycloak (email=" + dados.email() + ")", e);
        }
    }

    /**
     * Habilita ou desabilita um usuário existente.
     *
     * <p><strong>Idempotent:</strong> YES — definir o mesmo estado de novo é no-op.
     * <p><strong>Side Effects:</strong> External API (Keycloak) — atualiza o usuário.
     * <p><strong>Tenant-aware:</strong> N/A
     */
    @Override
    public void definirHabilitado(String usuarioId, boolean habilitado) {
        log.info("Alterando habilitação do usuário no Keycloak: usuarioId={}, habilitado={}",
                usuarioId, habilitado);
        String token = obterTokenAdmin();
        try {
            // Update parcial: só `enabled`. Reenviar o representation inteiro arriscaria
            // sobrescrever campos alterados por outro caminho.
            restClient.put()
                    .uri("/admin/realms/{realm}/users/{id}", props.getRealm(), usuarioId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("enabled", habilitado))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            throw new KeycloakIntegrationException(
                    "Falha ao alterar habilitação do usuário no Keycloak (usuarioId=" + usuarioId + ")", e);
        }
    }

    /**
     * Atribui uma role de realm ao usuário.
     *
     * <p><strong>Idempotent:</strong> YES — reatribuir a mesma role é no-op no Keycloak.
     * <p><strong>Side Effects:</strong> External API (Keycloak) — cria role mapping.
     * <p><strong>Tenant-aware:</strong> N/A
     */
    @Override
    public void atribuirRoleDeRealm(String usuarioId, String role) {
        log.info("Atribuindo role de realm: usuarioId={}, role={}", usuarioId, role);
        String token = obterTokenAdmin();
        try {
            // O endpoint de role-mapping exige a representation completa da role (id + name),
            // não só o nome — daí a leitura antes da escrita.
            Map<String, Object> roleRepresentation = restClient.get()
                    .uri("/admin/realms/{realm}/roles/{role}", props.getRealm(), role)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (roleRepresentation == null || roleRepresentation.get("id") == null) {
                throw new KeycloakIntegrationException("Role não encontrada no realm: " + role);
            }

            restClient.post()
                    .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", props.getRealm(), usuarioId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of(roleRepresentation))
                    .retrieve()
                    .toBodilessEntity();
        } catch (KeycloakIntegrationException e) {
            throw e;
        } catch (Exception e) {
            throw new KeycloakIntegrationException(
                    "Falha ao atribuir role no Keycloak (usuarioId=" + usuarioId + ", role=" + role + ")", e);
        }
    }

    /**
     * Vincula um usuário existente a uma Organization.
     *
     * <p><strong>Idempotent:</strong> YES — o Keycloak aceita re-adicionar membro já vinculado.
     * <p><strong>Side Effects:</strong> External API (Keycloak) — cria vínculo de membro.
     * <p><strong>Tenant-aware:</strong> N/A — o escopo é a própria Organization.
     */
    @Override
    public void adicionarMembroNaOrganization(String organizationId, String usuarioId) {
        log.info("Vinculando usuário à Organization: orgId={}, usuarioId={}", organizationId, usuarioId);
        String token = obterTokenAdmin();
        try {
            // O corpo é o id do usuário como string JSON crua — não um objeto.
            restClient.post()
                    .uri("/admin/realms/{realm}/organizations/{orgId}/members",
                            props.getRealm(), organizationId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("\"" + usuarioId + "\"")
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            throw new KeycloakIntegrationException(
                    "Falha ao vincular usuário à Organization no Keycloak (orgId=" + organizationId + ")", e);
        }
    }

    /**
     * Dispara o e-mail nativo de verificação do Keycloak.
     *
     * <p><strong>Idempotent:</strong> NO — reenvia o e-mail a cada chamada.
     * <p><strong>Side Effects:</strong> External API (Keycloak) — envia e-mail via SMTP do realm.
     * <p><strong>Tenant-aware:</strong> N/A
     *
     * <p>Exige usuário <strong>habilitado</strong>: para usuário desabilitado o Keycloak 26.7
     * responde {@code 400 {"errorMessage":"User is disabled"}} e nenhum e-mail sai.
     */
    @Override
    public void enviarVerificacaoDeEmail(String usuarioId) {
        log.info("Disparando verificação de e-mail: usuarioId={}", usuarioId);
        String token = obterTokenAdmin();
        try {
            restClient.put()
                    .uri("/admin/realms/{realm}/users/{id}/send-verify-email", props.getRealm(), usuarioId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            throw new KeycloakIntegrationException(
                    "Falha ao enviar verificação de e-mail no Keycloak (usuarioId=" + usuarioId + ")", e);
        }
    }

    /**
     * Remove o usuário. Usado na compensação.
     *
     * <p><strong>Idempotent:</strong> YES — remover o que já não existe é sucesso.
     * <p><strong>Side Effects:</strong> External API (Keycloak) — remove usuário.
     * <p><strong>Tenant-aware:</strong> N/A
     */
    @Override
    public void removerUsuario(String usuarioId) {
        log.info("Removendo usuário no Keycloak (compensação): usuarioId={}", usuarioId);
        remover("/admin/realms/{realm}/users/{id}", usuarioId,
                "Falha ao remover usuário no Keycloak (usuarioId=" + usuarioId + ")");
    }

    /**
     * Remove a Organization. Usado na compensação.
     *
     * <p><strong>Idempotent:</strong> YES — remover o que já não existe é sucesso.
     * <p><strong>Side Effects:</strong> External API (Keycloak) — remove Organization.
     * <p><strong>Tenant-aware:</strong> N/A
     */
    @Override
    public void removerOrganization(String organizationId) {
        log.info("Removendo Organization no Keycloak (compensação): orgId={}", organizationId);
        remover("/admin/realms/{realm}/organizations/{id}", organizationId,
                "Falha ao remover Organization no Keycloak (orgId=" + organizationId + ")");
    }

    /**
     * DELETE tolerante a 404: a compensação precisa convergir, e "o recurso já não está lá" é
     * exatamente o estado que ela busca. Qualquer outra falha propaga — é ela que vira
     * RECONCILIATION_REQUIRED no orquestrador.
     */
    private void remover(String uriTemplate, String id, String mensagemDeErro) {
        String token = obterTokenAdmin();
        try {
            restClient.delete()
                    .uri(uriTemplate, props.getRealm(), id)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(),
                            (request, response) -> log.info("Recurso já inexistente na compensação: id={}", id))
                    .toBodilessEntity();
        } catch (Exception e) {
            throw new KeycloakIntegrationException(mensagemDeErro, e);
        }
    }

    private String obterTokenAdmin() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", props.getClientId());
        form.add("username", props.getUsername());
        form.add("password", props.getPassword());

        try {
            TokenResponse token = restClient.post()
                    .uri("/realms/{tokenRealm}/protocol/openid-connect/token", props.getTokenRealm())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);

            if (token == null || token.accessToken() == null || token.accessToken().isBlank()) {
                throw new KeycloakIntegrationException("Keycloak retornou token de admin vazio");
            }
            return token.accessToken();
        } catch (KeycloakIntegrationException e) {
            throw e;
        } catch (Exception e) {
            throw new KeycloakIntegrationException("Falha ao obter token de admin no Keycloak", e);
        }
    }

    private String extrairIdDaLocation(String location) {
        int idx = location.lastIndexOf('/');
        if (idx < 0 || idx == location.length() - 1) {
            throw new KeycloakIntegrationException("Location inválido retornado pelo Keycloak: " + location);
        }
        return location.substring(idx + 1);
    }

    private record TokenResponse(
            @JsonProperty("access_token") String accessToken) {
    }
}
