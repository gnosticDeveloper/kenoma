
ALTER TABLE locations ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON locations
    USING (org_id = current_setting('app.org_id', true)::uuid);

ALTER TABLE pending_location_verifications ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pending_location_verifications
    USING (EXISTS (
        SELECT 1 FROM locations
        WHERE locations.id = pending_location_verifications.location_id
          AND locations.org_id = current_setting('app.org_id', true)::uuid
    ));

ALTER TABLE products ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON products
    USING (org_id = current_setting('app.org_id', true)::uuid);

ALTER TABLE product_metadata ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON product_metadata
    USING (org_id = current_setting('app.org_id', true)::uuid);

ALTER TABLE product_metadata_option ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON product_metadata_option
    USING (EXISTS (
        SELECT 1 FROM product_metadata
        WHERE product_metadata.id = product_metadata_option.metadata_id
          AND product_metadata.org_id = current_setting('app.org_id', true)::uuid
    ));

ALTER TABLE product_metadata_assignments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON product_metadata_assignments
    USING (EXISTS (
        SELECT 1 FROM products
        WHERE products.id = product_metadata_assignments.product_id
          AND products.org_id = current_setting('app.org_id', true)::uuid
    ));

ALTER TABLE product_option_selections ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON product_option_selections
    USING (EXISTS (
        SELECT 1 FROM product_metadata_assignments a
        JOIN products p ON p.id = a.product_id
        WHERE a.id = product_option_selections.assignment_id
          AND p.org_id = current_setting('app.org_id', true)::uuid
    ));

ALTER TABLE product_variants ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON product_variants
    USING (org_id = current_setting('app.org_id', true)::uuid);

ALTER TABLE product_variant_options ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON product_variant_options
    USING (EXISTS (
        SELECT 1 FROM product_variants
        WHERE product_variants.id = product_variant_options.variant_id
          AND product_variants.org_id = current_setting('app.org_id', true)::uuid
    ));

ALTER TABLE stock_movements ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON stock_movements
    USING (org_id = current_setting('app.org_id', true)::uuid);

ALTER TABLE variant_stock_balances ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON variant_stock_balances
    USING (org_id = current_setting('app.org_id', true)::uuid);

ALTER TABLE variant_stock_alert_thresholds ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON variant_stock_alert_thresholds
    USING (org_id = current_setting('app.org_id', true)::uuid);

ALTER TABLE variant_stock_alerts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON variant_stock_alerts
    USING (org_id = current_setting('app.org_id', true)::uuid);
