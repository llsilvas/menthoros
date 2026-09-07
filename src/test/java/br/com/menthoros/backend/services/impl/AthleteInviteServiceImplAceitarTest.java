package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.dto.input.AthleteInviteAcceptInputDto;
import br.com.menthoros.backend.entity.Assessoria;
import br.com.menthoros.backend.entity.AthleteInvite;
import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.entity.Usuario;
import br.com.menthoros.backend.exception.DomainConflictException;
import br.com.menthoros.backend.exception.DomainGoneException;
import br.com.menthoros.backend.exception.DomainNotFoundException;
import br.com.menthoros.backend.exception.KeycloakIntegrationException;
import br.com.menthoros.backend.repository.AssessoriaRepository;
import br.com.menthoros.backend.repository.AthleteInviteRepository;
import br.com.menthoros.backend.repository.AtletaRepository;
import br.com.menthoros.backend.repository.UsuarioRepository;
import br.com.menthoros.backend.security.InviteToken;
import br.com.menthoros.backend.services.KeycloakOrganizationGateway;
import br.com.menthoros.backend.services.NovoUsuarioKeycloak;
import br.com.menthoros.backend.services.email.EmailSender;
import br.com.menthoros.backend.services.email.EmailTemplateRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AthleteInviteServiceImplAceitarTest {

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
            Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC);
    private static final String TOKEN = "token-cru-de-teste";
    private static final String EMAIL_CONVITE = "ana@teste.com";
    private static final String KEYCLOAK_USER_ID = UUID.randomUUID().toString();

    private UUID tenantId;
    private UUID atletaId;
    private AthleteInvite convite;
    private Atleta atleta;
    private Assessoria assessoria;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        atletaId = UUID.randomUUID();

        convite = AthleteInvite.builder()
                .id(UUID.randomUUID())
                .atletaId(atletaId)
                .tenantId(tenantId)
                .tokenHash(InviteToken.hashOf(TOKEN))
                .emailEnviado(EMAIL_CONVITE)
                .expiresAt(OffsetDateTime.now(CLOCK).plusDays(3))
                .build();

        assessoria = new Assessoria();
        assessoria.setId(tenantId);
        assessoria.setNome("Assessoria Alfa");
        assessoria.setKeycloakOrganizationId("org-1");

        atleta = new Atleta();
        atleta.setId(atletaId);
        atleta.setNome("Ana");
        atleta.setEmail(EMAIL_CONVITE);
        atleta.setAssessoria(assessoria);

        service = new AthleteInviteServiceImpl(
                atletaRepository, inviteRepository, assessoriaRepository, usuarioRepository,
                keycloak, emailSender, templates, transactionTemplate, CLOCK,
                "https://app.menthoros.com", 7);
    }

    private void mockCaminhoFeliz() {
        when(inviteRepository.findByTokenHash(InviteToken.hashOf(TOKEN))).thenReturn(Optional.of(convite));
        when(inviteRepository.claim(eq(convite.getId()), any())).thenReturn(1);
        when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
        when(assessoriaRepository.findById(tenantId)).thenReturn(Optional.of(assessoria));
        when(keycloak.buscarUsuarioIdPorEmail(anyString())).thenReturn(Optional.empty());
        lenient().when(keycloak.criarUsuario(any())).thenReturn(KEYCLOAK_USER_ID);
        // TransactionTemplate executa o callback de verdade, fora de Spring
        lenient().doAnswer(inv -> {
            inv.getArgument(0, Consumer.class).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        lenient().when(usuarioRepository.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(atletaRepository.save(any(Atleta.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(inviteRepository.save(any(AthleteInvite.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AthleteInviteAcceptInputDto input(String email) {
        return new AthleteInviteAcceptInputDto(TOKEN, "Ana Silva", "senha-muito-segura", email);
    }

    @Nested
    @DisplayName("caminho feliz")
    class CaminhoFeliz {

        @Test
        @DisplayName("e-mail do convite: cria conta verificada, role ATLETA, Organization e vínculo")
        void emailDoConvite() {
            mockCaminhoFeliz();

            service.aceitar(input(null));

            ArgumentCaptor<NovoUsuarioKeycloak> novo = ArgumentCaptor.forClass(NovoUsuarioKeycloak.class);
            verify(keycloak).criarUsuario(novo.capture());
            assertThat(novo.getValue().email()).isEqualTo(EMAIL_CONVITE);
            assertThat(novo.getValue().emailVerificado()).isTrue();
            verify(keycloak).atribuirRoleDeRealm(KEYCLOAK_USER_ID, "ATLETA");
            verify(keycloak).adicionarMembroNaOrganization("org-1", KEYCLOAK_USER_ID);
            verify(keycloak, never()).enviarVerificacaoDeEmail(any());

            assertThat(atleta.getUsuario()).isNotNull();
            assertThat(atleta.getUsuario().getKeycloakId()).isEqualTo(KEYCLOAK_USER_ID);
            assertThat(convite.getAcceptedAt()).isEqualTo(OffsetDateTime.now(CLOCK));
        }

        @Test
        @DisplayName("e-mail divergente: vincula mesmo assim, conta nasce não verificada")
        void emailDivergente() {
            mockCaminhoFeliz();

            service.aceitar(input("outro@teste.com"));

            ArgumentCaptor<NovoUsuarioKeycloak> novo = ArgumentCaptor.forClass(NovoUsuarioKeycloak.class);
            verify(keycloak).criarUsuario(novo.capture());
            assertThat(novo.getValue().email()).isEqualTo("outro@teste.com");
            assertThat(novo.getValue().emailVerificado()).isFalse();
            verify(keycloak).enviarVerificacaoDeEmail(KEYCLOAK_USER_ID);
            assertThat(atleta.getUsuario()).isNotNull();
        }
    }

    @Nested
    @DisplayName("recusas")
    class Recusas {

        @Test
        @DisplayName("token desconhecido é 404")
        void tokenDesconhecido() {
            when(inviteRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.aceitar(input(null)))
                    .isInstanceOf(DomainNotFoundException.class);
            verify(keycloak, never()).criarUsuario(any());
        }

        @Test
        @DisplayName("convite expirado é 410")
        void expirado() {
            convite.setExpiresAt(OffsetDateTime.now(CLOCK).minusMinutes(1));
            when(inviteRepository.findByTokenHash(InviteToken.hashOf(TOKEN))).thenReturn(Optional.of(convite));

            assertThatThrownBy(() -> service.aceitar(input(null)))
                    .isInstanceOf(DomainGoneException.class);
            verify(inviteRepository, never()).claim(any(), any());
        }

        @Test
        @DisplayName("convite já aceito é 410")
        void jaAceito() {
            convite.setAcceptedAt(OffsetDateTime.now(CLOCK).minusHours(1));
            when(inviteRepository.findByTokenHash(InviteToken.hashOf(TOKEN))).thenReturn(Optional.of(convite));

            assertThatThrownBy(() -> service.aceitar(input(null)))
                    .isInstanceOf(DomainGoneException.class);
        }

        @Test
        @DisplayName("duplo POST concorrente: quem perde o claim recebe 410 e não provisiona")
        void corridaDeAceite() {
            when(inviteRepository.findByTokenHash(InviteToken.hashOf(TOKEN))).thenReturn(Optional.of(convite));
            when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
            when(assessoriaRepository.findById(tenantId)).thenReturn(Optional.of(assessoria));
            when(keycloak.buscarUsuarioIdPorEmail(EMAIL_CONVITE)).thenReturn(Optional.empty());
            when(inviteRepository.claim(eq(convite.getId()), any())).thenReturn(0);

            assertThatThrownBy(() -> service.aceitar(input(null)))
                    .isInstanceOf(DomainGoneException.class);
            verify(keycloak, never()).criarUsuario(any());
        }

        // Rejeições de domínio rodam ANTES do claim (achado do QA: claim vazado responderia 410
        // para sempre num erro recuperável) — todas verificam que o claim nem foi tentado.

        @Test
        @DisplayName("atleta já vinculado a outra conta é 409 — sem claim")
        void atletaJaVinculado() {
            Usuario outro = new Usuario();
            outro.setId(UUID.randomUUID());
            atleta.setUsuario(outro);
            when(inviteRepository.findByTokenHash(InviteToken.hashOf(TOKEN))).thenReturn(Optional.of(convite));
            when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));

            assertThatThrownBy(() -> service.aceitar(input(null)))
                    .isInstanceOf(DomainConflictException.class);
            verify(inviteRepository, never()).claim(any(), any());
            verify(keycloak, never()).criarUsuario(any());
        }

        @Test
        @DisplayName("assessoria do convite não encontrada é 404 — sem claim")
        void assessoriaNaoEncontrada() {
            when(inviteRepository.findByTokenHash(InviteToken.hashOf(TOKEN))).thenReturn(Optional.of(convite));
            when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
            when(assessoriaRepository.findById(tenantId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.aceitar(input(null)))
                    .isInstanceOf(DomainNotFoundException.class);
            verify(inviteRepository, never()).claim(any(), any());
        }

        @Test
        @DisplayName("assessoria sem keycloakOrganizationId é 422 — sem claim, e o retry funciona pós-onboarding")
        void assessoriaSemOrganizationId() {
            assessoria.setKeycloakOrganizationId(null);
            when(inviteRepository.findByTokenHash(InviteToken.hashOf(TOKEN))).thenReturn(Optional.of(convite));
            when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
            when(assessoriaRepository.findById(tenantId)).thenReturn(Optional.of(assessoria));

            assertThatThrownBy(() -> service.aceitar(input(null)))
                    .isInstanceOf(br.com.menthoros.backend.exception.DomainRuleViolationException.class);
            verify(inviteRepository, never()).claim(any(), any());
            verify(keycloak, never()).criarUsuario(any());
        }

        @Test
        @DisplayName("e-mail já existente no realm é 409 — sem claim, token continua utilizável")
        void emailJaExiste() {
            when(inviteRepository.findByTokenHash(InviteToken.hashOf(TOKEN))).thenReturn(Optional.of(convite));
            when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
            when(assessoriaRepository.findById(tenantId)).thenReturn(Optional.of(assessoria));
            when(keycloak.buscarUsuarioIdPorEmail(EMAIL_CONVITE)).thenReturn(Optional.of("existente"));

            assertThatThrownBy(() -> service.aceitar(input(null)))
                    .isInstanceOf(DomainConflictException.class);
            verify(inviteRepository, never()).claim(any(), any());
            verify(keycloak, never()).criarUsuario(any());
        }
    }

    @Nested
    @DisplayName("compensação")
    class Compensacao {

        @Test
        @DisplayName("falha após criar usuário: remove o usuário, reabre o claim e propaga")
        void falhaAposCriarUsuario() {
            mockCaminhoFeliz();
            doThrow(new KeycloakIntegrationException("org fora", null))
                    .when(keycloak).adicionarMembroNaOrganization(any(), any());

            assertThatThrownBy(() -> service.aceitar(input(null)))
                    .isInstanceOf(KeycloakIntegrationException.class);

            verify(keycloak).removerUsuario(KEYCLOAK_USER_ID);
            verify(inviteRepository).liberarClaim(convite.getId());
            assertThat(convite.getAcceptedAt()).isNull();
        }

        @Test
        @DisplayName("falha na criação do usuário: nada a remover, claim reaberto")
        void falhaNaCriacao() {
            mockCaminhoFeliz();
            when(keycloak.criarUsuario(any()))
                    .thenThrow(new KeycloakIntegrationException("keycloak fora", null));

            assertThatThrownBy(() -> service.aceitar(input(null)))
                    .isInstanceOf(KeycloakIntegrationException.class);

            verify(keycloak, never()).removerUsuario(any());
            verify(inviteRepository).liberarClaim(convite.getId());
        }
    }
}
