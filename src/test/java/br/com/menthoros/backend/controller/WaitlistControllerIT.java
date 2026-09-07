package br.com.menthoros.backend.controller;

import br.com.menthoros.backend.AbstractIntegrationTest;
import br.com.menthoros.backend.entity.Waitlist;
import br.com.menthoros.backend.enums.FaixaAtletas;
import br.com.menthoros.backend.enums.PerfilWaitlist;
import br.com.menthoros.backend.repository.WaitlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests do endpoint público {@code POST /api/v1/waitlist}.
 *
 * <p>Usa o contexto real (segurança permitAll, {@code JwtTenantFilter}, rate-limit, Flyway/Postgres).
 * Sem autenticação — cadastro pré-signup. Cada teste usa um {@code X-Forwarded-For} distinto para
 * isolar o contador de rate-limit por IP entre os métodos.
 */
@AutoConfigureMockMvc
@DisplayName("POST /api/v1/waitlist")
class WaitlistControllerIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WaitlistRepository waitlistRepository;

    @BeforeEach
    void limparWaitlist() {
        waitlistRepository.deleteAll();
    }

    private String body(String nome, String email, String perfil, boolean aceite, String website,
                        String qtdAtletas) {
        return body(nome, email, perfil, aceite, website, qtdAtletas, null, null, null, null);
    }

    private String body(String nome, String email, String perfil, boolean aceite, String website,
                        String qtdAtletas, String utmSource, String utmMedium, String utmCampaign, String utmContent) {
        StringBuilder sb = new StringBuilder("{");
        if (nome != null) sb.append("\"nome\":\"").append(nome).append("\",");
        if (email != null) sb.append("\"email\":\"").append(email).append("\",");
        sb.append("\"perfil\":\"").append(perfil).append("\",");
        if (qtdAtletas != null) sb.append("\"qtdAtletas\":\"").append(qtdAtletas).append("\",");
        if (website != null) sb.append("\"website\":\"").append(website).append("\",");
        if (utmSource != null) sb.append("\"utmSource\":\"").append(utmSource).append("\",");
        if (utmMedium != null) sb.append("\"utmMedium\":\"").append(utmMedium).append("\",");
        if (utmCampaign != null) sb.append("\"utmCampaign\":\"").append(utmCampaign).append("\",");
        if (utmContent != null) sb.append("\"utmContent\":\"").append(utmContent).append("\",");
        sb.append("\"aceiteLgpd\":").append(aceite).append("}");
        return sb.toString();
    }

    @Test
    @DisplayName("cadastro válido sem autenticação retorna 201 e persiste com aceite_lgpd (sem CSRF)")
    void cadastroValidoSemAuth() throws Exception {
        mockMvc.perform(post("/api/v1/waitlist")
                        .header("X-Forwarded-For", "10.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Maria", "maria@exemplo.com", "TREINADOR", true, null, "DE_11_A_30")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CRIADO"))
                .andExpect(jsonPath("$.mensagem").isNotEmpty());

        assertThat(waitlistRepository.existsByEmailNormalized("maria@exemplo.com")).isTrue();
        Waitlist salvo = waitlistRepository.findAll().get(0);
        assertThat(salvo.isAceiteLgpd()).isTrue();
        assertThat(salvo.getPerfil()).isEqualTo(PerfilWaitlist.TREINADOR);
        assertThat(salvo.getQtdAtletas()).isEqualTo(FaixaAtletas.DE_11_A_30);
        assertThat(salvo.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("corpo sem nome retorna 400 e não persiste")
    void semNomeRetorna400() throws Exception {
        mockMvc.perform(post("/api/v1/waitlist")
                        .header("X-Forwarded-For", "10.0.0.2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "sem-nome@exemplo.com", "ATLETA", true, null, null)))
                .andExpect(status().isBadRequest());

        assertThat(waitlistRepository.count()).isZero();
    }

    @Test
    @DisplayName("aceiteLgpd=false retorna 400")
    void aceiteLgpdFalsoRetorna400() throws Exception {
        mockMvc.perform(post("/api/v1/waitlist")
                        .header("X-Forwarded-For", "10.0.0.3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Joao", "joao@exemplo.com", "ATLETA", false, null, null)))
                .andExpect(status().isBadRequest());

        assertThat(waitlistRepository.count()).isZero();
    }

    @Test
    @DisplayName("e-mail duplicado retorna 200 sem criar segunda linha")
    void emailDuplicadoRetorna200() throws Exception {
        String corpo = body("Maria", "dup@exemplo.com", "TREINADOR", true, null, "ATE_10");
        mockMvc.perform(post("/api/v1/waitlist")
                        .header("X-Forwarded-For", "10.0.0.4")
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/waitlist")
                        .header("X-Forwarded-For", "10.0.0.4")
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("JA_INSCRITO"));

        assertThat(waitlistRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("com UTM na inscrição, persiste os 4 campos em tb_waitlist")
    void comUtmPersisteOs4Campos() throws Exception {
        mockMvc.perform(post("/api/v1/waitlist")
                        .header("X-Forwarded-For", "10.0.0.6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Maria", "utm@exemplo.com", "TREINADOR", true, null, "DE_11_A_30",
                                "instagram", "social", "turma-fundadora", "bio-link")))
                .andExpect(status().isCreated());

        Waitlist salvo = waitlistRepository.findAll().stream()
                .filter(w -> "utm@exemplo.com".equals(w.getEmail()))
                .findFirst().orElseThrow();
        assertThat(salvo.getUtmSource()).isEqualTo("instagram");
        assertThat(salvo.getUtmMedium()).isEqualTo("social");
        assertThat(salvo.getUtmCampaign()).isEqualTo("turma-fundadora");
        assertThat(salvo.getUtmContent()).isEqualTo("bio-link");
    }

    @Test
    @DisplayName("sem UTM na inscrição, as 4 colunas nascem null (cliente antigo intocado)")
    void semUtmColunasNascemNull() throws Exception {
        mockMvc.perform(post("/api/v1/waitlist")
                        .header("X-Forwarded-For", "10.0.0.7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Joao", "sem-utm@exemplo.com", "ATLETA", true, null, null)))
                .andExpect(status().isCreated());

        Waitlist salvo = waitlistRepository.findAll().stream()
                .filter(w -> "sem-utm@exemplo.com".equals(w.getEmail()))
                .findFirst().orElseThrow();
        assertThat(salvo.getUtmSource()).isNull();
        assertThat(salvo.getUtmMedium()).isNull();
        assertThat(salvo.getUtmCampaign()).isNull();
        assertThat(salvo.getUtmContent()).isNull();
    }

    @Test
    @DisplayName("honeypot preenchido retorna 201 sem persistir")
    void honeypotNaoPersiste() throws Exception {
        mockMvc.perform(post("/api/v1/waitlist")
                        .header("X-Forwarded-For", "10.0.0.5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Bot", "bot@exemplo.com", "ATLETA", true, "http://spam.example", null)))
                .andExpect(status().isCreated());

        assertThat(waitlistRepository.existsByEmailNormalized("bot@exemplo.com")).isFalse();
    }

    @Test
    @DisplayName("excedente do rate-limit por IP retorna 429")
    void rateLimitRetorna429() throws Exception {
        String corpo = body("Maria", "rate@exemplo.com", "TREINADOR", true, null, "ATE_10");
        // 5 primeiras passam (1ª cria -> 201, demais duplicadas -> 200); a 6ª é bloqueada.
        for (int i = 1; i <= 5; i++) {
            int esperado = (i == 1) ? 201 : 200;
            mockMvc.perform(post("/api/v1/waitlist")
                            .header("X-Forwarded-For", "10.9.9.9")
                            .contentType(MediaType.APPLICATION_JSON).content(corpo))
                    .andExpect(status().is(esperado));
        }
        mockMvc.perform(post("/api/v1/waitlist")
                        .header("X-Forwarded-For", "10.9.9.9")
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isTooManyRequests());
    }
}
