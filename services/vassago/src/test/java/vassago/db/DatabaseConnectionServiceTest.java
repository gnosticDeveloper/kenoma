package vassago.db;

import common.dto.CredentialsDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseConnectionServiceTest {

    private DatabaseConnectionService service;

    @BeforeEach
    void setUp() {
        service = new DatabaseConnectionService();
        ReflectionTestUtils.setField(service, "poolMaxSize", 5);
        ReflectionTestUtils.setField(service, "poolInitialSize", 1);
    }

    @Test
    void unsupportedEngine_throwsIllegalArgumentException() {
        CredentialsDTO credentials = new CredentialsDTO();
        credentials.setDbEngine("mysql");
        credentials.setDbHost("localhost");
        credentials.setDbPort(3306);
        credentials.setDbName("testdb");
        credentials.setUserName("user");
        credentials.setPassword("pass");

        assertThatThrownBy(() -> service.createConnectionPool(credentials))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported db engine: mysql");
    }

    @Test
    void postgresEngine_resolvesWithoutThrowing() {
        CredentialsDTO credentials = new CredentialsDTO();
        credentials.setDbEngine("postgres");
        credentials.setDbHost("localhost");
        credentials.setDbPort(5432);
        credentials.setDbName("testdb");
        credentials.setUserName("user");
        credentials.setPassword("pass");

        // createConnectionPool builds the factory/pool from the options — it does not
        // open a connection, so this succeeds even with no real database available.
        var pool = service.createConnectionPool(credentials);
        assertThat(pool).isNotNull();
        pool.dispose(); // clean up
    }

    @Test
    void postgresqlEngine_resolvesWithoutThrowing() {
        CredentialsDTO credentials = new CredentialsDTO();
        credentials.setDbEngine("postgresql");
        credentials.setDbHost("localhost");
        credentials.setDbPort(5432);
        credentials.setDbName("testdb");
        credentials.setUserName("user");
        credentials.setPassword("pass");

        var pool = service.createConnectionPool(credentials);
        assertThat(pool).isNotNull();
        pool.dispose();
    }
}