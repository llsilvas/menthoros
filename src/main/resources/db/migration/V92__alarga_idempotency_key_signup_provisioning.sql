-- =====================================================================
-- V92: idempotency_key de 64 para 100 chars em tb_signup_provisioning
-- =====================================================================
--
-- A chave do modo convite é "<token_hash>:<n>" — 64 chars de SHA-256 hex + sufixo = 66+,
-- que NUNCA coube no varchar(64): todo aceite de convite de fundadora estourava o insert
-- (DataIntegrityViolation -> 409 com mensagem enganosa de slug em uso). Achado no ensaio
-- do runbook de validação em 2026-09-05, no primeiro aceite real contra develop.
-- 100 dá folga para o sufixo crescer; a chave do signup público é UUID (36), não muda.

ALTER TABLE tb_signup_provisioning ALTER COLUMN idempotency_key TYPE VARCHAR(100);

DO $$
BEGIN
    RAISE NOTICE '✅ V92 - idempotency_key alargada para 100';
END$$;
