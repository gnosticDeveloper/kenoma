package raum;

import common.dto.BasicCredentialDTO;
import common.dto.CredentialsDTO;
import common.security.JwtValidator;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import raum.dto.OrgRequestDTO;
import raum.dto.OrgResponseDTO;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ContextConfiguration(initializers = EphemeralCredentialsIT.Initializer.class)
class EphemeralCredentialsIT {

    static final Network network = Network.newNetwork();

    @Container
    static final PostgreSQLContainer raumDb = new PostgreSQLContainer("postgres:18.1-alpine3.23")
            .withNetwork(network)
            .withNetworkAliases("raum-postgres")
            .withDatabaseName("raum")
            .withUsername("postgres")
            .withPassword("postgres");

    @Container
    static final PostgreSQLContainer operationalDb = new PostgreSQLContainer("postgres:18.1-alpine3.23")
            .withNetwork(network)
            .withNetworkAliases("vassago-postgres")
            .withDatabaseName("vassago")
            .withUsername("admin")
            .withPassword("adminpass");

    @Container
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

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    @Container
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
                      -d '{"type":"ecdsa-p256"}' || true ;
                    curl -sf -X POST http://openbao:8200/v1/sys/auth/approle \
                      -H 'X-Vault-Token: dev-root-token' \
                      -H 'Content-Type: application/json' \
                      -d '{"type":"approle"}' || true ;
                    curl -sf -X POST http://openbao:8200/v1/sys/policies/acl/vassago-policy \
                      -H 'X-Vault-Token: dev-root-token' \
                      -H 'Content-Type: application/json' \
                      -d '{"policy":"path \\"database/creds/*\\" { capabilities = [\\"read\\"] } path \\"transit/sign/vassago-jwt\\" { capabilities = [\\"update\\"] } path \\"transit/keys/vassago-jwt\\" { capabilities = [\\"read\\"] }"}' ;
                    curl -sf -X POST http://openbao:8200/v1/auth/approle/role/vassago \
                      -H 'X-Vault-Token: dev-root-token' \
                      -H 'Content-Type: application/json' \
                      -d '{"token_policies":"vassago-policy","token_ttl":"1h","token_max_ttl":"24h"}' ;
                    curl -sf -X POST http://openbao:8200/v1/sys/policies/acl/raum-policy \
                      -H 'X-Vault-Token: dev-root-token' \
                      -H 'Content-Type: application/json' \
                      -d '{"policy":"path \\"transit/keys/vassago-jwt\\" { capabilities = [\\"read\\"] }"}' ;
                    curl -sf -X POST http://openbao:8200/v1/auth/approle/role/raum \
                      -H 'X-Vault-Token: dev-root-token' \
                      -H 'Content-Type: application/json' \
                      -d '{"token_policies":"raum-policy","token_ttl":"1h","token_max_ttl":"24h"}' ;
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

    static String vassagoToken;
    static String raumToken;
    static String raumProvisionerRoleId;
    static String raumProvisionerSecretId;
    static UUID credentialId;
    static UUID orgId;
    static UUID serviceId;
    static UUID raumServiceId;

    @MockitoBean
    JwtValidator jwtValidator;

