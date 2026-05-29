package vassago.db;

import common.dto.CredentialsDTO;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import static io.r2dbc.spi.ConnectionFactoryOptions.*;

@Service
public class DatabaseConnectionService {

    public DatabaseClient createReactiveClient(CredentialsDTO credentials) {
        String driver = resolveR2dbcDriver(credentials.getDbEngine());

        ConnectionFactoryOptions options = ConnectionFactoryOptions.builder()
                .option(DRIVER, driver)
                .option(HOST, credentials.getDbHost())
                .option(PORT, credentials.getDbPort())
                .option(USER, credentials.getUserName())
                .option(PASSWORD, credentials.getPassword())
                .option(DATABASE, credentials.getDbName())
                .build();

        ConnectionFactory connectionFactory = ConnectionFactories.get(options);
        return DatabaseClient.create(connectionFactory);
    }

    private String resolveR2dbcDriver(String engine) {
        return switch (engine.toLowerCase()) {
            case "postgresql", "postgres" -> "postgresql";
            default -> throw new IllegalArgumentException("Unsupported db engine: " + engine);
        };
    }
}