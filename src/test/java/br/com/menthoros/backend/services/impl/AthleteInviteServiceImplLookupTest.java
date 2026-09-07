package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.dto.output.AthleteInviteLookupOutputDto;
import br.com.menthoros.backend.entity.Assessoria;
import br.com.menthoros.backend.entity.AthleteInvite;
import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.exception.DomainNotFoundException;
import br.com.menthoros.backend.repository.AssessoriaRepository;
import br.com.menthoros.backend.repository.AthleteInviteRepository;
import br.com.menthoros.backend.repository.AtletaRepository;
import br.com.menthoros.backend.repository.UsuarioRepository;
import br.com.menthoros.backend.security.InviteToken;
import br.com.menthoros.backend.services.KeycloakOrganizationGateway;
import br.com.menthoros.backend.services.email.EmailSender;
import br.com.menthoros.backend.services.email.EmailTemplateRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AthleteInviteServiceImplLookupTest {

    @Mock private AtletaRepository atletaRepository;
    @Mock private AthleteInviteRepository inviteRepository;
    @Mock private AssessoriaRepository assessoriaRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private KeycloakOrganizationGateway keycloak;
    @Mock private EmailSender emailSender;
    @Mock private EmailTemplateRenderer templates;
    @Mock private TransactionTemplate transactionTemplate;

    private AthleteInviteServiceImpl service;

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-05T12:00:00Z"), ZoneOffset.UTC);
    private static final String TOKEN = "token-lookup";

    private UUID tenantId;
    private UUID atletaId;
    private AthleteInvite convite;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        atletaId = UUID.randomUUID();
        convite = AthleteInvite.builder()
                .id(UUID.randomUUID())
                .atletaId(atletaId)
                .tenantId(tenantId)
                .tokenHash(InviteToken.hashOf(TOKEN))
                .emailEnviado("ana@teste.com")
                .expiresAt(OffsetDateTime.now(CLOCK).plusDays(3))
                .build();

        service = new AthleteInviteServiceImpl(
                atletaRepository, inviteRepository, assessoriaRepository, usuarioRepository,
                keycloak, emailSender, templates, transactionTemplate, CLOCK,
                "https://app.menthoros.com", 7);
    }

    @Nested
    @DisplayName("lookup")
    class Lookup {

        @Test
        @DisplayName("convite ativo retorna nome do atleta, assessoria e e-mail sugerido")
        void conviteAtivo() {
            Atleta atleta = new Atleta();
            atleta.setId(atletaId);
            atleta.setNome("Ana");
            Assessoria assessoria = new Assessoria();
            assessoria.setId(tenantId);
            assessoria.setNome("Assessoria Alfa");
            when(inviteRepository.findByTokenHash(InviteToken.hashOf(TOKEN))).thenReturn(Optional.of(convite));
            when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
            when(assessoriaRepository.findById(tenantId)).thenReturn(Optional.of(assessoria));

            AthleteInviteLookupOutputDto saida = service.lookup(TOKEN);

            assertThat(saida.nomeAtleta()).isEqualTo("Ana");
            assertThat(saida.assessoria()).isEqualTo("Assessoria Alfa");
            assertThat(saida.emailSugerido()).isEqualTo("ana@teste.com");
        }

        @Test
        @DisplayName("token desconhecido, nulo ou em branco é 404")
        void tokenDesconhecido() {
            when(inviteRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.lookup("nao-existe")).isInstanceOf(DomainNotFoundException.class);
            assertThatThrownBy(() -> service.lookup(null)).isInstanceOf(DomainNotFoundException.class);
            assertThatThrownBy(() -> service.lookup("  ")).isInstanceOf(DomainNotFoundException.class);
        }

        @Test
        @DisplayName("expirado, invalidado e aceito respondem o MESMO 404 — o estado não é público")
        void estadosInativosSaoIndistinguiveis() {
            convite.setExpiresAt(OffsetDateTime.now(CLOCK).minusMinutes(1));
            when(inviteRepository.findByTokenHash(InviteToken.hashOf(TOKEN))).thenReturn(Optional.of(convite));
            assertThatThrownBy(() -> service.lookup(TOKEN)).isInstanceOf(DomainNotFoundException.class);

            convite.setExpiresAt(OffsetDateTime.now(CLOCK).plusDays(1));
            convite.setInvalidatedAt(OffsetDateTime.now(CLOCK));
            assertThatThrownBy(() -> service.lookup(TOKEN)).isInstanceOf(DomainNotFoundException.class);

            convite.setInvalidatedAt(null);
            convite.setAcceptedAt(OffsetDateTime.now(CLOCK));
            assertThatThrownBy(() -> service.lookup(TOKEN)).isInstanceOf(DomainNotFoundException.class);
        }

        @Test
        @DisplayName("atleta ou assessoria sumidos respondem o mesmo 404 do token inválido")
        void atletaOuAssessoriaSumidos() {
            when(inviteRepository.findByTokenHash(InviteToken.hashOf(TOKEN))).thenReturn(Optional.of(convite));
            when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.lookup(TOKEN)).isInstanceOf(DomainNotFoundException.class);

            Atleta atleta = new Atleta();
            atleta.setId(atletaId);
            when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
            when(assessoriaRepository.findById(tenantId)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.lookup(TOKEN)).isInstanceOf(DomainNotFoundException.class);
        }
    }
}
