package common.grants;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrantStatementRendererTest {

    @Test
    void creationStatementsHavePreambleAndOrgIdStamp() {
        String sql = GrantStatementRenderer.creationStatements(
                BimeGrantProfile.PROFILE, ServiceTier.CATALOG, "bime", "org-123");

        assertThat(sql)
                .startsWith("CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; ")
                .contains("GRANT CONNECT ON DATABASE \"bime\" TO \"{{name}}\"; ")
                .contains("GRANT USAGE ON SCHEMA public TO \"{{name}}\"; ")
                .endsWith("ALTER ROLE \"{{name}}\" SET app.org_id = 'org-123';");
    }

    @Test
    void catalogTierIsSelectOnlyOnCatalogTables() {
        String sql = GrantStatementRenderer.creationStatements(
                BimeGrantProfile.PROFILE, ServiceTier.CATALOG, "__DBNAME__", "__ORG_ID__");

        assertThat(sql).contains("GRANT SELECT ON \"products\" TO \"{{name}}\";");
        assertThat(sql).contains("GRANT SELECT ON \"variant_barcodes\" TO \"{{name}}\";");
        assertThat(sql).doesNotContain("INSERT");
        assertThat(sql).doesNotContain("stock_movements");
        assertThat(sql).doesNotContain("ON ALL TABLES");
    }

    @Test
    void salesTierAddsTargetedWritesOnTopOfReadAll() {
        String sql = GrantStatementRenderer.creationStatements(
                BimeGrantProfile.PROFILE, ServiceTier.SALES, "bime", "o");

        assertThat(sql).contains("GRANT SELECT, INSERT ON \"sales\" TO \"{{name}}\";");
        assertThat(sql).contains("GRANT SELECT, INSERT ON \"stock_movements\" TO \"{{name}}\";");
        assertThat(sql).contains("GRANT SELECT, INSERT, UPDATE ON \"variant_stock_balances\" TO \"{{name}}\";");
        assertThat(sql).contains("GRANT SELECT ON \"products\" TO \"{{name}}\";");
    }

    @Test
    void fullTierIsCrudOnEveryBimeTable() {
        String sql = GrantStatementRenderer.creationStatements(
                BimeGrantProfile.PROFILE, ServiceTier.FULL, "bime", "o");

        for (String table : BimeGrantProfile.ALL_TABLES) {
            assertThat(sql).contains("GRANT SELECT, INSERT, UPDATE, DELETE ON \"" + table + "\" TO \"{{name}}\";");
        }
    }

    @Test
    void vassagoReadonlyExcludesPasswordViaColumnList() {
        String sql = GrantStatementRenderer.creationStatements(
                VassagoGrantProfile.PROFILE, ServiceTier.READONLY, "vassago", "o");

        assertThat(sql).contains(
                "GRANT SELECT (id, org_id, name, last_name, email, username, roles, "
                        + "modification_lock, locked_at, created_at, modified_at, stopped_at, "
                        + "is_ready, locale) ON \"users\" TO \"{{name}}\";");
        assertThat(sql).doesNotContain(", password");
        assertThat(sql).doesNotContain("password)");
        assertThat(sql).contains("GRANT SELECT ON \"pending_verifications\" TO \"{{name}}\";");
    }

    @Test
    void genericProfileUsesAllTablesForm() {
        ServiceGrantProfile generic = ServiceGrantProfiles.forServiceName("Nyx");
        String full = GrantStatementRenderer.creationStatements(generic, ServiceTier.FULL, "nyx", "o");
        String readonly = GrantStatementRenderer.creationStatements(generic, ServiceTier.READONLY, "nyx", "o");

        assertThat(full).contains("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO \"{{name}}\";");
        assertThat(readonly).contains("GRANT SELECT ON ALL TABLES IN SCHEMA public TO \"{{name}}\";");
    }

    @Test
    void renderingIsStableAcrossCalls() {
        String a = GrantStatementRenderer.creationStatements(
                BimeGrantProfile.PROFILE, ServiceTier.OPERATIONS, "bime", "o");
        String b = GrantStatementRenderer.creationStatements(
                BimeGrantProfile.PROFILE, ServiceTier.OPERATIONS, "bime", "o");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void revocationStatementsReassignToOwner() {
        assertThat(GrantStatementRenderer.revocationStatements("bime_owner"))
                .isEqualTo("REASSIGN OWNED BY \"{{name}}\" TO \"bime_owner\"; "
                        + "DROP OWNED BY \"{{name}}\"; "
                        + "DROP ROLE IF EXISTS \"{{name}}\";");
    }
}
