-- =====================================================================
-- V93: Adiciona colunas de atribuição UTM à tb_waitlist
-- Aditivo e opcional — nenhum cliente existente quebra (add-waitlist-utm-attribution).
-- =====================================================================

ALTER TABLE tb_waitlist
    ADD COLUMN IF NOT EXISTS utm_source   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS utm_medium   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS utm_campaign VARCHAR(255),
    ADD COLUMN IF NOT EXISTS utm_content  VARCHAR(255);

COMMENT ON COLUMN tb_waitlist.utm_source IS
    'Parâmetro utm_source da URL de origem do signup (ex.: instagram) — opcional';
COMMENT ON COLUMN tb_waitlist.utm_medium IS
    'Parâmetro utm_medium da URL de origem do signup (ex.: social) — opcional';
COMMENT ON COLUMN tb_waitlist.utm_campaign IS
    'Parâmetro utm_campaign da URL de origem do signup (ex.: turma-fundadora) — opcional';
COMMENT ON COLUMN tb_waitlist.utm_content IS
    'Parâmetro utm_content da URL de origem do signup — opcional';

DO $$
BEGIN
    RAISE NOTICE '✅ V93 - colunas utm_* adicionadas a tb_waitlist';
END$$;
