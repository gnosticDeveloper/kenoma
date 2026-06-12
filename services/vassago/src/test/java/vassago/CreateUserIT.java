package vassago;

import common.dto.CredentialsDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
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
import vassago.dto.CreateUserResponseDTO;
import vassago.dto.LoginRequestDTO;
import vassago.dto.LoginResponseDTO;
import vassago.dto.UserRequestDTO;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
            .withInitScript("users-test.sql");

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

    // Matches the seed user in users.sql
    static final String BOOTSTRAP_USERNAME = "bootstrap_admin";
    static final String BOOTSTRAP_PASSWORD = "B00tstr@pPass1";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("raum.base-url",
                () -> "http://localhost:%d".formatted(raum.getMappedPort(8080)));
        registry.add("vassago.service-id",
                () -> serviceId.toString());
        registry.add("openbao.base-url",
                () -> "http://localhost:%d".formatted(openBao.getMappedPort(8200)));
        registry.add("openbao.token", () -> "dev-root-token");
        registry.add("vassago.jwt.transit-key-name", () -> "vassago-jwt");
    }

    @BeforeAll
    static void bootstrap() throws IOException, InterruptedException {
        WebClient bao = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(openBao.getMappedPort(8200)))
                .defaultHeader("X-Vault-Token", "dev-root-token")
                .build();

        bao.post().uri("/v1/sys/mounts/secret")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "kv", "options", Map.of("version", "2")))
                .retrieve().bodyToMono(Void.class).onErrorComplete().block();

        bao.post().uri("/v1/sys/mounts/database")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "database"))
                .retrieve().bodyToMono(Void.class).onErrorComplete().block();

        bao.post().uri("/v1/sys/mounts/transit")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "transit"))
                .retrieve().bodyToMono(Void.class).onErrorComplete().block();

        bao.post().uri("/v1/transit/keys/vassago-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "ecdsa-p256"))
                .retrieve().bodyToMono(Void.class).block();

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

        raumClient.post().uri("/credentials")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(CredentialsDTO.builder()
                        .orgId(orgId)
                        .serviceId(serviceId)
                        .userName("admin")
                        .password("adminpass")
                        .dbHost("customer-postgres")
                        .dbPort(5432)
                        .dbName("customerdb")
                        .dbEngine("postgres")
                        .build())
                .retrieve().bodyToMono(Void.class).block();

        int mappedPort = customerDb.getMappedPort(5432);
        raumDb.execInContainer(
                "psql", "-U", "postgres", "-d", "raum",
                "-c", "UPDATE credentials SET db_host = 'localhost', db_port = %d WHERE org_id = '%s'"
                        .formatted(mappedPort, orgId)
        );
    }

    private String obtainToken(WebClient client) {
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

    @LocalServerPort
    int port;

    @Test
    void createUser_persistsToOrgDatabase() {
        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(port))
                .build();

        String token = obtainToken(client);

        UserRequestDTO request = new UserRequestDTO();
        request.setOrgId(orgId);
        request.setName("Jane");
        request.setLastName("Doe");
        request.setEmail("jane.doe@example.com");
        request.setUsername("janedoe");
        request.setRoles(Map.of("vassago", List.of("USER")));

        CreateUserResponseDTO response = client.post()
                .uri("/user")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(CreateUserResponseDTO.class)
                .block();

        assertThat(response).isNotNull();
        assertThat(response.getId()).isNotNull();
        assertThat(response.getName()).isEqualTo("Jane");
        assertThat(response.getLastName()).isEqualTo("Doe");
        assertThat(response.getEmail()).isEqualTo("jane.doe@example.com");
        assertThat(response.getUsername()).isEqualTo("janedoe");
        assertThat(response.getRoles()).isEqualTo(Map.of("vassago", List.of("USER")));
        assertThat(response.getTemporaryPassword()).isNotBlank();
    }

    @Test
    void createUser_poolReusesConnectionOnSecondRequest() {
        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(port))
                .build();

        String token = obtainToken(client);

        UserRequestDTO first = new UserRequestDTO();
        first.setOrgId(orgId);
        first.setName("Alice");
        first.setLastName("Smith");
        first.setEmail("alice@example.com");
        first.setUsername("alicesmith");
        first.setRoles(Map.of("vassago", List.of("USER")));

        UserRequestDTO second = new UserRequestDTO();
        second.setOrgId(orgId);
        second.setName("Bob");
        second.setLastName("Jones");
        second.setEmail("bob@example.com");
        second.setUsername("bobjones");
        second.setRoles(Map.of("vassago", List.of("USER")));

        CreateUserResponseDTO r1 = client.post().uri("/user")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(first)
                .retrieve().bodyToMono(CreateUserResponseDTO.class).block();

        CreateUserResponseDTO r2 = client.post().uri("/user")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(second)
                .retrieve().bodyToMono(CreateUserResponseDTO.class).block();

        assertThat(r1).isNotNull();
        assertThat(r2).isNotNull();
        assertThat(r1.getId()).isNotEqualTo(r2.getId());
    }
}