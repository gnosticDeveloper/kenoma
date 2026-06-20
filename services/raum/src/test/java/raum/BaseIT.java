package raum;

import common.security.JwtValidator;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = BaseIT.Initializer.class)
public abstract class BaseIT {

    @MockitoBean
    protected JwtValidator jwtValidator;

    static final Network network = Network.newNetwork();

    @SuppressWarnings({"resource", "rawtypes"})
    static final PostgreSQLContainer raumDb = new PostgreSQLContainer("postgres:18.1-alpine3.23")
            .withNetwork(network)
            .withNetworkAliases("raum-postgres")
            .withDatabaseName("raum")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("init.sql");

    static {
        raumDb.start();
    }

    protected static UUID orgId;
    protected static UUID raumServiceId;

    private static final AtomicBoolean bootstrapped = new AtomicBoolean(false);

    @BeforeAll
    static void bootstrapOnce() throws Exception {
        if (!bootstrapped.compareAndSet(false, true)) return;

        orgId = UUID.fromString(raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                        "-t", "-A", "-c", "SELECT id FROM organizations WHERE name = 'Platform' LIMIT 1;")
                .getStdout().trim());

        raumServiceId = UUID.fromString(raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                        "-t", "-A", "-c", "SELECT id FROM services WHERE name = 'Raum' LIMIT 1;")
                .getStdout().trim());
    }

    @SuppressWarnings("unchecked")
    protected void mockAdminJwt() {
        Claims claims = mock(Claims.class);
        String rolesJson = "{\"" + raumServiceId + "\":[\"RAUM_ADMIN\"]}";
        when(claims.getSubject()).thenReturn("test-admin");
        when(claims.get(eq("orgId"), eq(String.class))).thenReturn(orgId.toString());
        when(claims.get(eq("roles"), eq(String.class))).thenReturn(rolesJson);
        when(jwtValidator.validateToken(anyString())).thenReturn(Mono.just(claims));
    }

    static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        private static final AtomicBoolean initialized = new AtomicBoolean(false);
        private static String raumServiceIdStr;

        @Override
        public void initialize(ConfigurableApplicationContext ctx) {
            if (initialized.compareAndSet(false, true)) {
                doSetup();
            }
            injectProperties(ctx);
        }

        private static void doSetup() {
            try {
                raumServiceIdStr = raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                                "-t", "-A", "-c", "SELECT id FROM services WHERE name = 'Raum' LIMIT 1;")
                        .getStdout().trim();
            } catch (Exception e) {
                throw new RuntimeException("Failed to read Raum service ID", e);
            }
        }

        private static void injectProperties(ConfigurableApplicationContext ctx) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(ctx,
                    "spring.r2dbc.url=r2dbc:postgresql://localhost:%d/raum".formatted(raumDb.getMappedPort(5432)),
                    "spring.r2dbc.username=postgres",
                    "spring.r2dbc.password=postgres",
                    "openbao.host=http://fake-openbao:8200",
                    "openbao.token=fake-token",
                    "openbao.kv.mount=secret",
                    "RAUM_SERVICE_ID=" + raumServiceIdStr,
                    "RAUM_JWT_TRANSIT_KEY_NAME=vassago-jwt"
            );
        }
    }
}
