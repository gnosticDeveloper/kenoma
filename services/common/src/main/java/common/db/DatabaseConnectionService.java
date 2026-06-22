package common.db;

import common.dto.CredentialsDTO;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;

import static io.r2dbc.spi.ConnectionFactoryOptions.*;

/**
 * Stateless factory that builds an R2DBC {@link ConnectionPool} from ephemeral credentials.
 *
 * <p>Returns a {@link ConnectionPool} rather than a {@link org.springframework.r2dbc.core.DatabaseClient}
 * so that each service's connection pool manager can control the pool lifecycle and call
 * {@link ConnectionPool#dispose()} on eviction, closing all physical connections and
 * releasing any credential state held internally by the R2DBC driver.
 *
 * <p><strong>Security:</strong> this class is intentionally stateless — credentials are
 * consumed once to build the factory and are not retained as fields.
 *
 * <p><strong>Note on heap credential exposure:</strong> the R2DBC PostgreSQL driver holds
 * the password internally as a plain {@code String}. This is unavoidable at the JVM level;
 * mitigation relies on deploy discipline (restricted heap dumps, no core dumps in prod)
 * rather than application-layer controls.
 */
public class DatabaseConnectionService {

    private final int poolMaxSize;
    private final int poolInitialSize;

    public DatabaseConnectionService(int poolMaxSize, int poolInitialSize) {
        this.poolMaxSize = poolMaxSize;
        this.poolInitialSize = poolInitialSize;
    }

    /**
     * Builds a {@link ConnectionPool} from the supplied credentials.
     * The caller is responsible for storing only this pool — not the
     * {@link CredentialsDTO} — so raw credential strings do not persist
     * in memory beyond this method call.
     */
    public ConnectionPool createConnectionPool(CredentialsDTO credentials) {
        String driver = resolveR2dbcDriver(credentials.getDbEngine());

        ConnectionFactoryOptions options = ConnectionFactoryOptions.builder()
                .option(DRIVER, driver)
                .option(HOST, credentials.getDbHost())
                .option(PORT, credentials.getDbPort())
                .option(USER, credentials.getUserName())
                .option(PASSWORD, credentials.getPassword())
                .option(DATABASE, credentials.getDbName())
                .build();

        ConnectionFactory factory = ConnectionFactories.get(options);

        return new ConnectionPool(
                ConnectionPoolConfiguration.builder(factory)
                        .initialSize(poolInitialSize)
                        .maxSize(poolMaxSize)
                        .validationQuery("SELECT 1")
                        .build()
        );
    }

    private String resolveR2dbcDriver(String engine) {
        return switch (engine.toLowerCase()) {
            case "postgresql", "postgres" -> "postgresql";
            default -> throw new IllegalArgumentException("Unsupported db engine: " + engine);
        };
    }
}