    static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext ctx) {
            vassagoToken = loginAppRole(openBao.getMappedPort(8200), "vassago");
            raumToken = loginAppRole(openBao.getMappedPort(8200), "raum");
            assertThat(vassagoToken).isNotBlank();
            assertThat(raumToken).isNotBlank();

            String[] raumProvisionerCreds = fetchRoleIdAndSecretId(openBao.getMappedPort(8200), "raum-provisioner");
            raumProvisionerRoleId = raumProvisionerCreds[0];
            raumProvisionerSecretId = raumProvisionerCreds[1];

            String raumServiceIdStr;
            String vassagoServiceIdStr;
            String bimeServiceIdStr;
            try {
                raumServiceIdStr = raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                                "-t", "-A", "-c", "SELECT id FROM services WHERE name = 'Raum' LIMIT 1;")
                        .getStdout().trim();
                vassagoServiceIdStr = raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                                "-t", "-A", "-c", "SELECT id FROM services WHERE name = 'Vassago' LIMIT 1;")
                        .getStdout().trim();
                bimeServiceIdStr = raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                                "-t", "-A", "-c", "SELECT id FROM services WHERE name = 'Bime' LIMIT 1;")
                        .getStdout().trim();
            } catch (Exception e) {
                throw new RuntimeException("Failed to read service IDs", e);
            }

            raumServiceId = UUID.fromString(raumServiceIdStr);

            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(ctx,
                    "spring.r2dbc.url=r2dbc:postgresql://localhost:%d/raum".formatted(raumDb.getMappedPort(5432)),
                    "spring.r2dbc.username=postgres",
                    "spring.r2dbc.password=postgres",
                    "openbao.host=http://localhost:%d".formatted(openBao.getMappedPort(8200)),
                    "openbao.provisioner.role-id=" + raumProvisionerRoleId,
                    "openbao.provisioner.secret-id=" + raumProvisionerSecretId,
                    "openbao.kv.mount=secret",
                    "RAUM_SERVICE_ID=" + raumServiceIdStr,
                    "RAUM_JWT_TRANSIT_KEY_NAME=vassago-jwt",
                    "vassago.service-id=" + vassagoServiceIdStr,
                    "bime.service-id=" + bimeServiceIdStr,
                    "spring.data.redis.host=localhost",
                    "spring.data.redis.port=" + redis.getMappedPort(6379),
                    "vassago.jwt.public-key-refresh-cron=-",
                    "raum.onboarding.retry-cron=-",
                    "raum.billing.deadline-cron=-",
                    "mailgun.api-key=test-key",
                    "mailgun.domain=test.example.com",
                    "mailgun.from=noreply@test.example.com",
                    "app.base-url=http://localhost:3000",
                    "spring.flyway.enabled=false"
            );
        }
    }

    @BeforeAll
    static void setup() throws Exception {
        // @Container-managed, so raumDb is only guaranteed started (not yet migrated) by this point -
        // this must run before the psql queries below, and before Initializer.initialize() (which
        // JUnit/Spring don't run until just before the first @Test, i.e. after this @BeforeAll).
        TestMigrations.migrate(raumDb, "raum");

        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(openBao.getMappedPort(8200)))
                .defaultHeader("X-Vault-Token", "dev-root-token")
                .build();

        credentialId = UUID.fromString(raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                        "-t", "-A", "-c", "SELECT id FROM credentials LIMIT 1;")
                .getStdout().trim());
        orgId = UUID.fromString(raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                        "-t", "-A", "-c", "SELECT org_id FROM credentials WHERE id = '" + credentialId + "';")
                .getStdout().trim());
        serviceId = UUID.fromString(raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                        "-t", "-A", "-c", "SELECT service_id FROM credentials WHERE id = '" + credentialId + "';")
                .getStdout().trim());

        raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                "-c", "UPDATE credentials SET db_port = %d WHERE id = '%s';"
                        .formatted(operationalDb.getMappedPort(5432), credentialId));

        client.post().uri("/v1/secret/data/credentials/{id}", credentialId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("data", Map.of("username", "admin", "password", "adminpass")))
                .retrieve().bodyToMono(Void.class).block();

        client.post().uri("/v1/database/config/{id}", credentialId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "plugin_name", "postgresql-database-plugin",
                        "allowed_roles", credentialId + "-admin-role," + credentialId + "-member-role",
                        "connection_url", "postgresql://{{username}}:{{password}}@vassago-postgres:5432/vassago?sslmode=disable",
                        "username", "admin",
                        "password", "adminpass"
                ))
                .retrieve().bodyToMono(Void.class).block();

        client.post().uri("/v1/database/roles/{role}", credentialId + "-admin-role")
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

        client.post().uri("/v1/database/roles/{role}", credentialId + "-member-role")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "db_name", credentialId.toString(),
                        "creation_statements", """
                                CREATE ROLE "{{name}}" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}';
                                GRANT CONNECT ON DATABASE "vassago" TO "{{name}}";
                                GRANT USAGE ON SCHEMA public TO "{{name}}";
                                GRANT SELECT ON ALL TABLES IN SCHEMA public TO "{{name}}";
                                """,
                        "default_ttl", "1h",
                        "max_ttl", "24h"
                ))
                .retrieve().bodyToMono(Void.class).block();
    }

    @SuppressWarnings("unchecked")
    private static String loginAppRole(int baoPort, String roleName) {
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

        Map<String, Object> loginResponse = client.post()
                .uri("/v1/auth/approle/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("role_id", roleId, "secret_id", secretId))
                .retrieve().bodyToMono(Map.class).block();
        assertThat(loginResponse).isNotNull();
        return (String) ((Map<String, Object>) loginResponse.get("auth")).get("client_token");
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

    @SuppressWarnings("unchecked")
    private void mockAdminJwt() {
        Claims claims = mock(Claims.class);
        String rolesJson = "{\"" + raumServiceId + "\":[\"RAUM_ADMIN\"]}";
        when(claims.getSubject()).thenReturn("test-admin");
        when(claims.get(eq("orgId"), eq(String.class))).thenReturn(orgId.toString());
        when(claims.get(eq("roles"), eq(String.class))).thenReturn(rolesJson);
        when(jwtValidator.validateToken(anyString())).thenReturn(reactor.core.publisher.Mono.just(claims));
    }

    @LocalServerPort
    int port;

    @Test
    void ephemeralCredentialsAreIssuedAndValid() {
        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(port))
                .build();

        CredentialsDTO ephemeral = client.post()
                .uri("/credentials/ephemeral")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Vault-Token", vassagoToken)
                .bodyValue(BasicCredentialDTO.builder()
                        .orgId(orgId)
                        .serviceId(serviceId)
                        .build())
                .retrieve()
                .bodyToMono(CredentialsDTO.class)
                .block();

        assertThat(ephemeral).isNotNull();
        assertThat(ephemeral.getUserName()).isNotBlank();
        assertThat(ephemeral.getPassword()).isNotBlank();
        assertThat(ephemeral.getDbHost()).isEqualTo("vassago-postgres");
        assertThat(ephemeral.getDbName()).isEqualTo("vassago");

        String jdbcUrl = "jdbc:postgresql://localhost:%d/vassago"
                .formatted(operationalDb.getMappedPort(5432));
        assertThatNoException().isThrownBy(() -> {
            try (Connection conn = DriverManager.getConnection(
                    jdbcUrl, ephemeral.getUserName(), ephemeral.getPassword())) {
                assertThat(conn.isValid(2)).isTrue();
            }
        });
    }

    @Test
    void ephemeralCredentials_withAdminJwt_succeeds() {
        mockAdminJwt();
        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(port))
                .build();

        CredentialsDTO ephemeral = client.post()
                .uri("/credentials/ephemeral")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(BasicCredentialDTO.builder()
                        .orgId(orgId)
                        .serviceId(serviceId)
                        .build())
                .retrieve()
                .bodyToMono(CredentialsDTO.class)
                .block();

        assertThat(ephemeral).isNotNull();
        assertThat(ephemeral.getUserName()).isNotBlank();
    }

    @Test
    void ephemeralCredentials_rejectsJwtWithoutCredentialManage() {
        // A JWT that's authenticated but holds no CREDENTIAL_MANAGE-granting role (e.g. a plain
        // onboarding-scoped operator) must not be able to pull any org's ephemeral DB credentials
        // just by being logged in — this was the actual vulnerability: only isAuthenticated() was
        // checked, so any valid JWT from any org/role could fetch any other org's credentials.
        Claims claims = mock(Claims.class);
        String rolesJson = "{\"" + raumServiceId + "\":[\"RAUM_ONBOARDING\"]}";
        when(claims.getSubject()).thenReturn("test-onboarding-only");
        when(claims.get(eq("orgId"), eq(String.class))).thenReturn(orgId.toString());
        when(claims.get(eq("roles"), eq(String.class))).thenReturn(rolesJson);
        when(jwtValidator.validateToken(anyString())).thenReturn(reactor.core.publisher.Mono.just(claims));

        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(port))
                .build();

        assertThatThrownBy(() -> client.post()
                .uri("/credentials/ephemeral")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(BasicCredentialDTO.builder()
                        .orgId(orgId)
                        .serviceId(serviceId)
                        .build())
                .retrieve()
                .bodyToMono(CredentialsDTO.class)
                .block())
                .isInstanceOf(WebClientResponseException.Forbidden.class);
    }

    @Test
    void saveCredentials_registersInOpenBaoAndDb() {
        mockAdminJwt();
        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(port))
                .build();

        OrgResponseDTO newOrg = client.post().uri("/orgs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new OrgRequestDTO("SaveCreds Org", "savecreds@test.com", "Test Admin"))
                .retrieve()
                .bodyToMono(OrgResponseDTO.class)
                .block();

        assertThat(newOrg).isNotNull();

        CredentialsDTO dto = CredentialsDTO.builder()
                .orgId(newOrg.getId())
                .serviceId(serviceId)
                .userName("admin")
                .password("adminpass")
                .dbHost("vassago-postgres")
                .dbPort(5432)
                .dbName("vassago")
                .dbEngine("postgres")
                .build();

        BasicCredentialDTO result = client.post()
                .uri("/credentials")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .retrieve()
                .bodyToMono(BasicCredentialDTO.class)
                .block();

        assertThat(result).isNotNull();
        assertThat(result.getOrgId()).isEqualTo(newOrg.getId());
        assertThat(result.getServiceId()).isEqualTo(serviceId);
    }

    /**
     * Regression test for the org-deactivation lease-revocation gap: {@code OrganizationService.
     * deleteOrg} calls {@code OpenBaoService.revokeAllLeasesForCredential}, which needs
     * {@code sys/leases/revoke-prefix/database/creds/*} on raum's own OpenBao AppRole policy
     * ({@code raum-service-policy.hcl}). That capability was missing entirely at first - the code
     * compiled and ran without error (the revoke call's failure is swallowed via onErrorResume, by
     * design, so deactivation itself never fails), but Vault silently 403'd every revoke attempt and
     * the underlying Postgres role never actually got dropped.
     *
     * <p>This is deliberately NOT a unit test with a mocked OpenBaoService - a mock can't catch a
     * missing real-world Vault ACL grant. This class is the one IT suite where raum's own
     * OpenBaoService authenticates as the real, policy-restricted {@code raum-service} AppRole
     * (not the root token {@code OnboardingIT} uses via a mocked {@code OpenBaoProvisioner}), so a
     * regression here - re-missing the policy stanza, or Vault's sudo-path rules changing - would
     * make this test fail with a real 403 surfacing as the org's DB role staying alive after
     * deactivation, exactly like the bug that prompted this test.
     */
    @Test
    void deleteOrg_revokesLeaseUsingRaumsOwnRestrictedOpenBaoToken() throws Exception {
        mockAdminJwt();
        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(port))
                .build();

        OrgResponseDTO newOrg = client.post().uri("/orgs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new OrgRequestDTO("Deactivate Cascade Org", "cascade@test.com", "Test Admin"))
                .retrieve()
                .bodyToMono(OrgResponseDTO.class)
                .block();
        assertThat(newOrg).isNotNull();

        BasicCredentialDTO registered = client.post().uri("/credentials")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(CredentialsDTO.builder()
                        .orgId(newOrg.getId())
                        .serviceId(serviceId)
                        .userName("admin")
                        .password("adminpass")
                        .dbHost("vassago-postgres")
                        .dbPort(5432)
                        .dbName("vassago")
                        .dbEngine("postgres")
                        .build())
                .retrieve()
                .bodyToMono(BasicCredentialDTO.class)
                .block();
        assertThat(registered).isNotNull();

        CredentialsDTO ephemeral = client.post().uri("/credentials/ephemeral")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Vault-Token", vassagoToken)
                .bodyValue(BasicCredentialDTO.builder()
                        .orgId(newOrg.getId())
                        .serviceId(serviceId)
                        .build())
                .retrieve()
                .bodyToMono(CredentialsDTO.class)
                .block();
        assertThat(ephemeral).isNotNull();

        String jdbcUrl = "jdbc:postgresql://localhost:%d/vassago".formatted(operationalDb.getMappedPort(5432));
        try (Connection conn = DriverManager.getConnection(jdbcUrl, ephemeral.getUserName(), ephemeral.getPassword())) {
            assertThat(conn.isValid(2)).isTrue();
        }

        client.delete().uri("/orgs/{id}", newOrg.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .retrieve()
                .bodyToMono(Void.class)
                .block();

        assertThatThrownBy(() -> DriverManager.getConnection(jdbcUrl, ephemeral.getUserName(), ephemeral.getPassword()))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void saveCredentials_rejectsSelf() {
        mockAdminJwt();
        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(port))
                .build();

        CredentialsDTO dto = CredentialsDTO.builder()
                .orgId(orgId)
                .serviceId(raumServiceId)
                .userName("admin")
                .password("adminpass")
                .dbHost("vassago-postgres")
                .dbPort(5432)
                .dbName("vassago")
                .dbEngine("postgres")
                .build();

        assertThatThrownBy(() -> client.post()
                .uri("/credentials")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .retrieve()
                .bodyToMono(BasicCredentialDTO.class)
                .block())
                .isInstanceOf(WebClientResponseException.Forbidden.class);
    }

    @Test
    void activeOrgIds_returnsIdsForValidVaultToken() {
        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(port))
                .build();

        List<UUID> ids = client.get()
                .uri("/orgs/active-ids")
                .header("X-Vault-Token", vassagoToken)
                .retrieve()
                .bodyToFlux(UUID.class)
                .collectList()
                .block();

        assertThat(ids).isNotNull();
        assertThat(ids).contains(orgId);
    }

    // Adversarial: any valid AppRole token authenticates — not just Vassago's — since this
    // endpoint (like /credentials/ephemeral) trusts any live vault token, not a specific AppRole.
    @Test
    void activeOrgIds_acceptsAnyValidAppRoleToken_notJustVassago() {
        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(port))
                .build();

        List<UUID> ids = client.get()
                .uri("/orgs/active-ids")
                .header("X-Vault-Token", raumToken)
                .retrieve()
                .bodyToFlux(UUID.class)
                .collectList()
                .block();

        assertThat(ids).isNotNull();
        assertThat(ids).contains(orgId);
    }

    @Test
    void activeOrgIds_rejectsMissingVaultToken() {
        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(port))
                .build();

        assertThatThrownBy(() -> client.get()
                .uri("/orgs/active-ids")
                .retrieve()
                .bodyToFlux(UUID.class)
                .collectList()
                .block())
                .isInstanceOf(WebClientResponseException.Unauthorized.class);
    }

    @Test
    void activeOrgIds_rejectsInvalidVaultToken() {
        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(port))
                .build();

        assertThatThrownBy(() -> client.get()
                .uri("/orgs/active-ids")
                .header("X-Vault-Token", "not-a-real-token")
                .retrieve()
                .bodyToFlux(UUID.class)
                .collectList()
                .block())
                .isInstanceOf(WebClientResponseException.Unauthorized.class);
    }

    // Adversarial: a caller's own user JWT (not a machine vault token) must not work either —
    // this endpoint is machine-to-machine only, unlike /credentials/ephemeral which accepts both.
    @Test
    void activeOrgIds_rejectsUserJwt_vaultTokenOnly() {
        mockAdminJwt();
        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(port))
                .build();

        assertThatThrownBy(() -> client.get()
                .uri("/orgs/active-ids")
                .header(HttpHeaders.AUTHORIZATION.toString(), "Bearer test-token")
                .retrieve()
                .bodyToFlux(UUID.class)
                .collectList()
                .block())
                .isInstanceOf(WebClientResponseException.Unauthorized.class);
    }

}
