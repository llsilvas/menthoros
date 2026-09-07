package br.com.menthoros.backend.services;

import java.util.Optional;
import java.util.UUID;

/**
 * Abstração da integração com o Keycloak para Organizations, usuários e convites.
 * O service de domínio depende desta interface (mockável em testes);
 * o adapter real usa o Keycloak Admin Client (infra).
 *
 * <p>As operações são <strong>primitivas</strong>: cada uma faz uma chamada e nada mais. A ordem
 * entre elas, e a compensação quando uma falha, são responsabilidade do orquestrador — não deste
 * gateway. Isso é o que permite ao orquestrador desfazer em ordem inversa.</p>
 */
public interface KeycloakOrganizationGateway {

    /**
     * Cria uma Organization no realm para a assessoria e injeta o atributo tenant_id.
     * @return o id da Organization criada no Keycloak
     */
    String criarOrganization(String nome, String dominio, UUID tenantId);

    /**
     * Busca o id do usuário pelo e-mail, com correspondência exata.
     * @return o id, ou vazio quando não existe usuário com esse e-mail
     */
    Optional<String> buscarUsuarioIdPorEmail(String email);

    /**
     * Cria um usuário no realm, com senha definitiva.
     * @return o id do usuário criado
     */
    String criarUsuario(NovoUsuarioKeycloak dados);

    /**
     * Habilita ou desabilita um usuário existente.
     */
    void definirHabilitado(String usuarioId, boolean habilitado);

    /**
     * Atribui uma role de realm (ex.: {@code TECNICO}) ao usuário.
     */
    void atribuirRoleDeRealm(String usuarioId, String role);

    /**
     * Vincula um usuário existente a uma Organization.
     */
    void adicionarMembroNaOrganization(String organizationId, String usuarioId);

    /**
     * Dispara o e-mail nativo de verificação do Keycloak.
     *
     * <p><strong>Exige usuário habilitado.</strong> Verificado contra o Keycloak 26.7 em
     * 2026-08-09: para usuário desabilitado a API responde
     * {@code 400 {"errorMessage":"User is disabled"}} e nenhum e-mail sai.</p>
     */
    void enviarVerificacaoDeEmail(String usuarioId);

    /**
     * Remove o usuário. Usado na compensação — tolera usuário inexistente.
     */
    void removerUsuario(String usuarioId);

    /**
     * Remove a Organization. Usado na compensação — tolera Organization inexistente.
     */
    void removerOrganization(String organizationId);
}
