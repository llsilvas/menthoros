package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.config.keycloak.KeycloakAdminProperties;
import br.com.menthoros.backend.exception.KeycloakIntegrationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.POST;

class KeycloakOrganizationGatewayImplTest {

    private static final String SERVER_URL = "http://kc.test";
    private static final String TOKEN_ENDPOINT =
            SERVER_URL + "/realms/master/protocol/openid-connect/token";
    private static final String ORGANIZATIONS_ENDPOINT =
            SERVER_URL + "/admin/realms/menthoros/organizations";

    private MockRestServiceServer server;
    private KeycloakOrganizationGatewayImpl gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(SERVER_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        KeycloakAdminProperties props = new KeycloakAdminProperties();
        props.setServerUrl(SERVER_URL);
        props.setRealm("menthoros");
        props.setTokenRealm("master");
        props.setClientId("admin-cli");
        props.setUsername("admin");
        props.setPassword("pw");

        gateway = new KeycloakOrganizationGatewayImpl(restClient, props);
    }

    @Test
    void criarOrganizationRetornaIdDaLocation() {
        UUID tenantId = UUID.randomUUID();

        server.expect(requestTo(TOKEN_ENDPOINT))
                .andExpect(method(POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(containsString("grant_type=password")))
                .andRespond(withSuccess("{\"access_token\":\"tkn\"}", MediaType.APPLICATION_JSON));

        server.expect(requestTo(ORGANIZATIONS_ENDPOINT))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer tkn"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(containsString(tenantId.toString())))
                .andExpect(content().string(containsString("\"name\":\"Acme Run\"")))
                .andExpect(content().string(containsString("acme-run")))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .header("Location", ORGANIZATIONS_ENDPOINT + "/abc-123"));

        String orgId = gateway.criarOrganization("Acme Run", "acme-run", tenantId);

        assertThat(orgId).isEqualTo("abc-123");
        server.verify();
    }

    @Test
    void criarOrganizationLancaQuandoKeycloakFalha() {
        UUID tenantId = UUID.randomUUID();

        server.expect(requestTo(TOKEN_ENDPOINT))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"access_token\":\"tkn\"}", MediaType.APPLICATION_JSON));

        server.expect(requestTo(ORGANIZATIONS_ENDPOINT))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> gateway.criarOrganization("Acme Run", "acme-run", tenantId))
                .isInstanceOf(KeycloakIntegrationException.class);
    }

    @Test
    void criarOrganizationLancaQuandoSemLocation() {
        UUID tenantId = UUID.randomUUID();

        server.expect(requestTo(TOKEN_ENDPOINT))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"access_token\":\"tkn\"}", MediaType.APPLICATION_JSON));

        server.expect(requestTo(ORGANIZATIONS_ENDPOINT))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.CREATED));

        assertThatThrownBy(() -> gateway.criarOrganization("Acme Run", "acme-run", tenantId))
                .isInstanceOf(KeycloakIntegrationException.class);
    }


}
