package raum;

import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Applies a service's real Flyway migrations to a Testcontainers Postgres instance, so IT suites
 * exercise the same {@code classpath:db/migration/<service>} files production runs instead of a
 * hand-maintained duplicate fixture. */
final class TestMigrations {

    private TestMigrations() {
    }

    @SuppressWarnings("rawtypes")
    static void migrate(PostgreSQLContainer db, String service) {
        Flyway.configure()
                .dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                .locations("classpath:db/migration/" + service)
                .load()
                .migrate();
    }
}
