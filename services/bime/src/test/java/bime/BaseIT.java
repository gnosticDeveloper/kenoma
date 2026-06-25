package bime;

import bime.db.BimeDbHandle;
import bime.db.ConnectionPoolService;
import common.security.JwtValidator;
import io.jsonwebtoken.Claims;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = BaseIT.Initializer.class)
public abstract class BaseIT {

    @MockitoBean
    protected JwtValidator jwtValidator;

    @MockitoBean
    protected ConnectionPoolService connectionPoolService;

    protected static final UUID BIME_SERVICE_ID = UUID.randomUUID();
    protected static final UUID ORG_ID          = UUID.randomUUID();
    protected static final UUID ORG_ID_B        = UUID.randomUUID();
    protected static final UUID USER_ID          = UUID.randomUUID();

    @SuppressWarnings({"resource", "rawtypes"})
    static final PostgreSQLContainer bimeDb = new PostgreSQLContainer("postgres:18.1-alpine3.23")
            .withDatabaseName("bime")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("init.sql");

    static {
        bimeDb.start();
    }

    private static final AtomicBoolean bootstrapped = new AtomicBoolean(false);
    protected static BimeDbHandle testHandle;

    @BeforeAll
    static void bootstrapOnce() {
        if (!bootstrapped.compareAndSet(false, true)) return;
        ConnectionFactory cf = ConnectionFactories.get(
                ConnectionFactoryOptions.builder()
                        .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                        .option(ConnectionFactoryOptions.HOST, bimeDb.getHost())
                        .option(ConnectionFactoryOptions.PORT, bimeDb.getMappedPort(5432))
                        .option(ConnectionFactoryOptions.DATABASE, bimeDb.getDatabaseName())
                        .option(ConnectionFactoryOptions.USER, bimeDb.getUsername())
                        .option(ConnectionFactoryOptions.PASSWORD, bimeDb.getPassword())
                        .build());
        DatabaseClient client = DatabaseClient.create(cf);
        TransactionalOperator tx = TransactionalOperator.create(new R2dbcTransactionManager(cf));
        testHandle = new BimeDbHandle(client, tx);
    }

    @BeforeEach
    void setUpMocks() {
        when(connectionPoolService.getHandle(any())).thenReturn(Mono.just(testHandle));
        testHandle.client()
                .sql("TRUNCATE locations, products, product_metadata CASCADE")
                .fetch().rowsUpdated().block();
    }

    protected void mockAdminJwt() {
        mockJwtWithRole("BIME_ADMIN", ORG_ID);
    }

    protected void mockViewerJwt() {
        mockJwtWithRole("BIME_VIEWER", ORG_ID);
    }

    protected void mockUserJwt() {
        mockJwtWithRole("BIME_USER", ORG_ID);
    }

    protected void mockAdminJwtForOrg(UUID orgId) {
        mockJwtWithRole("BIME_ADMIN", orgId);
    }

    @SuppressWarnings("unchecked")
    private void mockJwtWithRole(String role, UUID orgId) {
        Claims claims = mock(Claims.class);
        String rolesJson = "{\"" + BIME_SERVICE_ID + "\":[\"" + role + "\"]}";
        when(claims.getSubject()).thenReturn(USER_ID.toString());
        when(claims.get(eq("orgId"), eq(String.class))).thenReturn(orgId.toString());
        when(claims.get(eq("roles"), eq(String.class))).thenReturn(rolesJson);
        when(jwtValidator.validateToken(anyString())).thenReturn(Mono.just(claims));
    }

    static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        private static final AtomicBoolean initialized = new AtomicBoolean(false);

        @Override
        public void initialize(ConfigurableApplicationContext ctx) {
            if (initialized.compareAndSet(false, true)) {
                TestPropertySourceUtils.addInlinedPropertiesToEnvironment(ctx,
                        "BIME_SERVICE_ID=" + BIME_SERVICE_ID.toString()
                );
            }
        }
    }
}
