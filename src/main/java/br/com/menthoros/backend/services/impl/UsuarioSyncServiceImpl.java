package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.services.UsuarioSyncService;
import br.com.menthoros.backend.entity.Assessoria;
import br.com.menthoros.backend.entity.Usuario;
import br.com.menthoros.backend.enums.UserRole;
import br.com.menthoros.backend.repository.AssessoriaRepository;
import br.com.menthoros.backend.repository.AtletaRepository;
import br.com.menthoros.backend.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Service responsável por sincronizar usuários do Keycloak com o banco local (tb_usuario)
 *
 * IMPORTANTE: tb_usuario é um CACHE dos dados do Keycloak
 * - Não armazena senhas
 * - É atualizado automaticamente no login
 * - Fonte da verdade: Keycloak
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UsuarioSyncServiceImpl implements UsuarioSyncService {

    private final UsuarioRepository usuarioRepository;
    private final AssessoriaRepository assessoriaRepository;
    private final AtletaRepository atletaRepository;

    /**
     * Janela mínima entre registros de "último acesso". O sync roda a cada requisição; sem o
     * throttle, cada uma vira um UPDATE em tb_usuario — foi o amplificador do incidente de pool
     * de 2026-09-04 (um lock nessa linha esgotou as conexões). PT0S desliga o throttle (rollback
     * sem deploy de código).
     */
    @Value("${app.security.user-sync.access-throttle:PT5M}")
    private Duration accessThrottle = Duration.ofMinutes(5);

    /**
     * Sincroniza usuário a partir do JWT do Keycloak.
     * Chamado automaticamente pelo JwtTenantFilter em cada request.
     *
     * Idempotent: YES — dentro da janela de throttle, chamadas repetidas com o mesmo JWT são
     *   no-op de escrita (nenhum UPDATE); divergência de campo espelhado escreve sempre.
     * Side Effects: Database update CONDICIONAL (diff do JWT ou throttle vencido; usuário novo
     *   sempre insere) + possível vínculo de Atleta órfão. NUNCA assuma que toda chamada grava
     *   ultimoAcesso.
     * Tenant-aware: YES — tenantId usado na criação do usuário e no vínculo de atleta.
     *
     * Retorna SEMPRE o Usuario resolvido, mesmo no caminho no-op — o LgpdConsentInterceptor
     * depende dele via USUARIO_ATTR.
     *
     * @param jwt JWT do Keycloak com claims do usuário
     * @param tenantId UUID da assessoria (tenant)
     * @return Usuario sincronizado
     */
    @Transactional
    public Usuario syncUsuarioFromJwt(Jwt jwt, UUID tenantId) {
        String keycloakId = jwt.getSubject(); // subject (sub) do JWT
        String email = jwt.getClaimAsString("email");
        String nome = jwt.getClaimAsString("given_name");
        String sobrenome = jwt.getClaimAsString("family_name");
        Boolean emailVerificado = jwt.getClaimAsBoolean("email_verified");

        // Extrai roles do JWT (podem vir como lista ou string)
        List<String> roles = extractRoles(jwt);
        UserRole userRole = mapToUserRole(roles);

        log.debug("Sincronizando usuário: keycloakId={}, email={}, tenantId={}", keycloakId, email, tenantId);

        String nomeEfetivo = nome != null ? nome : email.split("@")[0]; // Fallback para parte do email
        boolean emailVerificadoEfetivo = emailVerificado != null ? emailVerificado : false;
        // Espelho da role PROPRIETARIO: atribuição SEMPRE, nunca só quando presente — perder a
        // role no Keycloak precisa desligar a flag no próximo acesso, senão o banco vira uma
        // segunda fonte da verdade que ninguém reconcilia.
        boolean owner = roles.contains("PROPRIETARIO");

        Usuario existente = usuarioRepository.findByKeycloakId(keycloakId).orElse(null);
        Usuario usuario = existente != null ? existente : createNewUsuario(keycloakId, tenantId);

        // O diff é calculado ANTES de qualquer setter: a entidade é gerenciada e o método é
        // @Transactional — mutar e "não chamar save()" ainda flusharia o UPDATE por dirty
        // checking. Só mutamos quando a decisão já é escrever.
        boolean divergiuDoJwt = existente == null
                || !Objects.equals(usuario.getEmail(), email)
                || !Objects.equals(usuario.getNome(), nomeEfetivo)
                || !Objects.equals(usuario.getSobrenome(), sobrenome)
                || !Objects.equals(usuario.getEmailVerificado(), emailVerificadoEfetivo)
                || usuario.getRole() != userRole
                || usuario.isOwner() != owner;
        boolean acessoVencido = existente == null
                || deveRegistrarAcesso(usuario.getUltimoAcesso());

        if (divergiuDoJwt || acessoVencido) {
            usuario.setEmail(email);
            usuario.setNome(nomeEfetivo);
            usuario.setSobrenome(sobrenome);
            usuario.setEmailVerificado(emailVerificadoEfetivo);
            usuario.setRole(userRole);
            usuario.setOwner(owner);
            usuario.registrarAcesso();
            usuario.registrarSincronizacao();

            usuario = usuarioRepository.save(usuario);

            // INFO de propósito: este é o único registro de mudança de role/owner em produção
            // (root INFO no cloud). O volume é seguro — só dispara em escrita real, que o
            // throttle já limita por usuário.
            log.info("Usuário sincronizado: id={}, email={}, role={}, tenant={}",
                    usuario.getId(), usuario.getEmail(), usuario.getRole(), tenantId);
        }

        // Fora da condição de escrita, de propósito: um atleta órfão pré-existente precisa ser
        // vinculado mesmo quando o sync não tem nada a escrever no usuário. Trade-off aceito: o
        // SELECT por e-mail roda em toda requisição de ATLETA (como já rodava antes do throttle) —
        // é leitura indexada e não segura lock; o amplificador do incidente eram as escritas.
        if (usuario.getRole() == UserRole.ATLETA) {
            vincularAtletaSeNecessario(usuario, tenantId);
        }

        return usuario;
    }

    /**
     * Idempotent: YES — leitura pura. Side Effects: NONE. Tenant-aware: N/A.
     */
    private boolean deveRegistrarAcesso(LocalDateTime ultimoAcesso) {
        return ultimoAcesso == null
                || ultimoAcesso.plus(accessThrottle).isBefore(LocalDateTime.now());
    }

    /**
     * Vincula o Atleta correspondente ao Usuario no primeiro acesso de um ATLETA.
     * O vínculo só ocorre quando há um Atleta com o mesmo email no tenant e que ainda não possui usuário.
     */
    private void vincularAtletaSeNecessario(Usuario usuario, UUID tenantId) {
        if (usuario.getEmail() == null) {
            return;
        }
        atletaRepository.findByEmailAndAssessoria_Id(usuario.getEmail(), tenantId)
                .filter(atleta -> atleta.getUsuario() == null)
                .ifPresent(atleta -> {
                    atleta.setUsuario(usuario);
                    atletaRepository.save(atleta);
                    log.info("Atleta vinculado ao usuário: atletaId={}, usuarioId={}, tenant={}",
                            atleta.getId(), usuario.getId(), tenantId);
                });
    }

    /**
     * Cria um novo usuário no banco local
     */
    private Usuario createNewUsuario(String keycloakId, UUID tenantId) {
        log.info("Criando novo usuário local: keycloakId={}, tenantId={}", keycloakId, tenantId);

        // Busca a assessoria (tenant)
        Assessoria assessoria = assessoriaRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Assessoria não encontrada: " + tenantId + ". " +
                        "Certifique-se de que o tenant_id no Keycloak Group está correto."
                ));

        // Usa o próprio keycloakId (subject) como ID do usuário
        // Isso garante consistência: subject do JWT = ID na tb_usuario
        Usuario usuario = Usuario.builder()
                .id(UUID.fromString(keycloakId)) // ID = subject do JWT
                .keycloakId(keycloakId)
                .assessoria(assessoria)
                .ativo(true)
                .build();
        return usuario;
    }

    /**
     * Extrai roles do JWT no formato padrão do Keycloak.
     *
     * Ordem de busca:
     *   1. realm_access.roles — estrutura padrão emitida pelo Keycloak
     *   2. roles              — claim flat para mappers customizados
     */
    @SuppressWarnings("unchecked")
    private List<String> extractRoles(Jwt jwt) {
        // 1. realm_access.roles (padrão Keycloak)
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null) {
            Object realmRoles = realmAccess.get("roles");
            if (realmRoles instanceof List) {
                return (List<String>) realmRoles;
            }
        }

        // 2. claim flat "roles" (mapper customizado)
        Object rolesClaim = jwt.getClaim("roles");
        if (rolesClaim instanceof List) {
            return (List<String>) rolesClaim;
        } else if (rolesClaim instanceof String) {
            return List.of((String) rolesClaim);
        }

        log.warn("JWT sem claim 'roles' ou 'realm_access.roles': {}", jwt.getSubject());
        return List.of();
    }

    /**
     * Mapeia roles do Keycloak para enum UserRole local
     * Prioridade: ADMIN > TECNICO > ATLETA > VISUALIZADOR
     */
    private UserRole mapToUserRole(List<String> roles) {
        if (roles.contains("ADMIN")) {
            return UserRole.ADMIN;
        } else if (roles.contains("TECNICO")) {
            return UserRole.TECNICO;
        } else if (roles.contains("ATLETA")) {
            return UserRole.ATLETA;
        } else if (roles.contains("VISUALIZADOR")) {
            return UserRole.VISUALIZADOR;
        } else {
            log.warn("Usuário sem role reconhecida, usando VISUALIZADOR como padrão. Roles: {}", roles);
            return UserRole.VISUALIZADOR; // Default seguro: apenas leitura
        }
    }

    /**
     * Verifica se um usuário precisa ser sincronizado
     * (última sincronização > 1 hora)
     */
    public boolean precisaSincronizar(Usuario usuario) {
        return usuario.precisaSincronizar();
    }

    /**
     * Sincroniza todos os usuários pendentes (última_sinc > 1 hora)
     * Útil para job scheduled em background
     *
     * NOTA: Requer Keycloak Admin Client para buscar dados atualizados
     */
    public void syncUsuariosPendentes() {
        List<Usuario> usuariosPendentes = usuarioRepository.findUsuariosPendenteSincronizacao();

        log.info("Sincronização em background: {} usuários pendentes", usuariosPendentes.size());

        // TODO: Implementar busca via Keycloak Admin API
        // Por enquanto, apenas registra para indicar que o job está funcionando
        for (Usuario usuario : usuariosPendentes) {
            log.debug("Usuário pendente de sincronização: id={}, email={}, ultima_sinc={}",
                    usuario.getId(), usuario.getEmail(), usuario.getUltimaSinc());
        }

        // Quando KeycloakAdminService estiver implementado:
        // 1. Para cada usuário pendente
        // 2. Buscar dados atualizados no Keycloak
        // 3. Atualizar no banco local
        // 4. Registrar sincronização
    }
}