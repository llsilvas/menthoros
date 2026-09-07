package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.entity.Assessoria;
import br.com.menthoros.backend.entity.AthleteInvite;
import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.exception.DomainConflictException;
import br.com.menthoros.backend.exception.DomainNotFoundException;
import br.com.menthoros.backend.exception.DomainRuleViolationException;
import br.com.menthoros.backend.multitenancy.TenantContext;
import br.com.menthoros.backend.repository.AthleteInviteRepository;
import br.com.menthoros.backend.repository.AtletaRepository;
import br.com.menthoros.backend.exception.EmailDeliveryException;
import br.com.menthoros.backend.services.email.EmailMessage;
import br.com.menthoros.backend.services.email.EmailSender;
import br.com.menthoros.backend.services.email.EmailTemplateRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AthleteInviteServiceImplInviteTest {

    @Mock private AtletaRepository atletaRepository;
    @Mock private AthleteInviteRepository inviteRepository;
    @Mock private br.com.menthoros.backend.repository.AssessoriaRepository assessoriaRepository;
    @Mock private br.com.menthoros.backend.repository.UsuarioRepository usuarioRepository;
    @Mock private br.com.menthoros.backend.services.KeycloakOrganizationGateway keycloak;
    @Mock private EmailSender emailSender;
    @Mock private EmailTemplateRenderer templates;
    @Mock private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private AthleteInviteServiceImpl service;

    private UUID tenantId;
    private UUID atletaId;
    private Atleta atleta;

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId);

        atletaId = UUID.randomUUID();
        Assessoria assessoria = new Assessoria();
        assessoria.setId(tenantId);
        assessoria.setNome("Assessoria Alfa");
        atleta = new Atleta();
        atleta.setId(atletaId);
        atleta.setNome("Ana");
        atleta.setEmail("ana@teste.com");
        atleta.setAssessoria(assessoria);

        service = new AthleteInviteServiceImpl(
                atletaRepository, inviteRepository, assessoriaRepository, usuarioRepository,
                keycloak, emailSender, templates, transactionTemplate, CLOCK,
                "https://app.menthoros.com/", 7);

        lenient().when(templates.render(anyString(), anyMap())).thenReturn("corpo");
        lenient().when(inviteRepository.save(any(AthleteInvite.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // A assessoria vem do repositório, nunca de atleta.getAssessoria() — proxy LAZY fora de
        // sessão estourava no serviço sem transação (bug do ensaio de 2026-09-05).
        lenient().when(assessoriaRepository.findById(tenantId))
                .thenReturn(java.util.Optional.of(atleta.getAssessoria()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("invite")
    class Invite {

        @Test
        @DisplayName("emite convite: persiste hash (nunca o token cru), envia e-mail e marca sentAt")
        void caminhoFeliz() {
            when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
            when(inviteRepository.findOpenByAtletaId(atletaId)).thenReturn(Optional.empty());

            service.invite(atletaId);

            ArgumentCaptor<AthleteInvite> salvo = ArgumentCaptor.forClass(AthleteInvite.class);
            verify(inviteRepository, org.mockito.Mockito.times(2)).save(salvo.capture());
            AthleteInvite convite = salvo.getAllValues().get(0);
            assertThat(convite.getAtletaId()).isEqualTo(atletaId);
            assertThat(convite.getTenantId()).isEqualTo(tenantId);
            assertThat(convite.getTokenHash()).hasSize(64);
            assertThat(convite.getEmailEnviado()).isEqualTo("ana@teste.com");
            assertThat(convite.getExpiresAt())
                    .isEqualTo(OffsetDateTime.now(CLOCK).plusDays(7));

            ArgumentCaptor<EmailMessage> msg = ArgumentCaptor.forClass(EmailMessage.class);
            verify(emailSender).send(msg.capture());
            assertThat(msg.getValue().to()).isEqualTo("ana@teste.com");
            // o link carrega o token cru; o hash persistido não pode aparecer nele
            assertThat(salvo.getAllValues().get(1).getSentAt()).isNotNull();
        }

        @Test
        @DisplayName("o link do e-mail leva o token cru para /#/cadastro?convite=")
        void linkComTokenCru() {
            when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
            when(inviteRepository.findOpenByAtletaId(atletaId)).thenReturn(Optional.empty());

            service.invite(atletaId);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, String>> valores = ArgumentCaptor.forClass(Map.class);
            verify(templates, org.mockito.Mockito.atLeastOnce())
                    .render(eq("athlete-invite.html"), valores.capture());
            String link = valores.getValue().get("link");
            assertThat(link).startsWith("https://app.menthoros.com/#/cadastro?convite=");
            assertThat(valores.getValue().get("assessoria")).isEqualTo("Assessoria Alfa");
        }

        @Test
        @DisplayName("reenvio invalida o convite aberto anterior")
        void reenvioInvalidaAnterior() {
            AthleteInvite anterior = AthleteInvite.builder()
                    .id(UUID.randomUUID()).atletaId(atletaId).tenantId(tenantId)
                    .tokenHash("x").emailEnviado("ana@teste.com")
                    .expiresAt(OffsetDateTime.now(CLOCK).plusDays(1)).build();
            when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
            when(inviteRepository.findOpenByAtletaId(atletaId)).thenReturn(Optional.of(anterior));

            service.invite(atletaId);

            assertThat(anterior.getInvalidatedAt()).isEqualTo(OffsetDateTime.now(CLOCK));
        }

        @Test
        @DisplayName("atleta sem e-mail não pode ser convidado")
        void atletaSemEmail() {
            atleta.setEmail(null);
            when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));

            assertThatThrownBy(() -> service.invite(atletaId))
                    .isInstanceOf(DomainRuleViolationException.class);
            verify(emailSender, never()).send(any());
        }

        @Test
        @DisplayName("atleta de outro tenant (ou inexistente) é not-found")
        void atletaDeOutroTenant() {
            when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.invite(atletaId))
                    .isInstanceOf(DomainNotFoundException.class);
        }

        @Test
        @DisplayName("corrida de duas emissões: a UNIQUE parcial decide e o perdedor recebe 409")
        void corridaDeEmissao() {
            when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
            when(inviteRepository.findOpenByAtletaId(atletaId)).thenReturn(Optional.empty());
            when(inviteRepository.save(any(AthleteInvite.class)))
                    .thenThrow(new DataIntegrityViolationException("uk_athlete_invite_aberto_por_atleta"));

            assertThatThrownBy(() -> service.invite(atletaId))
                    .isInstanceOf(DomainConflictException.class);
            verify(emailSender, never()).send(any());
        }

        @Test
        @DisplayName("falha de SMTP propaga e o convite fica sem sentAt")
        void falhaDeSmtp() {
            when(atletaRepository.findByIdAndTenantId(atletaId, tenantId)).thenReturn(Optional.of(atleta));
            when(inviteRepository.findOpenByAtletaId(atletaId)).thenReturn(Optional.empty());
            org.mockito.Mockito.doThrow(new EmailDeliveryException("smtp fora", null))
                    .when(emailSender).send(any());

            assertThatThrownBy(() -> service.invite(atletaId))
                    .isInstanceOf(EmailDeliveryException.class);

            ArgumentCaptor<AthleteInvite> salvo = ArgumentCaptor.forClass(AthleteInvite.class);
            verify(inviteRepository).save(salvo.capture());
            assertThat(salvo.getValue().getSentAt()).isNull();
        }
    }
}
