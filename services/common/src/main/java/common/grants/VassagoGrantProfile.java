package common.grants;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static common.grants.GrantFragment.crud;
import static common.grants.GrantFragment.select;
import static common.grants.GrantFragment.selectColumns;
import static common.grants.GrantFragment.union;

/**
 * Grant model for the Vassago (identity) service.
 *
 * <ul>
 *   <li>{@code READONLY} — SELECT on {@code pending_verifications}; column-scoped SELECT
 *       on {@code users} covering every column <em>except</em> {@code password} (bcrypt
 *       hashes), which no API response exposes and a read-only lease has no business
 *       reading. No Vassago role maps here today; the tier is still generated so a future
 *       read-only role, or a manual lease, gets a correctly scoped grant.</li>
 *   <li>{@code FULL} — CRUD on both tables. {@code VASSAGO_ADMIN} and {@code VASSAGO_MEMBER}
 *       both map here ({@code VASSAGO_MEMBER} holds {@code VASSAGO_EDIT_USER}, a write).</li>
 * </ul>
 */
public final class VassagoGrantProfile {

    public static final String SERVICE_NAME = "Vassago";

    static final String[] ALL_TABLES = {"pending_verifications", "users"};

    /** Every {@code users} column except {@code password}. Kept in sync by {@code GrantProfileSchemaTest}. */
    static final List<String> USERS_READABLE_COLUMNS = List.of(
            "id",
            "org_id",
            "name",
            "last_name",
            "email",
            "username",
            "roles",
            "modification_lock",
            "locked_at",
            "created_at",
            "modified_at",
            "stopped_at",
            "is_ready",
            "locale");

    public static final ServiceGrantProfile PROFILE = build();

    private VassagoGrantProfile() {}

    private static ServiceGrantProfile build() {
        Set<GrantSpec> readonly = union(
                select("pending_verifications"),
                selectColumns("users", USERS_READABLE_COLUMNS));
        Set<GrantSpec> full = crud(ALL_TABLES);

        Map<ServiceTier, Set<GrantSpec>> grantsByTier = new EnumMap<>(ServiceTier.class);
        grantsByTier.put(ServiceTier.READONLY, readonly);
        grantsByTier.put(ServiceTier.FULL, full);

        Map<String, ServiceTier> roleNameToTier = new LinkedHashMap<>();
        roleNameToTier.put("VASSAGO_ADMIN", ServiceTier.FULL);
        roleNameToTier.put("VASSAGO_MEMBER", ServiceTier.FULL);

        return new ServiceGrantProfile(SERVICE_NAME, grantsByTier, roleNameToTier);
    }
}
