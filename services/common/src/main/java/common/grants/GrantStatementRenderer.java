package common.grants;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;

/**
 * Renders a {@link ServiceGrantProfile} tier into the exact SQL strings OpenBao's
 * database secrets engine stores as {@code creation_statements} / {@code revocation_statements}
 * for a dynamic role. The single source of truth for that text — raum's runtime role
 * registration and the build-time bootstrap generator both call this, so a Platform-org
 * lease and a tenant-org lease of the same tier are byte-for-byte identical grants.
 *
 * <p>{@code {{name}}}, {@code {{password}}} and {@code {{expiration}}} are OpenBao
 * templating placeholders and are emitted literally.
 */
public final class GrantStatementRenderer {

    private GrantStatementRenderer() {}

    /**
     * @param dbName database name for the {@code GRANT CONNECT}; callers may pass a literal
     *               marker (e.g. {@code __DBNAME__}) for later substitution
     * @param orgId  value for {@code ALTER ROLE ... SET app.org_id}; callers may pass a
     *               literal marker (e.g. {@code __ORG_ID__})
     */
    public static String creationStatements(ServiceGrantProfile profile, ServiceTier tier,
                                            String dbName, String orgId) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; ");
        sql.append("GRANT CONNECT ON DATABASE \"").append(dbName).append("\" TO \"{{name}}\"; ");
        sql.append("GRANT USAGE ON SCHEMA public TO \"{{name}}\"; ");

        List<GrantSpec> ordered = new ArrayList<>(profile.grantsFor(tier));
        ordered.sort(Comparator.comparing(GrantSpec::table));
        for (GrantSpec spec : ordered) {
            sql.append(grantClause(spec)).append(' ');
        }

        sql.append("ALTER ROLE \"{{name}}\" SET app.org_id = '").append(orgId).append("';");
        return sql.toString();
    }

    public static String revocationStatements(String ownerUsername) {
        return "REASSIGN OWNED BY \"{{name}}\" TO \"" + ownerUsername + "\"; "
                + "DROP OWNED BY \"{{name}}\"; "
                + "DROP ROLE IF EXISTS \"{{name}}\";";
    }

    private static String grantClause(GrantSpec spec) {
        String target = spec.isAllTables()
                ? "ALL TABLES IN SCHEMA public"
                : "\"" + spec.table() + "\"";

        if (spec.isColumnScoped()) {
            String columnList = "(" + String.join(", ", spec.columns()) + ")";
            StringJoiner privileges = new StringJoiner(", ");
            for (Op op : spec.ops()) {
                privileges.add(op.name() + " " + columnList);
            }
            return "GRANT " + privileges + " ON " + target + " TO \"{{name}}\";";
        }

        StringJoiner privileges = new StringJoiner(", ");
        for (Op op : spec.ops()) {
            privileges.add(op.name());
        }
        return "GRANT " + privileges + " ON " + target + " TO \"{{name}}\";";
    }
}
