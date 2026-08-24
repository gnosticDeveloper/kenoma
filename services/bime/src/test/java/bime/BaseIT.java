package bime;

import bime.db.BimeDbHandle;
import bime.db.ConnectionPoolService;
import common.mail.MailgunService;
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

    @MockitoBean
    protected MailgunService mailgunService;

    // No live OpenBao in this suite; disable real AppRole provisioning so context
    // startup doesn't block on (or retry against) a nonexistent server.
    @MockitoBean
    protected bime.openbao.OpenBaoProvisioner openBaoProvisioner;

    protected static final UUID BIME_SERVICE_ID = UUID.randomUUID();
    protected static final UUID ORG_ID          = UUID.randomUUID();
    protected static final UUID ORG_ID_B        = UUID.randomUUID();
    protected static final UUID USER_ID          = UUID.randomUUID();

    @SuppressWarnings({"resource", "rawtypes"})
    static final PostgreSQLContainer bimeDb = new PostgreSQLContainer("postgres:18.1-alpine3.23")
            .withDatabaseName("bime")
            .withUsername("postgres")
            .withPassword("postgres");

    static {
        bimeDb.start();
        org.flywaydb.core.Flyway.configure()
                .dataSource(bimeDb.getJdbcUrl(), bimeDb.getUsername(), bimeDb.getPassword())
                .locations("classpath:db/migration/bime")
                .load()
                .migrate();
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
        when(connectionPoolService.getHandleViaVaultToken(any(), any())).thenReturn(Mono.just(testHandle));
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
        mockJwtWithRole("BIME_CATALOG_VIEWER", ORG_ID);
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

        @Override
        public void initialize(ConfigurableApplicationContext ctx) {
            // Runs once per distinct Spring context (Spring's test context cache builds a separate
            // context per unique set of @MockitoBean overrides), so this must NOT be guarded by a
            // JVM-static "already ran" flag - that previously caused any IT class whose @MockitoBean
            // set differs from the rest (e.g. one that also mocks RaumClient) to silently boot with
            // none of these properties set, since the flag had already been tripped by an earlier,
            // differently-configured context.
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(ctx,
                    "BIME_SERVICE_ID=" + BIME_SERVICE_ID.toString(),
                    "vassago.jwt.public-key-refresh-cron=-",
                    "bime.stock-alerts.check-cron=-",
                    "mailgun.api-key=test-key",
                    "mailgun.domain=test.example.com",
                    "mailgun.from=noreply@test.example.com",
                    "app.base-url=http://localhost:3000"
            );
        }
    }
}
