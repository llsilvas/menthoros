package br.com.menthoros.backend.controller;

import br.com.menthoros.backend.config.core.JacksonConfig;
import br.com.menthoros.backend.mapper.PlanoSemanalMapper;
import br.com.menthoros.backend.repository.TenantValidationRepository;
import br.com.menthoros.backend.repository.UsuarioRepository;
import br.com.menthoros.backend.services.AtletaProgressService;
import br.com.menthoros.backend.services.PlanoService;
import br.com.menthoros.backend.services.UsuarioSyncService;
import br.com.menthoros.backend.testsupport.AuthWebMvcTestConfig;
import br.com.menthoros.backend.testsupport.JwtTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static br.com.menthoros.backend.testsupport.JwtTestSupport.adminJwt;
import static br.com.menthoros.backend.testsupport.JwtTestSupport.atletaJwt;
import static br.com.menthoros.backend.testsupport.JwtTestSupport.tecnicoJwt;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Autorização do DELETE /api/v1/planos/{id}: TECNICO e ADMIN excluem (decisão do founder,
 * 2026-09-08 — o coach precisa excluir o plano da semana); ATLETA recebe 403. A regra de negócio
 * (só plano PLANEJADO, tenant-aware) fica no service e já é testada em PlanoServiceTenantTest.
 */
@WebMvcTest(PlanoTreinoController.class)
@Import({JacksonConfig.class, AuthWebMvcTestConfig.class})
class PlanoTreinoControllerDeleteAuthTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PlanoService planoService;
    @MockitoBean private AtletaProgressService atletaProgressService;
    @MockitoBean private PlanoSemanalMapper planoSemanalMapper;
    @MockitoBean private TenantValidationRepository tenantValidationRepository;
    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private UsuarioSyncService usuarioSyncService;
    @MockitoBean private UsuarioRepository usuarioRepository;

    private final UUID planoId = UUID.randomUUID();

    @BeforeEach
    void stubUsuarioAtivo() {
        JwtTestSupport.stubUsuarioAtivo(usuarioSyncService);
    }

    @Test
    @DisplayName("TECNICO exclui o plano: 204 e chama o service")
    void tecnicoExclui() throws Exception {
        mockMvc.perform(delete("/api/v1/planos/{id}", planoId).with(tecnicoJwt()))
                .andExpect(status().isNoContent());
        verify(planoService).deletePlanoSemanal(planoId);
    }

    @Test
    @DisplayName("ADMIN continua excluindo: 204")
    void adminExclui() throws Exception {
        mockMvc.perform(delete("/api/v1/planos/{id}", planoId).with(adminJwt()))
                .andExpect(status().isNoContent());
        verify(planoService).deletePlanoSemanal(planoId);
    }

    @Test
    @DisplayName("ATLETA não exclui: 403")
    void atletaNaoExclui() throws Exception {
        mockMvc.perform(delete("/api/v1/planos/{id}", planoId).with(atletaJwt()))
                .andExpect(status().isForbidden());
    }
}
