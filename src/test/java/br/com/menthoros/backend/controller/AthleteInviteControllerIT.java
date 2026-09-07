package br.com.menthoros.backend.controller;

import br.com.menthoros.backend.AbstractIntegrationTest;
import br.com.menthoros.backend.entity.Assessoria;
import br.com.menthoros.backend.entity.AthleteInvite;
import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.enums.AtletaStatus;
import br.com.menthoros.backend.enums.NivelExperiencia;
import br.com.menthoros.backend.repository.AssessoriaRepository;
import br.com.menthoros.backend.repository.AthleteInviteRepository;
import br.com.menthoros.backend.repository.AtletaRepository;
import br.com.menthoros.backend.repository.UsuarioRepository;
import br.com.menthoros.backend.security.InviteToken;
import br.com.menthoros.backend.services.KeycloakOrganizationGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato dos endpoints públicos do convite de atleta e do fluxo pós-aceite — o caminho que o
 * incidente de 2026-09-04 quebrou: atleta órfão respondia 404 em todo {@code /me/*}.
 *
 * <p>O gateway do Keycloak é mockado ({@code @MockitoBean}): o provisionamento externo tem os seus
 * próprios testes; aqui interessa o contrato HTTP + as escritas locais (vínculo, consumo do token).</p>
 */
@AutoConfigureMockMvc
@DisplayName("/api/public/athlete-invites")
class AthleteInviteControllerIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AssessoriaRepository assessoriaRepository;
    @Autowired private AtletaRepository atletaRepository;
    @Autowired private AthleteInviteRepository inviteRepository;
    @Autowired private UsuarioRepository usuarioRepository;

    @MockitoBean private KeycloakOrganizationGateway keycloak;

    private Assessoria assessoria;
    private Atleta atleta;
    private String tokenCru;

    @BeforeEach
    void preparar() {
        inviteRepository.deleteAll();

        assessoria = assessoriaRepository.save(Assessoria.builder()
                .nome("Assessoria IT " + UUID.randomUUID())
                .dominio("it-" + UUID.randomUUID())
                .plano(br.com.menthoros.backend.enums.PlanoAssessoria.BASIC)
                .keycloakOrganizationId("org-it-" + UUID.randomUUID())
                .build());

        atleta = atletaRepository.save(Atleta.builder()
                .nome("Ana IT")
                .email("ana-" + UUID.randomUUID() + "@it.test")
                .objetivo("Meia maratona")
                .nivelExperiencia(NivelExperiencia.INTERMEDIARIO)
                .ativo(AtletaStatus.ATIVO)
                .assessoria(assessoria)
                .build());

        InviteToken token = InviteToken.generate();
        tokenCru = token.value();
        inviteRepository.save(AthleteInvite.builder()
                .atletaId(atleta.getId())
                .tenantId(assessoria.getId())
                .tokenHash(token.hash())
                .emailEnviado(atleta.getEmail())
                .sentAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusDays(7))
                .build());
    }

    private String bodyAceite(String email) {
        String emailJson = email == null ? "" : ",\"email\":\"" + email + "\"";
        return "{\"token\":\"" + tokenCru + "\",\"nome\":\"Ana IT\","
                + "\"senha\":\"senha-muito-segura\"" + emailJson + "}";
    }

    @Nested
    @DisplayName("POST /api/v1/atletas/{id}/convite (emissão — coach)")
    class Emissao {

        /**
         * Regressão do ensaio de 2026-09-05: a emissão roda sem transação e lia
         * {@code atleta.getAssessoria().getNome()} — proxy LAZY fora de sessão, 500 no primeiro
         * convite real. Este IT exercita a emissão inteira contra o contexto real (o carteiro do
         * profile integration grava .eml em disco, sem SMTP).
         */
        @Test
        @DisplayName("coach emite o convite: 202, hash persistido e sent_at preenchido")
        void coachEmiteConvite() throws Exception {
            inviteRepository.deleteAll(); // remove o convite do preparar() — a emissão cria o dela

            mockMvc.perform(post("/api/v1/atletas/{id}/convite", atleta.getId())
                            .with(jwt()
                                    .authorities(new SimpleGrantedAuthority("ROLE_TECNICO"))
                                    .jwt(j -> j.subject(UUID.randomUUID().toString())
                                            .claim("email", "coach@it.test")
                                            .claim("organization", Map.of("assessoria-it",
                                                    Map.of("tenant_id", List.of(assessoria.getId().toString())))))))
                    .andExpect(status().isAccepted());

            var convites = inviteRepository.findAll();
            assertThat(convites).hasSize(1);
            assertThat(convites.get(0).getAtletaId()).isEqualTo(atleta.getId());
            assertThat(convites.get(0).getTokenHash()).hasSize(64);
            assertThat(convites.get(0).getSentAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("GET /{token} (lookup)")
    class Lookup {

        @Test
        @DisplayName("token ativo retorna nome, assessoria e e-mail sugerido")
        void tokenAtivo() throws Exception {
            mockMvc.perform(get("/api/public/athlete-invites/{token}", tokenCru)
                            .header("X-Forwarded-For", "10.1.0.1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nomeAtleta").value("Ana IT"))
                    .andExpect(jsonPath("$.emailSugerido").value(atleta.getEmail()));
        }

        @Test
        @DisplayName("token desconhecido retorna 404 sem revelar estado")
        void tokenDesconhecido() throws Exception {
            mockMvc.perform(get("/api/public/athlete-invites/{token}", "nao-existe")
                            .header("X-Forwarded-For", "10.1.0.2"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("excedente do rate-limit por IP retorna 429")
        void rateLimit() throws Exception {
            for (int i = 0; i < 10; i++) {
                mockMvc.perform(get("/api/public/athlete-invites/{token}", tokenCru)
                                .header("X-Forwarded-For", "10.1.9.9"))
                        .andExpect(status().isOk());
            }
            mockMvc.perform(get("/api/public/athlete-invites/{token}", tokenCru)
                            .header("X-Forwarded-For", "10.1.9.9"))
                    .andExpect(status().isTooManyRequests());
        }
    }

    @Nested
    @DisplayName("POST /aceitar")
    class Aceitar {

        @Test
        @DisplayName("aceite com e-mail divergente vincula o atleta e consome o convite; primeiro login acessa /me/*")
        void aceiteComEmailDivergenteEPrimeiroLogin() throws Exception {
            String keycloakUserId = UUID.randomUUID().toString();
            when(keycloak.buscarUsuarioIdPorEmail(anyString())).thenReturn(Optional.empty());
            when(keycloak.criarUsuario(any())).thenReturn(keycloakUserId);

            mockMvc.perform(post("/api/public/athlete-invites/aceitar")
                            .header("X-Forwarded-For", "10.2.0.1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyAceite("email-novo@it.test")))
                    .andExpect(status().isCreated());

            Atleta vinculado = atletaRepository.findById(atleta.getId()).orElseThrow();
            assertThat(vinculado.getUsuario()).isNotNull();
            // id do proxy LAZY é seguro fora de sessão; getKeycloakId inicializaria o proxy
            assertThat(vinculado.getUsuario().getId()).isEqualTo(UUID.fromString(keycloakUserId));
            assertThat(usuarioRepository.findByKeycloakId(keycloakUserId)).isPresent();

            // 2.4 — o caminho que o incidente quebrou: primeiro login já resolve o atleta
            mockMvc.perform(get("/api/v1/atletas/me/provas").with(jwtDePrimeiroLogin(keycloakUserId)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/atletas/me/home").with(jwtDePrimeiroLogin(keycloakUserId)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("segundo aceite do mesmo token retorna 410 e não provisiona de novo")
        void segundoAceiteRetorna410() throws Exception {
            String keycloakUserId = UUID.randomUUID().toString();
            when(keycloak.buscarUsuarioIdPorEmail(anyString())).thenReturn(Optional.empty());
            when(keycloak.criarUsuario(any())).thenReturn(keycloakUserId);

            mockMvc.perform(post("/api/public/athlete-invites/aceitar")
                            .header("X-Forwarded-For", "10.2.0.2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyAceite(null)))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/public/athlete-invites/aceitar")
                            .header("X-Forwarded-For", "10.2.0.3")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyAceite(null)))
                    .andExpect(status().isGone());
        }

        @Test
        @DisplayName("e-mail já existente no realm retorna 409 e o token continua utilizável")
        void emailJaExistente() throws Exception {
            when(keycloak.buscarUsuarioIdPorEmail(anyString())).thenReturn(Optional.of("ja-existe"));

            mockMvc.perform(post("/api/public/athlete-invites/aceitar")
                            .header("X-Forwarded-For", "10.2.0.4")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyAceite(null)))
                    .andExpect(status().isConflict());

            // claim reaberto: o mesmo token volta a ser aceito quando o conflito se resolve
            when(keycloak.buscarUsuarioIdPorEmail(anyString())).thenReturn(Optional.empty());
            when(keycloak.criarUsuario(any())).thenReturn(UUID.randomUUID().toString());
            mockMvc.perform(post("/api/public/athlete-invites/aceitar")
                            .header("X-Forwarded-For", "10.2.0.5")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyAceite(null)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("token desconhecido retorna 404")
        void tokenDesconhecido() throws Exception {
            mockMvc.perform(post("/api/public/athlete-invites/aceitar")
                            .header("X-Forwarded-For", "10.2.0.6")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"x\",\"nome\":\"A\",\"senha\":\"senha-muito-segura\"}"))
                    .andExpect(status().isNotFound());
        }
    }

    /** JWT como o primeiro login real: subject = keycloakId, claim organization com o tenant, ROLE_ATLETA. */
    private RequestPostProcessor jwtDePrimeiroLogin(String keycloakUserId) {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_ATLETA"))
                .jwt(j -> j.subject(keycloakUserId)
                        .claim("email", "email-novo@it.test")
                        .claim("organization", Map.of("assessoria-it",
                                Map.of("tenant_id", List.of(assessoria.getId().toString())))));
    }
}
