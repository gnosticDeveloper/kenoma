-- Transfer orders: move stock between two locations as one tracked operation with a status
-- lifecycle, instead of two ad-hoc INBOUND/OUTBOUND movements a user has to remember to pair up.
-- Also introduces a generic status on every stock movement, so a movement can be recorded before
-- it takes effect (PENDING) and only later counted toward on-hand balances (POSTED), or voided
-- (CANCELLED). Plain INBOUND / OUTBOUND / ADJUSTMENT stay POSTED-on-insert exactly as before -
-- only transfer orders enforce a lifecycle.

-- 1) Movement status. Every existing row is POSTED: historically, recorded always meant applied.
-- Only POSTED movements count toward variant_stock_balances. PENDING -> POSTED applies the delta
-- at that point; PENDING -> CANCELLED never applies it. POSTED and CANCELLED are terminal.
ALTER TABLE stock_movements ADD COLUMN status varchar(20) NOT NULL DEFAULT 'POSTED';

-- reference_id finally has a real use (it links TRANSFER_OUT / TRANSFER_IN rows to their
-- stock_transfers row); index it, plus a partial index for the hot "find this transfer's
-- still-outstanding inbound leg" lookup the receive step does.
CREATE INDEX idx_stock_movements_reference ON stock_movements(reference_id);
CREATE INDEX idx_stock_movements_pending_transfer_in
    ON stock_movements(reference_id, variant_id)
    WHERE status = 'PENDING' AND movement_type = 'TRANSFER_IN';

-- 2) The transfer order document. status walks:
--   DRAFT -> PENDING_APPROVAL -> APPROVED -> IN_TRANSIT -> PARTIALLY_RECEIVED -> COMPLETED
-- with CANCELLED reachable from any pre-dispatch state. A caller holding BIME_TRANSFER_APPROVE
-- skips PENDING_APPROVAL on submit (self-approved).
CREATE TABLE stock_transfers (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        uuid        NOT NULL,
    reference     varchar(50),
    status        varchar(20) NOT NULL DEFAULT 'DRAFT',
    note          varchar(500),
    created_at    timestamp   NOT NULL DEFAULT current_timestamp,
    created_by    uuid,
    submitted_at  timestamp,
    submitted_by  uuid,
    approved_at   timestamp,
    approved_by   uuid,
    dispatched_at timestamp,
    dispatched_by uuid,
    completed_at  timestamp,
    completed_by  uuid,
    cancelled_at  timestamp,
    cancelled_by  uuid
);
CREATE INDEX idx_stock_transfers_org_status ON stock_transfers(org_id, status);

ALTER TABLE stock_transfers ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON stock_transfers
    USING (org_id = current_setting('app.org_id', true)::uuid);

-- 3) Transfer lines. source / dest live on the line rather than the header, so a future
-- multi-stop trip - one dispatch, several drop-offs at different locations - is the same shape.
-- v1 validates that every line of a transfer shares one source and one destination; the API
-- takes them once at the header level.
--   qty_requested  - what the transfer asks to move, in the variant's base unit
--   qty_dispatched - what actually left the source (v1: always == qty_requested on dispatch)
--   qty_received   - running total accepted at the destination across receive events
--   uom / uom_quantity - the unit and amount as a human entered them, kept verbatim (same
--                        pattern as stock_movements); qty_* are always normalized to base unit
CREATE TABLE stock_transfer_lines (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id        uuid NOT NULL REFERENCES stock_transfers(id) ON DELETE CASCADE,
    org_id             uuid NOT NULL,
    source_location_id uuid NOT NULL REFERENCES locations(id),
    dest_location_id   uuid NOT NULL REFERENCES locations(id),
    variant_id         uuid NOT NULL REFERENCES product_variants(id),
    qty_requested      numeric(14,3) NOT NULL CHECK (qty_requested > 0),
    qty_dispatched     numeric(14,3) NOT NULL DEFAULT 0,
    qty_received       numeric(14,3) NOT NULL DEFAULT 0,
    uom                varchar(50),
    uom_quantity       numeric(14,4),
    CHECK (source_location_id <> dest_location_id),
    UNIQUE (transfer_id, variant_id)
);
CREATE INDEX idx_stock_transfer_lines_transfer ON stock_transfer_lines(transfer_id);

ALTER TABLE stock_transfer_lines ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON stock_transfer_lines
    USING (org_id = current_setting('app.org_id', true)::uuid);
