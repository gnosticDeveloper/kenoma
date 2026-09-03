package common.grants;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static common.grants.GrantFragment.crud;
import static common.grants.GrantFragment.insert;
import static common.grants.GrantFragment.insertUpdate;
import static common.grants.GrantFragment.select;
import static common.grants.GrantFragment.union;
import static common.grants.GrantFragment.writes;

/**
 * Grant model for the Bime (inventory) service. Five tiers derived from
 * {@code bime.security.BimeRole} → {@code BimePermission} sets:
 *
 * <ul>
 *   <li>{@code CATALOG}     — {@code BIME_CATALOG_VIEWER}: browse the product catalog only</li>
 *   <li>{@code READONLY}    — {@code BIME_VIEWER}: read everything, write nothing</li>
 *   <li>{@code SALES}       — {@code BIME_CASHIER}: ring up POS sales, deplete stock</li>
 *   <li>{@code OPERATIONS}  — {@code BIME_STOCK_OPERATOR}, {@code BIME_TRANSFER_APPROVER}:
 *       day-to-day catalog/stock/transfer maintenance</li>
 *   <li>{@code FULL}        — {@code BIME_ADMIN}: everything, including batch recall
 *       status transitions and deletes</li>
 * </ul>
 *
 * <p>The transfer create-vs-approve separation of duties ({@code BIME_STOCK_OPERATOR}
 * raises a transfer, {@code BIME_TRANSFER_APPROVE} approves it) cannot be expressed as a
 * table grant — both map to {@code OPERATIONS}, which holds {@code UPDATE} on
 * {@code stock_transfers}. That boundary stays enforced in the controller
 * ({@code @PreAuthorize}) only.
 */
public final class BimeGrantProfile {

    public static final String SERVICE_NAME = "Bime";

    /** Every Bime tenant table, kept in sync with the migrations by {@code GrantProfileSchemaTest}. */
    static final String[] ALL_TABLES = {
            "batch_expiry_alerts",
            "locations",
            "org_barcode_settings",
            "org_batch_settings",
            "org_units",
            "pending_location_verifications",
            "product_metadata",
            "product_metadata_assignments",
            "product_metadata_option",
            "product_option_selections",
            "products",
            "product_variant_options",
            "product_variants",
            "sale_lines",
            "sales",
            "stock_batch_balances",
            "stock_batches",
            "stock_movements",
            "stock_transfer_lines",
            "stock_transfers",
            "variant_barcodes",
            "variant_stock_alerts",
            "variant_stock_alert_thresholds",
            "variant_stock_balances",
            "variant_uom_conversions",
    };

    private static final String[] CATALOG_TABLES = {
            "products",
            "product_variants",
            "product_variant_options",
            "product_metadata",
            "product_metadata_option",
            "product_metadata_assignments",
            "product_option_selections",
            "variant_barcodes",
            "org_units",
            "variant_uom_conversions",
            "org_barcode_settings",
    };

    private static final String[] OPERATIONS_WRITE_TABLES = {
            "products",
            "product_variants",
            "product_variant_options",
            "product_metadata",
            "product_metadata_option",
            "product_metadata_assignments",
            "product_option_selections",
            "locations",
            "org_units",
            "variant_uom_conversions",
            "variant_barcodes",
            "stock_transfers",
            "stock_transfer_lines",
            "variant_stock_alert_thresholds",
            "variant_stock_alerts",
            "org_batch_settings",
            "org_barcode_settings",
    };

    public static final ServiceGrantProfile PROFILE = build();

    private BimeGrantProfile() {}

    private static ServiceGrantProfile build() {
        Set<GrantSpec> catalog = select(CATALOG_TABLES);
        Set<GrantSpec> readonly = select(ALL_TABLES);
        Set<GrantSpec> sales = union(
                readonly,
                insert("sales", "sale_lines", "stock_movements"),
                insertUpdate("variant_stock_balances", "stock_batch_balances"));
        Set<GrantSpec> operations = union(
                sales,
                writes(OPERATIONS_WRITE_TABLES),
                insertUpdate("stock_batches"));
        Set<GrantSpec> full = crud(ALL_TABLES);

        Map<ServiceTier, Set<GrantSpec>> grantsByTier = new EnumMap<>(ServiceTier.class);
        grantsByTier.put(ServiceTier.CATALOG, catalog);
        grantsByTier.put(ServiceTier.READONLY, readonly);
        grantsByTier.put(ServiceTier.SALES, sales);
        grantsByTier.put(ServiceTier.OPERATIONS, operations);
        grantsByTier.put(ServiceTier.FULL, full);

        Map<String, ServiceTier> roleNameToTier = new LinkedHashMap<>();
        roleNameToTier.put("BIME_ADMIN", ServiceTier.FULL);
        roleNameToTier.put("BIME_STOCK_OPERATOR", ServiceTier.OPERATIONS);
        roleNameToTier.put("BIME_TRANSFER_APPROVER", ServiceTier.OPERATIONS);
        roleNameToTier.put("BIME_CASHIER", ServiceTier.SALES);
        roleNameToTier.put("BIME_VIEWER", ServiceTier.READONLY);
        roleNameToTier.put("BIME_CATALOG_VIEWER", ServiceTier.CATALOG);

        return new ServiceGrantProfile(SERVICE_NAME, grantsByTier, roleNameToTier);
    }
}
