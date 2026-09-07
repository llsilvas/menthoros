-- =====================================================================
-- V91: tb_athlete_invite — convite de atleta por token do backend
-- =====================================================================
--
-- Substitui o invite-user do Keycloak Organizations como canal do convite de atleta
-- (change add-athlete-invite-token-link). O vínculo Usuario<->Atleta passa a ser
-- determinístico pelo token — o match por e-mail no primeiro login vira fallback.
-- claimed_at é o claim atômico do aceite público: quem o grava primeiro provisiona;
-- a compensação o zera em caso de falha, reabrindo o retry.

CREATE TABLE IF NOT EXISTS tb_athlete_invite (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    atleta_id       UUID NOT NULL REFERENCES tb_atleta(id) ON DELETE CASCADE,
    tenant_id       UUID NOT NULL,
    token_hash      VARCHAR(64) NOT NULL,
    email_enviado   VARCHAR(180) NOT NULL,
    claimed_at      TIMESTAMPTZ,
    sent_at         TIMESTAMPTZ,
    invalidated_at  TIMESTAMPTZ,
    accepted_at     TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_athlete_invite_token_hash UNIQUE (token_hash)
);

CREATE INDEX IF NOT EXISTS idx_athlete_invite_tenant_atleta
    ON tb_athlete_invite(tenant_id, atleta_id);

-- No máximo um convite aberto por atleta: a UNIQUE parcial decide a corrida de duas
-- emissões simultâneas (mesmo mecanismo do tb_founding_invite por waitlist).
CREATE UNIQUE INDEX IF NOT EXISTS uk_athlete_invite_aberto_por_atleta
    ON tb_athlete_invite(atleta_id)
    WHERE invalidated_at IS NULL AND accepted_at IS NULL;

DO $$
BEGIN
    RAISE NOTICE '✅ V91 - tb_athlete_invite criada com sucesso';
END$$;
