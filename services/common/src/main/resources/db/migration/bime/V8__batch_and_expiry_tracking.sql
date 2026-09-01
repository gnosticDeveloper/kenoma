-- Batch and perishable support. A batch-tracked product's stock is broken down by the production
-- batch (lot) it came from, so it can be consumed oldest-expiry-first (FEFO), warned about before
-- expiry, and pulled in a recall. Opt-in per product via products.tracks_batches - a product that
-- doesn't opt in keeps a single per-(variant, location) balance and is untouched by any of this.
--
-- variant_stock_balances stays the source of truth for total on-hand. stock_batch_balances is an
-- additional per-batch breakdown maintained in lockstep, only for batch-tracked variants.

-- 1) Opt-in flag.
ALTER TABLE products ADD COLUMN tracks_batches boolean NOT NULL DEFAULT false;

-- 2) The batch (lot). A batch belongs to one variant; its code is whatever the producer stamped
-- on the goods (GS1 AI 10), unique per variant within the org. expiry_date is nullable - not every
-- batch-tracked good carries a date. status walks ACTIVE -> RECALLED (and back, via lift-recall);
-- validated in application code, like movement_type / transfer status.
CREATE TABLE stock_batches (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       uuid NOT NULL,
    variant_id   uuid NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    batch_code   varchar(64) NOT NULL,
    expiry_date  date,
    status       varchar(20) NOT NULL DEFAULT 'ACTIVE',
    recalled_at  timestamp,
    recalled_by  uuid,
    recall_note  varchar(500),
    created_at   timestamp NOT NULL DEFAULT current_timestamp,
    UNIQUE (org_id, variant_id, batch_code)
);
CREATE INDEX idx_stock_batches_org_variant ON stock_batches(org_id, variant_id);
CREATE INDEX idx_stock_batches_org_expiry ON stock_batches(org_id, expiry_date);

ALTER TABLE stock_batches ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON stock_batches
    USING (org_id = current_setting('app.org_id', true)::uuid);

-- 3) Per-batch, per-location on-hand. Same shape and CHECK (quantity >= 0) as
-- variant_stock_balances; the ledger applies every batch-tracked movement to both.
CREATE TABLE stock_batch_balances (
    org_id      uuid NOT NULL,
    variant_id  uuid NOT NULL REFERENCES product_variants(id),
    location_id uuid NOT NULL REFERENCES locations(id),
    batch_id    uuid NOT NULL REFERENCES stock_batches(id) ON DELETE CASCADE,
    quantity    numeric(14,3) NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    modified_at timestamp NOT NULL DEFAULT current_timestamp,
    PRIMARY KEY (org_id, batch_id, location_id)
);
CREATE INDEX idx_stock_batch_balances_variant_location
    ON stock_batch_balances(org_id, variant_id, location_id);

ALTER TABLE stock_batch_balances ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON stock_batch_balances
    USING (org_id = current_setting('app.org_id', true)::uuid);

-- 4) Which batch a movement drew from / added to. Always null for non-batch-tracked variants.
-- A FEFO outbound that spans several batches writes one movement row per batch, each with its
-- own batch_id.
ALTER TABLE stock_movements ADD COLUMN batch_id uuid REFERENCES stock_batches(id);
CREATE INDEX idx_stock_movements_batch ON stock_movements(batch_id) WHERE batch_id IS NOT NULL;

-- 5) Per-org near-expiry window for the daily alert sweep. Parallels org_barcode_settings.
CREATE TABLE org_batch_settings (
    org_id          uuid PRIMARY KEY,
    near_expiry_days int NOT NULL DEFAULT 30 CHECK (near_expiry_days > 0),
    created_at      timestamp NOT NULL DEFAULT current_timestamp,
    modified_at     timestamp NOT NULL DEFAULT current_timestamp
);

ALTER TABLE org_batch_settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON org_batch_settings
    USING (org_id = current_setting('app.org_id', true)::uuid);

-- 6) Active near-expiry alerts. Same "row exists only while breached, emailed once on first
-- detection" pattern as variant_stock_alerts: inserted when a batch with stock first falls inside
-- the window, deleted once it is recalled / consumed to zero / the window no longer covers it.
CREATE TABLE batch_expiry_alerts (
    org_id       uuid NOT NULL,
    batch_id     uuid NOT NULL REFERENCES stock_batches(id) ON DELETE CASCADE,
    location_id  uuid NOT NULL REFERENCES locations(id),
    expiry_date  date NOT NULL,
    quantity     numeric(14,3) NOT NULL,
    triggered_at timestamp NOT NULL DEFAULT current_timestamp,
    PRIMARY KEY (org_id, batch_id, location_id)
);

ALTER TABLE batch_expiry_alerts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON batch_expiry_alerts
    USING (org_id = current_setting('app.org_id', true)::uuid);
