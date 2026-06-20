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
import java.time.Duration;
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
            .withPassword("postgres")
            .withInitScript("init.sql");

    @Container
    static final PostgreSQLContainer operationalDb = new PostgreSQLContainer("postgres:18.1-alpine3.23")
            .withNetwork(network)
            .withNetworkAliases("operational-postgres")
            .withDatabaseName("operationaldb")
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
                    echo OPENBAO_INIT_DONE
                    """)
            .waitingFor(Wait.forLogMessage(".*OPENBAO_INIT_DONE.*", 1)
                    .withStartupTimeout(Duration.ofSeconds(30)));

    static String vassagoToken;
    static String raumToken;
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

            String raumServiceIdStr;
            try {
                raumServiceIdStr = raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                                "-t", "-A", "-c", "SELECT id FROM services WHERE name = 'Raum' LIMIT 1;")
                        .getStdout().trim();
            } catch (Exception e) {
                throw new RuntimeException("Failed to read Raum service ID", e);
            }

            raumServiceId = UUID.fromString(raumServiceIdStr);

            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(ctx,
                    "spring.r2dbc.url=r2dbc:postgresql://localhost:%d/raum".formatted(raumDb.getMappedPort(5432)),
                    "spring.r2dbc.username=postgres",
                    "spring.r2dbc.password=postgres",
                    "openbao.host=http://localhost:%d".formatted(openBao.getMappedPort(8200)),
                    "openbao.token=dev-root-token",
                    "openbao.kv.mount=secret",
                    "RAUM_SERVICE_ID=" + raumServiceIdStr,
                    "RAUM_OPENBAO_TOKEN=" + raumToken,
                    "RAUM_JWT_TRANSIT_KEY_NAME=vassago-jwt"
            );
        }
    }

    @BeforeAll
    static void setup() throws Exception {
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
                        "allowed_roles", credentialId + "-role",
                        "connection_url", "postgresql://{{username}}:{{password}}@operational-postgres:5432/operationaldb?sslmode=disable",
                        "username", "admin",
                        "password", "adminpass"
                ))
                .retrieve().bodyToMono(Void.class).block();

        client.post().uri("/v1/database/roles/{role}", credentialId + "-role")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "db_name", credentialId.toString(),
                        "creation_statements", """
                                CREATE ROLE "{{name}}" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}';
                                GRANT CONNECT ON DATABASE "operationaldb" TO "{{name}}";
                                GRANT USAGE ON SCHEMA public TO "{{name}}";
                                GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "{{name}}";
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
        assertThat(ephemeral.getDbHost()).isEqualTo("operational-postgres");
        assertThat(ephemeral.getDbName()).isEqualTo("operationaldb");

        String jdbcUrl = "jdbc:postgresql://localhost:%d/operationaldb"
                .formatted(operationalDb.getMappedPort(5432));
        assertThatNoException().isThrownBy(() -> {
            try (Connection conn = DriverManager.getConnection(
                    jdbcUrl, ephemeral.getUserName(), ephemeral.getPassword())) {
                assertThat(conn.isValid(2)).isTrue();
            }
        });
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
                .dbHost("operational-postgres")
                .dbPort(5432)
                .dbName("operationaldb")
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
                .dbHost("operational-postgres")
                .dbPort(5432)
                .dbName("operationaldb")
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
    void testDb_returnsTrue() {
        mockAdminJwt();
        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(port))
                .build();

        Boolean result = client.get()
                .uri("/credentials/test-db")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .retrieve()
                .bodyToMono(Boolean.class)
                .block();

        assertThat(result).isTrue();
    }
}
