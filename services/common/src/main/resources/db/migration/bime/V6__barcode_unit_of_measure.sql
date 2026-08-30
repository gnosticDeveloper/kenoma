-- Tie each barcode to a unit of measure, so scanning a pack-level barcode (a case, a six-pack)
-- resolves to that pack's quantity multiplier rather than just the variant. In real GS1 practice a
-- single unit and a case are different GTINs; this lets the two coexist on one variant and be told
-- apart at point of sale.

ALTER TABLE variant_barcodes ADD COLUMN uom_id uuid REFERENCES org_units(id);

-- Existing barcodes are for the variant's base unit.
UPDATE variant_barcodes vb
SET uom_id = pv.base_uom_id
FROM product_variants pv
WHERE pv.id = vb.variant_id AND vb.uom_id IS NULL;

ALTER TABLE variant_barcodes ALTER COLUMN uom_id SET NOT NULL;
CREATE INDEX idx_variant_barcodes_uom ON variant_barcodes(uom_id);

-- Primary is now per (variant, unit): the base-unit barcode and the case barcode each get their
-- own primary, which is what the label sheet's "one label per variant" default prints.
DROP INDEX IF EXISTS uq_variant_barcodes_primary;
CREATE UNIQUE INDEX uq_variant_barcodes_primary
    ON variant_barcodes (variant_id, uom_id) WHERE is_primary;
