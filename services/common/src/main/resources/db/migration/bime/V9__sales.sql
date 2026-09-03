-- Point-of-sale sales. A sale rings up one or more variants scanned at a single location and
-- depletes stock through the existing stock-movements ledger (a new SALE movement type, one row
-- per variant, or one row per batch when the variant is batch-tracked and FEFO-allocated).
--
-- stock_movements has no money on it, so the priced document lives here: sales + sale_lines hold
-- the unit prices and totals as they stood at the moment of sale. The stock side is linked back
-- by stock_movements.reference_id = sales.id, exactly like transfer orders.
--
-- Out of scope for this migration (deferred): business billing / customer invoicing, tax,
-- discounts, tender and change capture, returns/refunds, receipt generation.

-- 1) The sale document. status is COMPLETED on insert - a sale is recorded after the goods and
-- payment have changed hands. VOIDED is reserved for a later returns/void feature; validated in
-- application code like movement_type / transfer status. subtotal is the sum of line totals,
-- a currency-less scalar; currency is informational (taken from the variants' price currency).
CREATE TABLE sales (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      uuid          NOT NULL,
    location_id uuid          NOT NULL REFERENCES locations(id),
    reference   varchar(50),
    status      varchar(20)   NOT NULL DEFAULT 'COMPLETED',
    subtotal    numeric(14,2) NOT NULL,
    currency    varchar(3),
    note        varchar(500),
    sold_at     timestamp     NOT NULL DEFAULT current_timestamp,
    sold_by     uuid,
    voided_at   timestamp,
    voided_by   uuid
);
CREATE INDEX idx_sales_org_sold_at ON sales(org_id, sold_at DESC);
CREATE INDEX idx_sales_org_location ON sales(org_id, location_id);

ALTER TABLE sales ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sales
    USING (org_id = current_setting('app.org_id', true)::uuid);

-- 2) Sale lines. One row per scanned variant. qty_base is always normalized to the variant's
-- base unit (what the ledger moves); uom / uom_quantity keep the scanned pack unit verbatim,
-- same pattern as stock_movements / stock_transfer_lines. unit_price is the price of one
-- uom_quantity unit at the time of sale (a till-side override, or the variant's effective price
-- for that unit); line_total is unit_price * uom_quantity. barcode is the scanned code, kept for
-- the receipt / audit trail.
CREATE TABLE sale_lines (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sale_id      uuid          NOT NULL REFERENCES sales(id) ON DELETE CASCADE,
    org_id       uuid          NOT NULL,
    variant_id   uuid          NOT NULL REFERENCES product_variants(id),
    barcode      varchar(64),
    qty_base     numeric(14,3) NOT NULL CHECK (qty_base > 0),
    uom          varchar(50),
    uom_quantity numeric(14,4),
    unit_price   numeric(12,2) NOT NULL,
    line_total   numeric(14,2) NOT NULL,
    created_at   timestamp     NOT NULL DEFAULT current_timestamp
);
CREATE INDEX idx_sale_lines_sale ON sale_lines(sale_id);
CREATE INDEX idx_sale_lines_org_variant ON sale_lines(org_id, variant_id);

ALTER TABLE sale_lines ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sale_lines
    USING (org_id = current_setting('app.org_id', true)::uuid);
