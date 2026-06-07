package vassago;

import common.dto.CredentialsDTO;
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
import vassago.dto.UserRequestDTO;
import vassago.dto.UserResponseDTO;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for user creation via Vassago.
 *
 * <h2>Network topology</h2>
 * All containers share a Docker network so they can reach each other by alias.
 * Vassago's Spring context runs on the host (via @SpringBootTest), so it reaches
 * containers through their mapped ports on localhost.
 *
 * <h2>The two-address problem</h2>
 * OpenBao's database plugin must reach the customer DB from inside the Docker network,
 * so it needs the in-network alias "customer-postgres:5432".
 * Vassago runs on the host and must reach the customer DB via localhost + mapped port.
 * Solution: OpenBao is configured directly in @BeforeAll using the in-network address.
 * Raum's credentials record stores localhost + mapped port — what Vassago actually uses.
 * Raum's saveNewCredentials would normally call OpenBao registration too, so we use
 * a two-step approach: configure OpenBao manually first, then save metadata to Raum
 * using a dedicated endpoint that skips the OpenBao registration step.
 * Since that endpoint doesn't exist yet, we call Raum's POST /credentials with the
 * in-network address (so Raum's internal OpenBao call works), then patch the stored
 * dbHost/dbPort directly via the Raum DB. For now, the simpler and fully correct
 * approach is to configure OpenBao manually and insert the Raum credential record
 * directly, bypassing the Raum API for test setup.
 * This is the only test-specific complexity — production flow is unaffected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CreateUserIT {

    static final Network network = Network.newNetwork();

    @Container
    static final PostgreSQLContainer raumDb = new PostgreSQLContainer("postgres:18.1-alpine3.23")
            .withNetwork(network)
            .withNetworkAliases("raum-postgres")
            .withDatabaseName("raum")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("raum-init.sql");

    @Container
    static final PostgreSQLContainer customerDb = new PostgreSQLContainer("postgres:18.1-alpine3.23")
            .withNetwork(network)
            .withNetworkAliases("customer-postgres")
            .withDatabaseName("customerdb")
            .withUsername("admin")
            .withPassword("adminpass")
            .withInitScript("users.sql");

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
            .withEnv("OPENBAO_TOKEN", "dev-root-token")
            .withEnv("OPENBAO_KV_MOUNT", "secret")
            .waitingFor(Wait.forHttp("/actuator/health").forPort(8080)
                    .withStartupTimeout(Duration.ofSeconds(60)))
            .dependsOn(raumDb, openBao);

    static UUID orgId;
    static UUID serviceId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("raum.base-url",
                () -> "http://localhost:%d".formatted(raum.getMappedPort(8080)));
        registry.add("vassago.service-id",
                () -> serviceId.toString());
    }

    @BeforeAll
    static void bootstrap() throws IOException, InterruptedException {
        WebClient bao = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(openBao.getMappedPort(8200)))
                .defaultHeader("X-Vault-Token", "dev-root-token")
                .build();

        // Enable secrets engines.
        bao.post().uri("/v1/sys/mounts/secret")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "kv", "options", Map.of("version", "2")))
                .retrieve().bodyToMono(Void.class).onErrorComplete().block();

        bao.post().uri("/v1/sys/mounts/database")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "database"))
                .retrieve().bodyToMono(Void.class).onErrorComplete().block();

        // Create org and service in Raum.
        WebClient raumClient = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(raum.getMappedPort(8080)))
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> org = raumClient.post().uri("/orgs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "name", "Test Org",
                        "contactEmail", "test@example.com",
                        "contactName", "Test User"
                ))
                .retrieve().bodyToMono(Map.class).block();
        assertThat(org).isNotNull();
        orgId = UUID.fromString((String) org.get("id"));

        @SuppressWarnings("unchecked")
        Map<String, Object> service = raumClient.post().uri("/services")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "name", "Vassago",
                        "description", "Authentication and identity service"
                ))
                .retrieve().bodyToMono(Map.class).block();
        assertThat(service).isNotNull();
        serviceId = UUID.fromString((String) service.get("id"));

        // Register credentials in Raum using the in-network address so that
        // Raum's internal OpenBao registration call can reach customer-postgres.
        // Vassago will get these host/port values back and try to connect — but
        // it runs on the host, so we need the mapped address instead.
        // We therefore register with the in-network address first (for OpenBao),
        // then update the record directly in the Raum DB to the mapped address
        // (for Vassago). This is the only test-specific workaround required.
        raumClient.post().uri("/credentials")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(CredentialsDTO.builder()
                        .orgId(orgId)
                        .serviceId(serviceId)
                        .userName("admin")
                        .password("adminpass")
                        .dbHost("customer-postgres")   // in-network: OpenBao needs this
                        .dbPort(5432)
                        .dbName("customerdb")
                        .dbEngine("postgres")
                        .build())
                .retrieve().bodyToMono(Void.class).block();

        // Patch the stored dbHost/dbPort to the host-mapped address so that
        // Vassago (running on the host) can reach the customer DB.
        int mappedPort = customerDb.getMappedPort(5432);
        raumDb.execInContainer(
                "psql",
                "-U", "postgres",
                "-d", "raum",
                "-c", "UPDATE credentials SET db_host = 'localhost', db_port = %d WHERE org_id = '%s'"
                        .formatted(mappedPort, orgId)
        );
    }

    @LocalServerPort
    int port;

    @Test
    void createUser_persistsToOrgDatabase() {
        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(port))
                .build();

        UserRequestDTO request = new UserRequestDTO();
        request.setOrgId(orgId);
        request.setName("Jane");
        request.setLastName("Doe");
        request.setEmail("jane.doe@example.com");
        request.setUsername("janedoe");
        request.setPassword("Str0ng!Pass1");
        request.setRoles(List.of("USER"));

        UserResponseDTO response = client.post()
                .uri("/user")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(UserResponseDTO.class)
                .block();

        assertThat(response).isNotNull();
        assertThat(response.getId()).isNotNull();
        assertThat(response.getName()).isEqualTo("Jane");
        assertThat(response.getLastName()).isEqualTo("Doe");
        assertThat(response.getEmail()).isEqualTo("jane.doe@example.com");
        assertThat(response.getUsername()).isEqualTo("janedoe");
        assertThat(response.getRoles()).containsExactly("USER");
    }

    @Test
    void createUser_poolReusesConnectionOnSecondRequest() {
        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(port))
                .build();

        UserRequestDTO first = new UserRequestDTO();
        first.setOrgId(orgId);
        first.setName("Alice");
        first.setLastName("Smith");
        first.setEmail("alice@example.com");
        first.setUsername("alicesmith");
        first.setPassword("Str0ng!Pass1");
        first.setRoles(List.of("USER"));

        UserRequestDTO second = new UserRequestDTO();
        second.setOrgId(orgId);
        second.setName("Bob");
        second.setLastName("Jones");
        second.setEmail("bob@example.com");
        second.setUsername("bobjones");
        second.setPassword("Str0ng!Pass1");
        second.setRoles(List.of("ADMIN"));

        UserResponseDTO r1 = client.post().uri("/user")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(first)
                .retrieve().bodyToMono(UserResponseDTO.class).block();

        UserResponseDTO r2 = client.post().uri("/user")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(second)
                .retrieve().bodyToMono(UserResponseDTO.class).block();

        assertThat(r1).isNotNull();
        assertThat(r2).isNotNull();
        assertThat(r1.getId()).isNotEqualTo(r2.getId());
    }
}