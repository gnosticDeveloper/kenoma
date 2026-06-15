package raum;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import common.dto.BasicCredentialDTO;
import common.dto.CredentialsDTO;
import raum.dto.OrgRequestDTO;
import raum.dto.OrgResponseDTO;
import raum.dto.ServiceRequestDTO;
import raum.dto.ServiceResponseDTO;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
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
    static final PostgreSQLContainer customerDb = new PostgreSQLContainer("postgres:18.1-alpine3.23")
            .withNetwork(network)
            .withNetworkAliases("customer-postgres")
            .withDatabaseName("customerdb")
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

    static String vassagoToken;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () ->
                "r2dbc:postgresql://localhost:%d/raum".formatted(raumDb.getMappedPort(5432)));
        registry.add("spring.r2dbc.username", raumDb::getUsername);
        registry.add("spring.r2dbc.password", raumDb::getPassword);
        registry.add("openbao.host", () ->
                "http://localhost:%d".formatted(openBao.getMappedPort(8200)));
        registry.add("openbao.token", () -> "dev-root-token");
        registry.add("openbao.kv.mount", () -> "secret");
    }

    @BeforeAll
    static void initOpenBao() {
        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(openBao.getMappedPort(8200)))
                .defaultHeader("X-Vault-Token", "dev-root-token")
                .build();

        // Enable engines
        client.post().uri("/v1/sys/mounts/secret")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "kv", "options", Map.of("version", "2")))
                .retrieve().bodyToMono(Void.class).onErrorComplete().block();

        client.post().uri("/v1/sys/mounts/database")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "database"))
                .retrieve().bodyToMono(Void.class).onErrorComplete().block();

        // Enable AppRole
        client.post().uri("/v1/sys/auth/approle")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "approle"))
                .retrieve().bodyToMono(Void.class).onErrorComplete().block();

        // Write policy
        client.post().uri("/v1/sys/policies/acl/vassago-policy")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("policy", """
                        path "database/creds/*" {
                          capabilities = ["read"]
                        }
                        """))
                .retrieve().bodyToMono(Void.class).block();

        // Create role
        client.post().uri("/v1/auth/approle/role/vassago")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "token_policies", "vassago-policy",
                        "token_ttl", "1h",
                        "token_max_ttl", "24h"
                ))
                .retrieve().bodyToMono(Void.class).block();

        // Get role_id
        @SuppressWarnings("unchecked")
        Map<String, Object> roleIdResponse = client.get()
                .uri("/v1/auth/approle/role/vassago/role-id")
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        assertThat(roleIdResponse).isNotNull();
        @SuppressWarnings("unchecked")
        String roleId = (String) ((Map<String, Object>) roleIdResponse.get("data")).get("role_id");

        // Get secret_id
        @SuppressWarnings("unchecked")
        Map<String, Object> secretIdResponse = client.post()
                .uri("/v1/auth/approle/role/vassago/secret-id")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        assertThat(secretIdResponse).isNotNull();
        @SuppressWarnings("unchecked")
        String secretId = (String) ((Map<String, Object>) secretIdResponse.get("data")).get("secret_id");

        // Login
        @SuppressWarnings("unchecked")
        Map<String, Object> loginResponse = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(openBao.getMappedPort(8200)))
                .build()
                .post()
                .uri("/v1/auth/approle/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("role_id", roleId, "secret_id", secretId))
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        assertThat(loginResponse).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> auth = (Map<String, Object>) loginResponse.get("auth");
        vassagoToken = (String) auth.get("client_token");
        assertThat(vassagoToken).isNotBlank();
    }

    @LocalServerPort
    int port;

    @Test
    void ephemeralCredentialsAreIssuedAndValid() {
        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(port))
                .build();

        OrgResponseDTO org = client.post()
                .uri("/orgs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new OrgRequestDTO("Test Org", "test@example.com", "Test User"))
                .retrieve()
                .bodyToMono(OrgResponseDTO.class)
                .block();
        assertThat(org).isNotNull();
        assertThat(org.getId()).isNotNull();

        ServiceResponseDTO service = client.post()
                .uri("/services")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ServiceRequestDTO("Test Service", "Integration test service"))
                .retrieve()
                .bodyToMono(ServiceResponseDTO.class)
                .block();
        assertThat(service).isNotNull();
        assertThat(service.getId()).isNotNull();

        BasicCredentialDTO saved = client.post()
                .uri("/credentials")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(CredentialsDTO.builder()
                        .orgId(org.getId())
                        .serviceId(service.getId())
                        .userName("admin")
                        .password("adminpass")
                        .dbHost("customer-postgres")
                        .dbPort(5432)
                        .dbName("customerdb")
                        .dbEngine("postgres")
                        .build())
                .retrieve()
                .bodyToMono(BasicCredentialDTO.class)
                .block();
        assertThat(saved).isNotNull();
        assertThat(saved.getOrgId()).isEqualTo(org.getId());
        assertThat(saved.getServiceId()).isEqualTo(service.getId());

        CredentialsDTO ephemeral = client.post()
                .uri("/credentials/ephemeral")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Vault-Token", vassagoToken)
                .bodyValue(saved)
                .retrieve()
                .bodyToMono(CredentialsDTO.class)
                .block();
        assertThat(ephemeral).isNotNull();
        assertThat(ephemeral.getUserName()).isNotBlank();
        assertThat(ephemeral.getPassword()).isNotBlank();
        assertThat(ephemeral.getDbHost()).isEqualTo("customer-postgres");
        assertThat(ephemeral.getDbName()).isEqualTo("customerdb");

        String jdbcUrl = "jdbc:postgresql://localhost:%d/customerdb"
                .formatted(customerDb.getMappedPort(5432));
        assertThatNoException().isThrownBy(() -> {
            try (Connection conn = DriverManager.getConnection(
                    jdbcUrl, ephemeral.getUserName(), ephemeral.getPassword())) {
                assertThat(conn.isValid(2)).isTrue();
            }
        });
    }
}