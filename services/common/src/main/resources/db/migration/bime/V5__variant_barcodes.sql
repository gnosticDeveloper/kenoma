-- Barcodes for product variants. Two ways a variant gets one: link an existing manufacturer
-- ("provider") barcode scanned off received goods, or issue an internal one. A scan at point of
-- sale resolves a barcode string to exactly one variant within the org, so (org_id, barcode) is
-- unique. A variant can carry several barcodes (a single unit and a case each have their own
-- GTIN in real GS1 practice); at most one is marked primary.

CREATE TABLE variant_barcodes (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     uuid NOT NULL,
    variant_id uuid NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    barcode    varchar(64)  NOT NULL,
    -- EAN13 | UPC_A | EAN8 | CODE128 | CODE39. Validated in application code, not constrained here,
    -- matching how stock_movements.movement_type is handled.
    symbology  varchar(20)  NOT NULL,
    -- PROVIDER = scanned off received goods; ISSUED = minted by this system.
    source     varchar(10)  NOT NULL DEFAULT 'PROVIDER',
    is_primary boolean      NOT NULL DEFAULT false,
    created_at timestamp    NOT NULL DEFAULT current_timestamp,
    UNIQUE (org_id, barcode)
);
CREATE INDEX idx_variant_barcodes_variant ON variant_barcodes(variant_id);
CREATE UNIQUE INDEX uq_variant_barcodes_primary ON variant_barcodes(variant_id) WHERE is_primary;

ALTER TABLE variant_barcodes ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON variant_barcodes
    USING (org_id = current_setting('app.org_id', true)::uuid);

-- Per-org barcode issuance config. gs1_prefix is the org's real GS1 company prefix if it has one;
-- when null, issuance falls back to the GS1 restricted-distribution range (prefix "20"), which is
-- reserved for in-store use and valid only within the org's own systems. next_sequence is the
-- running item-reference counter consumed by each issue call.
CREATE TABLE org_barcode_settings (
    org_id        uuid PRIMARY KEY,
    gs1_prefix    varchar(12),
    next_sequence bigint    NOT NULL DEFAULT 1,
    created_at    timestamp NOT NULL DEFAULT current_timestamp,
    modified_at   timestamp NOT NULL DEFAULT current_timestamp
);

ALTER TABLE org_barcode_settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON org_barcode_settings
    USING (org_id = current_setting('app.org_id', true)::uuid);
