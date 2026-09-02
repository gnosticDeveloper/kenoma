-- Audit trail for till-side price overrides on POS sale lines.
--
-- sale_lines.unit_price is what was actually charged. Before this migration a cashier
-- (BIME_SALE, the lowest till role) could pass any unitPrice on a line - including 0 or
-- sub-cent - and the sale completed with no record that the price differed from the
-- catalogue. These two columns keep that record:
--   catalogue_unit_price - the variant's effective price for that unit at the moment of
--                          sale (null when no price was on file for the item)
--   price_overridden      - true when the charged unit_price differs from
--                          catalogue_unit_price (or any client price when nothing was
--                          on file). Lets an operator find below-catalogue tills later.
ALTER TABLE sale_lines ADD COLUMN catalogue_unit_price numeric(12,2);
ALTER TABLE sale_lines ADD COLUMN price_overridden      boolean NOT NULL DEFAULT false;

CREATE INDEX idx_sale_lines_org_overridden ON sale_lines(org_id) WHERE price_overridden;
