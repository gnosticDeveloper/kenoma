package vassago;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import vassago.dto.LoginRequestDTO;
import vassago.dto.LoginResponseDTO;
import vassago.security.VassagoRole;
import common.mail.MailgunService;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = BaseIT.Initializer.class)
@TestPropertySource(properties = {
        "mailgun.api-key=test-key",
        "mailgun.domain=test.example.com",
        "mailgun.from=noreply@test.example.com",
        "app.base-url=http://localhost:3000",
        "vassago.cookie.domain=.test.local",
        // IT classes share one Spring context, one Redis instance, and one client IP,
        // and collectively hit /auth/* far more than the production per-IP budget —
        // effectively disable the limit here; RateLimitFilterIT verifies the real
        // behavior in its own isolated context with a tight override.
        "vassago.rate-limit.max-requests=1000000"
})
public abstract class BaseIT {

    @MockitoBean
    protected MailgunService mailgunService;

    static final Network network = Network.newNetwork();

    @SuppressWarnings("resource")
    static final PostgreSQLContainer raumDb = new PostgreSQLContainer("postgres:18.1-alpine3.23")
            .withNetwork(network)
            .withNetworkAliases("raum-postgres")
            .withDatabaseName("raum")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("raum-init.sql");

    @SuppressWarnings("resource")
    static final PostgreSQLContainer operationalDb = new PostgreSQLContainer("postgres:18.1-alpine3.23")
            .withNetwork(network)
            .withNetworkAliases("vassago-postgres")
            .withDatabaseName("vassago")
            .withUsername("admin")
            .withPassword("adminpass")
            .withInitScript("users-test.sql");

    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withNetwork(network)
            .withNetworkAliases("redis")
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    @SuppressWarnings("resource")
    static final GenericContainer<?> openBao = new GenericContainer<>("openbao/openbao:2.5.2")
            .withNetwork(network)
            .withNetworkAliases("openbao")
            .withExposedPorts(8200)
            .withEnv("BAO_DEV_ROOT_TOKEN_ID", "dev-root-token")
            .withEnv("BAO_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
            .withCommand("server", "-dev")
            .waitingFor(Wait.forHttp("/v1/sys/health").forPort(8200)
                    .withStartupTimeout(Duration.ofSeconds(30)));

    @SuppressWarnings("resource")
    static final GenericContainer<?> openBaoInit = new GenericContainer<>("curlimages/curl:8.5.0")
            .withNetwork(network)
            .dependsOn(openBao)
            .withCommand("sh", "-c", """
                    curl -sf -X POST http://openbao:8200/v1/sys/mounts/secret \
                      -H 'X-Vault-Token: dev-root-token' \
                      -H 'Content-Type: application/json' \
                      -d '{"type":"kv","options":{"version":"2"}}' || true ;
                    curl -sf -X POST http://openbao:8200/v1/sys/mounts/database \
                      -H 'X-Vault-Token: dev-root-token' \
                      -H 'Content-Type: application/json' \
                      -d '{"type":"database"}' || true ;
                    curl -sf -X POST http://openbao:8200/v1/sys/mounts/transit \
                      -H 'X-Vault-Token: dev-root-token' \
                      -H 'Content-Type: application/json' \
                      -d '{"type":"transit"}' || true ;
                    curl -sf -X POST http://openbao:8200/v1/transit/keys/vassago-jwt \
                      -H 'X-Vault-Token: dev-root-token' \
                      -H 'Content-Type: application/json' \
                      -d '{"type":"ecdsa-p256"}' ;
                    curl -sf -X POST http://openbao:8200/v1/sys/auth/approle \
                      -H 'X-Vault-Token: dev-root-token' \
                      -H 'Content-Type: application/json' \
                      -d '{"type":"approle"}' || true ;
                    curl -sf -X POST http://openbao:8200/v1/sys/policies/acl/vassago-provisioner-policy \
                      -H 'X-Vault-Token: dev-root-token' \
                      -H 'Content-Type: application/json' \
                      -d '{"policy":"path \\"sys/policies/acl/vassago-policy\\" { capabilities = [\\"create\\",\\"read\\",\\"update\\"] } path \\"auth/approle/role/vassago\\" { capabilities = [\\"create\\",\\"read\\",\\"update\\"] } path \\"auth/approle/role/vassago/role-id\\" { capabilities = [\\"read\\"] } path \\"auth/approle/role/vassago/secret-id\\" { capabilities = [\\"update\\"] }"}' ;
                    curl -sf -X POST http://openbao:8200/v1/auth/approle/role/vassago-provisioner \
                      -H 'X-Vault-Token: dev-root-token' \
                      -H 'Content-Type: application/json' \
                      -d '{"token_policies":"vassago-provisioner-policy","token_ttl":"5m","token_max_ttl":"15m"}' ;
                    curl -sf -X POST http://openbao:8200/v1/sys/policies/acl/raum-provisioner-policy \
                      -H 'X-Vault-Token: dev-root-token' \
                      -H 'Content-Type: application/json' \
                      -d '{"policy":"path \\"sys/policies/acl/raum-service-policy\\" { capabilities = [\\"create\\",\\"read\\",\\"update\\"] } path \\"auth/approle/role/raum-service\\" { capabilities = [\\"create\\",\\"read\\",\\"update\\"] } path \\"auth/approle/role/raum-service/role-id\\" { capabilities = [\\"read\\"] } path \\"auth/approle/role/raum-service/secret-id\\" { capabilities = [\\"update\\"] }"}' ;
                    curl -sf -X POST http://openbao:8200/v1/auth/approle/role/raum-provisioner \
                      -H 'X-Vault-Token: dev-root-token' \
                      -H 'Content-Type: application/json' \
                      -d '{"token_policies":"raum-provisioner-policy","token_ttl":"5m","token_max_ttl":"15m"}' ;
                    echo OPENBAO_INIT_DONE
                    """)
            .waitingFor(Wait.forLogMessage(".*OPENBAO_INIT_DONE.*", 1)
                    .withStartupTimeout(Duration.ofSeconds(30)));

    @SuppressWarnings("resource")
    static final GenericContainer<?> raum = new GenericContainer<>("kenoma-raum:latest")
            .withNetwork(network)
            .withNetworkAliases("raum")
            .withExposedPorts(8080)
            .withEnv("RAUM_DB_HOST", "raum-postgres")
            .withEnv("RAUM_DB_PORT", "5432")
            .withEnv("RAUM_DB_NAME", "raum")
            .withEnv("RAUM_DB_USER", "postgres")
            .withEnv("RAUM_DB_PASSWORD", "postgres")
            .withEnv("OPENBAO_HOST", "http://openbao:8200")
            .withEnv("OPENBAO_KV_MOUNT", "secret")
            .withEnv("VASSAGO_BASE_URL", "http://localhost:8081")
            .withEnv("MAILGUN_API_KEY", "test-key")
            .withEnv("MAILGUN_DOMAIN", "test.example.com")
            .withEnv("MAILGUN_FROM", "noreply@test.example.com")
            .withEnv("APP_BASE_URL", "http://localhost:3000")
            .waitingFor(Wait.forHttp("/actuator/health").forPort(8080)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    static {
        raumDb.start();
        operationalDb.start();
        redis.start();
        openBao.start();
        openBaoInit.start();
    }

    protected static UUID orgId;
    protected static UUID vassagoServiceId;
    private static volatile String vassagoProvisionerRoleId;
    private static volatile String vassagoProvisionerSecretId;
    private static volatile String raumProvisionerRoleId;
    private static volatile String raumProvisionerSecretId;

    protected static final String BOOTSTRAP_USERNAME = "bootstrap_admin";
    protected static final String BOOTSTRAP_PASSWORD = "B00tstr@pPass1";
    protected static final String CHANGEPW_USERNAME = "changepw_user";
    protected static final String CHANGEPW_PASSWORD = "B00tstr@pPass1";

    private static final AtomicBoolean bootstrapped = new AtomicBoolean(false);

    @BeforeAll
    static void bootstrapOnce() throws Exception {
        if (!bootstrapped.compareAndSet(false, true)) return;

        orgId = UUID.fromString(raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                        "-t", "-A", "-c", "SELECT id FROM organizations WHERE name = 'Platform' LIMIT 1;")
                .getStdout().trim());

        vassagoServiceId = UUID.fromString(raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                        "-t", "-A", "-c", "SELECT id FROM services WHERE name = 'Vassago' LIMIT 1;")
                .getStdout().trim());

        UUID credentialId = UUID.fromString(raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                        "-t", "-A", "-c", "SELECT id FROM credentials WHERE service_id = '" + vassagoServiceId + "' LIMIT 1;")
                .getStdout().trim());

        raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                "-c", "UPDATE credentials SET db_host = 'localhost', db_port = %d WHERE id = '%s';"
                        .formatted(operationalDb.getMappedPort(5432), credentialId));

        WebClient bao = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(openBao.getMappedPort(8200)))
                .defaultHeader("X-Vault-Token", "dev-root-token")
                .build();

        bao.post().uri("/v1/secret/data/credentials/{id}", credentialId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("data", Map.of("username", "admin", "password", "adminpass")))
                .retrieve().bodyToMono(Void.class).block();

        bao.post().uri("/v1/database/config/{id}", credentialId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "plugin_name", "postgresql-database-plugin",
                        "allowed_roles", credentialId + "-role",
                        "connection_url", "postgresql://{{username}}:{{password}}@vassago-postgres:5432/vassago?sslmode=disable",
                        "username", "admin",
                        "password", "adminpass"
                ))
                .retrieve().bodyToMono(Void.class).block();

        bao.post().uri("/v1/database/roles/{role}", credentialId + "-role")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "db_name", credentialId.toString(),
                        "creation_statements", """
                                CREATE ROLE "{{name}}" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}';
                                GRANT CONNECT ON DATABASE "vassago" TO "{{name}}";
                                GRANT USAGE ON SCHEMA public TO "{{name}}";
                                GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "{{name}}";
                                """,
                        "default_ttl", "1h",
                        "max_ttl", "24h"
                ))
                .retrieve().bodyToMono(Void.class).block();

        String adminRoles = "{\"" + vassagoServiceId + "\":[\""
                + VassagoRole.VASSAGO_ADMIN.name() + "\",\""
                + VassagoRole.VASSAGO_USER.name() + "\"]}";
        String userRoles = "{\"" + vassagoServiceId + "\":[\"" + VassagoRole.VASSAGO_USER.name() + "\"]}";

        operationalDb.execInContainer("psql", "-U", "admin", "-d", "vassago",
                "-c", """
                        INSERT INTO users (name, last_name, email, username, password, roles, is_ready)
                        VALUES ('Bootstrap', 'Admin', 'admin@bootstrap.local', 'bootstrap_admin',
                                '$2a$10$xI03I5H6IoRGzfpHm4IUGOlQooxsVSVkJM3JMI4QFrJyXvR.6/gw.',
                                '%s', true)
                        ON CONFLICT (username) DO NOTHING;
                        INSERT INTO users (name, last_name, email, username, password, roles, is_ready)
                        VALUES ('Change', 'Password', 'changepw@test.local', 'changepw_user',
                                '$2a$10$xI03I5H6IoRGzfpHm4IUGOlQooxsVSVkJM3JMI4QFrJyXvR.6/gw.',
                                '%s', true)
                        ON CONFLICT (username) DO NOTHING;
                        """.formatted(adminRoles, userRoles));
    }

    protected String obtainToken(WebClient client) {
        LoginRequestDTO login = new LoginRequestDTO();
        login.setOrgId(orgId);
        login.setUsername(BOOTSTRAP_USERNAME);
        login.setPassword(BOOTSTRAP_PASSWORD);
        LoginResponseDTO response = client.post()
                .uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(login)
                .retrieve()
                .bodyToMono(LoginResponseDTO.class)
                .block();
        assertThat(response).isNotNull();
        assertThat(response.token()).isNotBlank();
        return response.token();
    }

    static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        private static final AtomicBoolean initialized = new AtomicBoolean(false);

        @Override
        public void initialize(ConfigurableApplicationContext ctx) {
            if (initialized.compareAndSet(false, true)) {
                doSetup();
            }
            injectProperties(ctx);
        }

        private static void doSetup() {
            String[] vassagoProvisionerCreds = fetchRoleIdAndSecretId(openBao.getMappedPort(8200), "vassago-provisioner");
            vassagoProvisionerRoleId = vassagoProvisionerCreds[0];
            vassagoProvisionerSecretId = vassagoProvisionerCreds[1];

            String[] raumProvisionerCreds = fetchRoleIdAndSecretId(openBao.getMappedPort(8200), "raum-provisioner");
            raumProvisionerRoleId = raumProvisionerCreds[0];
            raumProvisionerSecretId = raumProvisionerCreds[1];

            String vassagoServiceIdStr;
            String raumServiceIdStr;
            try {
                vassagoServiceIdStr = raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                                "-t", "-A", "-c", "SELECT id FROM services WHERE name = 'Vassago' LIMIT 1;")
                        .getStdout().trim();
                raumServiceIdStr = raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                                "-t", "-A", "-c", "SELECT id FROM services WHERE name = 'Raum' LIMIT 1;")
                        .getStdout().trim();
            } catch (Exception e) {
                throw new RuntimeException("Failed to read service IDs", e);
            }

            vassagoServiceId = UUID.fromString(vassagoServiceIdStr);

            raum.withEnv("RAUM_SERVICE_ID", raumServiceIdStr)
                    .withEnv("RAUM_PROVISIONER_ROLE_ID", raumProvisionerRoleId)
                    .withEnv("RAUM_PROVISIONER_SECRET_ID", raumProvisionerSecretId)
                    .withEnv("VASSAGO_SERVICE_ID", vassagoServiceIdStr)
                    .withEnv("BIME_SERVICE_ID", "00000000-0000-0000-0000-000000000099")
                    .withEnv("REDIS_HOST", "redis")
                    .withEnv("ONBOARDING_RETRY_CRON", "-");
            raum.start();
        }

        @SuppressWarnings("unchecked")
        private static String[] fetchRoleIdAndSecretId(int baoPort, String roleName) {
            WebClient client = WebClient.builder()
                    .baseUrl("http://localhost:%d".formatted(baoPort))
                    .defaultHeader("X-Vault-Token", "dev-root-token")
                    .build();

            Map<String, Object> roleIdResponse = client.get()
                    .uri("/v1/auth/approle/role/{role}/role-id", roleName)
                    .retrieve().bodyToMono(Map.class).block();
            assertThat(roleIdResponse).isNotNull();
            String roleId = (String) ((Map<String, Object>) roleIdResponse.get("data")).get("role_id");

            Map<String, Object> secretIdResponse = client.post()
                    .uri("/v1/auth/approle/role/{role}/secret-id", roleName)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of())
                    .retrieve().bodyToMono(Map.class).block();
            assertThat(secretIdResponse).isNotNull();
            String secretId = (String) ((Map<String, Object>) secretIdResponse.get("data")).get("secret_id");

            return new String[]{roleId, secretId};
        }

        private static void injectProperties(ConfigurableApplicationContext ctx) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(ctx,
                    "raum.base-url=http://localhost:%d".formatted(raum.getMappedPort(8080)),
                    "vassago.service-id=" + vassagoServiceId,
                    "openbao.base-url=http://localhost:%d".formatted(openBao.getMappedPort(8200)),
                    "vassago.jwt.transit-key-name=vassago-jwt",
                    "vassago.jwt.key-rotation-cron=-",
                    "vassago.openbao.provisioner.role-id=" + vassagoProvisionerRoleId,
                    "vassago.openbao.provisioner.secret-id=" + vassagoProvisionerSecretId,
                    "spring.data.redis.host=localhost",
                    "spring.data.redis.port=" + redis.getMappedPort(6379)
            );
        }
    }
}
