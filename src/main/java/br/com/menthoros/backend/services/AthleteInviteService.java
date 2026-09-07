package br.com.menthoros.backend.services;

import java.util.UUID;

/**
 * Convite de acesso do atleta por token do backend (change add-athlete-invite-token-link).
 * Substitui o invite-user do Keycloak Organizations como canal do convite.
 */
public interface AthleteInviteService {

    /**
     * Gera (ou regenera) o convite do atleta e envia o e-mail pelo carteiro próprio.
     * Reenvio invalida o convite aberto anterior.
     *
     * <p><strong>Idempotent:</strong> NO — cada chamada gera token novo e envia outro e-mail.
     * <p><strong>Side Effects:</strong> Database insert/update + External API (SMTP).
     * <p><strong>Tenant-aware:</strong> YES — resolve o atleta no tenant do contexto.
     */
    void invite(UUID atletaId);

    /**
     * Dados públicos de um convite ativo, para a página de cadastro.
     *
     * <p><strong>Idempotent:</strong> YES — leitura.
     * <p><strong>Side Effects:</strong> NONE.
     * <p><strong>Tenant-aware:</strong> NO — rota pública; o token é o segredo.
     *
     * @throws br.com.menthoros.backend.exception.DomainNotFoundException para qualquer token que
     *         não esteja ativo, sem distinguir o motivo (estado do convite não é público)
     */
    br.com.menthoros.backend.dto.output.AthleteInviteLookupOutputDto lookup(String rawToken);

    /**
     * Aceita o convite: provisiona a conta (Keycloak + role ATLETA + Organization) e efetiva o
     * vínculo {@code atleta.usuario}. Claim atômico decide corrida de dois aceites; falha em passo
     * intermediário compensa os anteriores e reabre o convite para retry.
     *
     * <p><strong>Idempotent:</strong> NO — consome o convite; retry só após falha compensada.
     * <p><strong>Side Effects:</strong> External API (Keycloak) + Database insert/update.
     * <p><strong>Tenant-aware:</strong> NO contexto — o tenant vem do próprio convite (rota
     * pública, sem JWT).
     */
    void aceitar(br.com.menthoros.backend.dto.input.AthleteInviteAcceptInputDto input);
}
