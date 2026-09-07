package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.dto.input.AthleteInviteAcceptInputDto;
import br.com.menthoros.backend.dto.output.AthleteInviteLookupOutputDto;
import br.com.menthoros.backend.entity.Assessoria;
import br.com.menthoros.backend.entity.AthleteInvite;
import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.entity.Usuario;
import br.com.menthoros.backend.enums.UserRole;
import br.com.menthoros.backend.exception.DomainConflictException;
import br.com.menthoros.backend.exception.DomainGoneException;
import br.com.menthoros.backend.exception.DomainNotFoundException;
import br.com.menthoros.backend.exception.DomainRuleViolationException;
import br.com.menthoros.backend.multitenancy.TenantContext;
import br.com.menthoros.backend.repository.AssessoriaRepository;
import br.com.menthoros.backend.repository.AthleteInviteRepository;
import br.com.menthoros.backend.repository.AtletaRepository;
import br.com.menthoros.backend.repository.UsuarioRepository;
import br.com.menthoros.backend.security.InviteToken;
import br.com.menthoros.backend.services.AthleteInviteService;
import br.com.menthoros.backend.services.KeycloakOrganizationGateway;
import br.com.menthoros.backend.services.NovoUsuarioKeycloak;
import br.com.menthoros.backend.services.email.EmailMessage;
import br.com.menthoros.backend.services.email.EmailSender;
import br.com.menthoros.backend.services.email.EmailTemplateRenderer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Emissão do convite de atleta por token do backend.
 *
 * <p><strong>Deliberadamente SEM {@code @Transactional}</strong> em {@link #invite}: o envio do
 * e-mail é chamada externa e não pode segurar transação (mesma decisão do
 * {@link FoundingInviteServiceImpl}). A ordem das escritas torna a falha parcial inofensiva:
 * invalidar o anterior e só então inserir; se o e-mail falhar, o convite fica sem {@code sentAt}
 * e o próximo reenvio o invalida.</p>
 *
 * <p><strong>Duplicação conhecida e aceita</strong> com {@link FoundingInviteServiceImpl} (emissão,
 * invalidação do anterior, lookup): dois usos não justificam a abstração, e o aceite diverge
 * demais (só este provisiona conta). Extrair um template de emissão quando surgir o terceiro tipo
 * de convite — e aplicar bug-fix de emissão SEMPRE nos dois arquivos até lá.</p>
 */
@Slf4j
@Service
public class AthleteInviteServiceImpl implements AthleteInviteService {

    static final String SUBJECT = "Seu treinador te convidou para o Menthoros";
    static final String INVITE_PATH = "/#/cadastro?convite=";

    static final String ROLE_ATLETA = "ATLETA";

    private final AtletaRepository atletaRepository;
    private final AthleteInviteRepository inviteRepository;
    private final AssessoriaRepository assessoriaRepository;
    private final UsuarioRepository usuarioRepository;
    private final KeycloakOrganizationGateway keycloak;
    private final EmailSender emailSender;
    private final EmailTemplateRenderer templates;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final String frontendUrl;
    private final int validityDays;

    public AthleteInviteServiceImpl(
            AtletaRepository atletaRepository,
            AthleteInviteRepository inviteRepository,
            AssessoriaRepository assessoriaRepository,
            UsuarioRepository usuarioRepository,
            KeycloakOrganizationGateway keycloak,
            EmailSender emailSender,
            EmailTemplateRenderer templates,
            TransactionTemplate transactionTemplate,
            Clock clock,
            @Value("${app.frontend.url}") String frontendUrl,
            @Value("${app.athlete-invite.validity-days:7}") int validityDays) {
        this.atletaRepository = atletaRepository;
        this.inviteRepository = inviteRepository;
        this.assessoriaRepository = assessoriaRepository;
        this.usuarioRepository = usuarioRepository;
        this.keycloak = keycloak;
        this.emailSender = emailSender;
        this.templates = templates;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.frontendUrl = frontendUrl.endsWith("/")
                ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
        this.validityDays = validityDays;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void invite(UUID atletaId) {
        UUID tenantId = TenantContext.getRequiredTenantId();
        log.info("Emitindo convite de atleta: atletaId={}, tenantId={}", atletaId, tenantId);

        Atleta atleta = atletaRepository.findByIdAndTenantId(atletaId, tenantId)
                .orElseThrow(() -> new DomainNotFoundException("Atleta não encontrado: " + atletaId));
        if (atleta.getEmail() == null || atleta.getEmail().isBlank()) {
            throw new DomainRuleViolationException("Atleta sem email não pode ser convidado");
        }
        // Pelo repositório, NUNCA por atleta.getAssessoria(): o método roda sem transação (e-mail
        // fora de TX, ver JavaDoc da classe) e o proxy LAZY estouraria fora de sessão — 500 real
        // pego no ensaio do runbook em 2026-09-05, no primeiro convite emitido em develop.
        String nomeAssessoria = assessoriaRepository.findById(tenantId)
                .orElseThrow(() -> new DomainNotFoundException("Assessoria não encontrada: " + tenantId))
                .getNome();

        OffsetDateTime now = OffsetDateTime.now(clock);
        inviteRepository.findOpenByAtletaId(atletaId).ifPresent(anterior -> {
            anterior.setInvalidatedAt(now);
            inviteRepository.save(anterior);
            log.info("Convite de atleta anterior invalidado: inviteId={}", anterior.getId());
        });

        InviteToken token = InviteToken.generate();
        AthleteInvite convite;
        try {
            convite = inviteRepository.save(AthleteInvite.builder()
                    .atletaId(atletaId)
                    .tenantId(tenantId)
                    .tokenHash(token.hash())
                    .emailEnviado(atleta.getEmail())
                    .expiresAt(now.plusDays(validityDays))
                    .build());
        } catch (DataIntegrityViolationException corrida) {
            // Duas emissões simultâneas: entre o findOpen e o insert não há lock, e a UNIQUE
            // parcial decidiu. Quem perdeu recebe 409; nenhum e-mail saiu. Só funciona porque a
            // classe não é @Transactional (ver JavaDoc da classe).
            throw new DomainConflictException("Já existe um convite sendo emitido para este atleta; tente de novo");
        }

        // Fora de transação e depois do insert: se o SMTP recusar, o convite existe sem sentAt e a
        // exceção sobe como 502 — o coach reenvia, e o reenvio invalida este.
        emailSender.send(mensagem(atleta, nomeAssessoria, token));

        convite.setSentAt(OffsetDateTime.now(clock));
        inviteRepository.save(convite);

        log.info("Convite de atleta enviado: inviteId={}, atletaId={}, expiresAt={}",
                convite.getId(), atletaId, convite.getExpiresAt());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AthleteInviteLookupOutputDto lookup(String rawToken) {
        AthleteInvite convite = findActive(rawToken)
                .orElseThrow(() -> new DomainNotFoundException("Convite inválido ou expirado"));
        Atleta atleta = atletaRepository.findByIdAndTenantId(convite.getAtletaId(), convite.getTenantId())
                .orElseThrow(() -> new DomainNotFoundException("Convite inválido ou expirado"));
        Assessoria assessoria = assessoriaRepository.findById(convite.getTenantId())
                .orElseThrow(() -> new DomainNotFoundException("Convite inválido ou expirado"));
        return new AthleteInviteLookupOutputDto(
                atleta.getNome(), assessoria.getNome(), convite.getEmailEnviado());
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>Deliberadamente SEM {@code @Transactional}:</strong> os passos 2–4 são chamadas
     * externas (Keycloak) e não podem segurar conexão; a escrita local roda numa transação curta
     * via {@code TransactionTemplate} no final. O claim atômico (passo 1) é a exclusão mútua.</p>
     */
    @Override
    public void aceitar(AthleteInviteAcceptInputDto input) {
        AthleteInvite convite = conviteAtivoPara(input.token());

        // TODAS as validações rodam ANTES do claim atômico, de propósito (achado convergente do
        // QA): são leituras puras que não competem pela corrida de aceite, e qualquer rejeição
        // aqui não pode deixar o convite reivindicado — um claim vazado responderia 410 para
        // sempre num erro que é 404/409/422 e recuperável.
        Atleta atleta = atletaRepository.findByIdAndTenantId(convite.getAtletaId(), convite.getTenantId())
                .orElseThrow(() -> new DomainNotFoundException("Convite inválido ou expirado"));
        if (atleta.getUsuario() != null) {
            throw new DomainConflictException("Este atleta já está vinculado a uma conta; fale com o seu treinador");
        }
        Assessoria assessoria = assessoriaRepository.findById(convite.getTenantId())
                .orElseThrow(() -> new DomainNotFoundException("Convite inválido ou expirado"));
        String orgId = assessoria.getKeycloakOrganizationId();
        if (orgId == null || orgId.isBlank()) {
            throw new DomainRuleViolationException(
                    "Assessoria sem keycloakOrganizationId — execute o onboarding da assessoria primeiro");
        }

        String emailEscolhido = (input.email() == null || input.email().isBlank())
                ? convite.getEmailEnviado() : input.email().trim();
        // O token provou a posse do e-mail do convite (mesmo racional das fundadoras, risco aceito
        // no proposal); e-mail trocado nasce não-verificado e recebe o e-mail de verificação.
        boolean emailVerificado = emailEscolhido.equalsIgnoreCase(convite.getEmailEnviado());

        if (keycloak.buscarUsuarioIdPorEmail(emailEscolhido).isPresent()) {
            throw new DomainConflictException("Este e-mail já possui conta");
        }

        // Só agora o claim: quem perde a corrida de dois aceites simultâneos recebe 410.
        if (inviteRepository.claim(convite.getId(), OffsetDateTime.now(clock)) == 0) {
            throw new DomainGoneException("Este convite não é mais válido; peça um novo ao seu treinador");
        }

        // Compensação em pilha (padrão do coach signup): falha desfaz os passos externos na ordem
        // inversa e reabre o claim — o token continua válido para retry.
        Deque<Runnable> desfazer = new ArrayDeque<>();
        try {
            String usuarioKeycloakId = keycloak.criarUsuario(new NovoUsuarioKeycloak(
                    emailEscolhido, input.nome(), input.senha(), true, List.of(), emailVerificado));
            desfazer.push(() -> keycloak.removerUsuario(usuarioKeycloakId));

            keycloak.atribuirRoleDeRealm(usuarioKeycloakId, ROLE_ATLETA);
            // Adicionar membro já existente é no-op no Keycloak — o retry pós-falha é idempotente.
            keycloak.adicionarMembroNaOrganization(orgId, usuarioKeycloakId);

            if (!emailVerificado) {
                keycloak.enviarVerificacaoDeEmail(usuarioKeycloakId);
            }

            OffsetDateTime now = OffsetDateTime.now(clock);
            transactionTemplate.executeWithoutResult(tx -> {
                Usuario usuario = usuarioRepository.save(Usuario.builder()
                        .id(UUID.fromString(usuarioKeycloakId))
                        .keycloakId(usuarioKeycloakId)
                        .assessoria(assessoria)
                        .email(emailEscolhido)
                        .nome(input.nome())
                        .role(UserRole.ATLETA)
                        .ativo(true)
                        .emailVerificado(emailVerificado)
                        .build());
                Atleta gerenciado = atletaRepository.findByIdAndTenantId(
                                convite.getAtletaId(), convite.getTenantId())
                        .orElseThrow(() -> new DomainNotFoundException("Convite inválido ou expirado"));
                gerenciado.setUsuario(usuario);
                atletaRepository.save(gerenciado);
                convite.setAcceptedAt(now);
                inviteRepository.save(convite);
            });

            log.info("Convite de atleta aceito: inviteId={}, atletaId={}, tenantId={}, emailVerificado={}",
                    convite.getId(), convite.getAtletaId(), convite.getTenantId(), emailVerificado);
        } catch (RuntimeException falha) {
            compensar(desfazer, convite.getId());
            throw falha;
        }
    }

    /** Resolve o convite ativo do token — 404 para desconhecido, 410 para não-ativo. SEM claim. */
    private AthleteInvite conviteAtivoPara(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new DomainNotFoundException("Convite inválido ou expirado");
        }
        AthleteInvite convite = inviteRepository.findByTokenHash(InviteToken.hashOf(rawToken))
                .orElseThrow(() -> new DomainNotFoundException("Convite inválido ou expirado"));
        if (!convite.isActive(OffsetDateTime.now(clock))) {
            throw new DomainGoneException("Este convite não é mais válido; peça um novo ao seu treinador");
        }
        return convite;
    }

    /**
     * Desfaz na ordem inversa e reabre o claim. Se a própria compensação falhar, registra e segue —
     * insistir num Keycloak que acabou de falhar trocaria um recurso órfão logado por um loop
     * dentro do request do atleta (mesma decisão do coach signup).
     */
    private void compensar(Deque<Runnable> desfazer, UUID inviteId) {
        while (!desfazer.isEmpty()) {
            try {
                desfazer.pop().run();
            } catch (RuntimeException e) {
                log.error("Compensação do aceite de convite falhou: inviteId={}", inviteId, e);
            }
        }
        try {
            inviteRepository.liberarClaim(inviteId);
        } catch (RuntimeException e) {
            log.error("Não foi possível reabrir o claim do convite: inviteId={}", inviteId, e);
        }
    }

    private Optional<AthleteInvite> findActive(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        return inviteRepository.findByTokenHash(InviteToken.hashOf(rawToken))
                .filter(c -> c.isActive(now));
    }

    private EmailMessage mensagem(Atleta atleta, String nomeAssessoria, InviteToken token) {
        Map<String, String> valores = Map.of(
                "nome", atleta.getNome(),
                "assessoria", nomeAssessoria,
                "link", frontendUrl + INVITE_PATH + token.value(),
                "validade", validityDays + (validityDays == 1 ? " dia" : " dias"));
        return new EmailMessage(atleta.getEmail(), SUBJECT,
                templates.render("athlete-invite.html", valores),
                templates.render("athlete-invite.txt", valores));
    }
}
