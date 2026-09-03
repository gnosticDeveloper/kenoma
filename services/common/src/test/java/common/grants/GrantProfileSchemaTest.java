package common.grants;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps every {@link ServiceGrantProfile} honest against the Flyway migrations: a new
 * table added to a migration must be granted somewhere in that service's {@code FULL}
 * tier, and every table a profile names must actually exist. Also pins Vassago's
 * {@code users} read-only column list to the real schema minus {@code password}.
 */
class GrantProfileSchemaTest {

    private static final Path MIGRATION_ROOT = Path.of("src/main/resources/db/migration");

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?\"?([a-z_][a-z0-9_]*)\"?",
            Pattern.CASE_INSENSITIVE);

    @Test
    void bimeProfileCoversExactlyTheBimeMigrationTables() {
        Set<String> migrationTables = tablesDefinedIn("bime");
        Set<String> fullTierTables = tablesGrantedBy(BimeGrantProfile.PROFILE, ServiceTier.FULL);

        assertThat(fullTierTables)
                .as("every Bime migration table must be in the FULL tier")
                .containsAll(migrationTables);
        assertThat(migrationTables)
                .as("FULL tier must not grant tables that don't exist")
                .containsAll(fullTierTables);
        assertThat(List.of(BimeGrantProfile.ALL_TABLES))
                .as("BimeGrantProfile.ALL_TABLES must match the migrations")
                .containsExactlyInAnyOrderElementsOf(migrationTables);
    }

    @Test
    void bimeNarrowerTiersOnlyReferenceRealTables() {
        Set<String> migrationTables = tablesDefinedIn("bime");
        for (ServiceTier tier : BimeGrantProfile.PROFILE.supportedTiers()) {
            assertThat(migrationTables)
                    .as("tier " + tier + " references only real tables")
                    .containsAll(tablesGrantedBy(BimeGrantProfile.PROFILE, tier));
        }
    }

    @Test
    void vassagoProfileCoversExactlyTheVassagoMigrationTables() {
        Set<String> migrationTables = tablesDefinedIn("vassago");
        Set<String> fullTierTables = tablesGrantedBy(VassagoGrantProfile.PROFILE, ServiceTier.FULL);

        assertThat(fullTierTables).containsAll(migrationTables);
        assertThat(migrationTables).containsAll(fullTierTables);
    }

    @Test
    void vassagoReadonlyUsersColumnsAreEveryUsersColumnExceptPassword() {
        List<String> actualColumns = columnsOf("vassago", "users");

        assertThat(actualColumns).contains("password");
        assertThat(VassagoGrantProfile.USERS_READABLE_COLUMNS)
                .containsExactlyElementsOf(actualColumns.stream().filter(c -> !c.equals("password")).toList());
    }

    // --- helpers ---------------------------------------------------------------

    private static Set<String> tablesGrantedBy(ServiceGrantProfile profile, ServiceTier tier) {
        Set<String> tables = new LinkedHashSet<>();
        for (GrantSpec spec : profile.grantsFor(tier)) {
            if (!spec.isAllTables()) {
                tables.add(spec.table());
            }
        }
        return tables;
    }

    private static Set<String> tablesDefinedIn(String service) {
        Set<String> tables = new LinkedHashSet<>();
        for (String sql : migrationContents(service)) {
            Matcher m = CREATE_TABLE.matcher(sql);
            while (m.find()) {
                tables.add(m.group(1).toLowerCase());
            }
        }
        tables.removeIf(t -> t.startsWith("flyway_"));
        return tables;
    }

    private static List<String> migrationContents(String service) {
        Path dir = MIGRATION_ROOT.resolve(service);
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> p.toString().endsWith(".sql"))
                    .sorted()
                    .map(p -> {
                        try {
                            return Files.readString(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + dir, e);
        }
    }

    /** Column names of {@code CREATE TABLE <table> ( ... )} in the given service's migrations. */
    private static List<String> columnsOf(String service, String table) {
        Pattern block = Pattern.compile(
                "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?\"?" + Pattern.quote(table) + "\"?\\s*\\((.*?)\\n\\s*\\);",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        for (String sql : migrationContents(service)) {
            Matcher m = block.matcher(sql);
            if (m.find()) {
                List<String> columns = new java.util.ArrayList<>();
                for (String rawLine : m.group(1).split("\n")) {
                    String line = rawLine.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    String first = line.split("[\\s(]+")[0].replaceAll("[\",]", "").toLowerCase();
                    if (first.isEmpty() || isConstraintKeyword(first)) {
                        continue;
                    }
                    columns.add(first);
                }
                return columns;
            }
        }
        throw new AssertionError("no CREATE TABLE " + table + " found in " + service + " migrations");
    }

    private static boolean isConstraintKeyword(String token) {
        return Set.of("primary", "foreign", "unique", "constraint", "check").contains(token);
    }
}
