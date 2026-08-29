-- Decimal quantities, an org-level unit-of-measure catalog with conversions, and cost (COGS)
-- tracking. Quantity-shaped columns move from integer to numeric so stock can be tracked by
-- weight/length/volume, not just whole units. Units are looked up through org_units instead of
-- being raw strings on each variant/conversion, so "kg", "Kg", and "Kilogram" can't end up as
-- unrelated units within the same org. Standard metric conversions (kg<->g, m<->cm, l<->ml) are
-- physical constants computed on the fly in application code (see StandardUnits), not stored
-- here - org_units only holds the catalog of unit names an org actually uses, standard or custom.

-- 1) Widen quantity-shaped columns from integer to numeric. numeric(14,3) gives three decimal
-- places (kg/L/m granularity) while comfortably covering any realistic on-hand quantity.
ALTER TABLE stock_movements ALTER COLUMN delta TYPE numeric(14,3) USING delta::numeric(14,3);

ALTER TABLE variant_stock_balances ALTER COLUMN quantity TYPE numeric(14,3) USING quantity::numeric(14,3);
ALTER TABLE variant_stock_balances ALTER COLUMN quantity SET DEFAULT 0;

ALTER TABLE variant_stock_alert_thresholds ALTER COLUMN threshold TYPE numeric(14,3) USING threshold::numeric(14,3);

ALTER TABLE variant_stock_alerts ALTER COLUMN threshold TYPE numeric(14,3) USING threshold::numeric(14,3);
ALTER TABLE variant_stock_alerts ALTER COLUMN quantity TYPE numeric(14,3) USING quantity::numeric(14,3);

-- 2) Org-level unit catalog.
CREATE TABLE org_units (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     uuid NOT NULL,
    name       varchar(50) NOT NULL,
    created_at timestamp NOT NULL DEFAULT current_timestamp,
    UNIQUE (org_id, name)
);

ALTER TABLE org_units ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON org_units
    USING (org_id = current_setting('app.org_id', true)::uuid);

-- 3) Variant base unit, as a strict FK into the org's unit catalog. Defaults every existing
-- variant to "units" (auto-registered as a standard unit below).
INSERT INTO org_units (org_id, name)
SELECT DISTINCT org_id, 'units' FROM product_variants
ON CONFLICT (org_id, name) DO NOTHING;

ALTER TABLE product_variants ADD COLUMN base_uom_id uuid REFERENCES org_units(id);
UPDATE product_variants pv
SET base_uom_id = ou.id
FROM org_units ou
WHERE ou.org_id = pv.org_id AND ou.name = 'units';
ALTER TABLE product_variants ALTER COLUMN base_uom_id SET NOT NULL;

CREATE INDEX idx_product_variants_base_uom_id ON product_variants(base_uom_id);

-- 4) Alternate units a variant can be bought/sold in, and their conversion factor to base_uom_id.
-- An optional flat price lets a unit be priced as a bulk discount (e.g. $18/case flat) rather
-- than always exactly factor * the base-unit price. Cost has no such override: unlike price,
-- there's no batch/purchase-record yet to back a real per-unit cost, so effective cost is always
-- purely derived (factor * variant.cost) in application code.
CREATE TABLE variant_uom_conversions (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      uuid NOT NULL,
    variant_id  uuid NOT NULL REFERENCES product_variants(id),
    uom_id      uuid NOT NULL REFERENCES org_units(id),
    factor      numeric(14,4) NOT NULL CHECK (factor > 0),
    price       numeric(12,2),
    created_at  timestamp NOT NULL DEFAULT current_timestamp,
    modified_at timestamp NOT NULL DEFAULT current_timestamp,
    UNIQUE (variant_id, uom_id)
);
CREATE INDEX idx_variant_uom_conversions_variant ON variant_uom_conversions(variant_id);
CREATE INDEX idx_variant_uom_conversions_uom_id ON variant_uom_conversions(uom_id);

ALTER TABLE variant_uom_conversions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON variant_uom_conversions
    USING (org_id = current_setting('app.org_id', true)::uuid);

-- 5) stock_movements keeps an immutable historical record of the unit a movement was literally
-- entered in (plain string, independent of later catalog edits/renames), alongside the delta
-- above which is always normalized to the variant's base unit.
ALTER TABLE stock_movements ADD COLUMN uom varchar(50);
ALTER TABLE stock_movements ADD COLUMN uom_quantity numeric(14,4);

-- 6) Purchase cost (COGS) as a variant-level field, independent of and parallel to the existing
-- price/price_currency pair - informational only, lets a margin be computed (price - cost).
ALTER TABLE product_variants ADD COLUMN cost numeric(12,2);
ALTER TABLE product_variants ADD COLUMN cost_currency varchar(3);
