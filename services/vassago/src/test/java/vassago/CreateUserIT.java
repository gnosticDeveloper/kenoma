package vassago;

import common.dto.CredentialsDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;
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
import vassago.security.VassagoRole;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ContextConfiguration(initializers = CreateUserIT.Initializer.class)
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
                curl -sf -X POST http://openbao:8200/v1/sys/policies/acl/vassago-policy \
                  -H 'X-Vault-Token: dev-root-token' \
                  -H 'Content-Type: application/json' \
                  -d '{"policy":"path \\"database/creds/*\\" { capabilities = [\\"read\\"] } path \\"transit/sign/vassago-jwt\\" { capabilities = [\\"update\\"] } path \\"transit/keys/vassago-jwt\\" { capabilities = [\\"read\\"] }"}' ;
                curl -sf -X POST http://openbao:8200/v1/auth/approle/role/vassago \
                  -H 'X-Vault-Token: dev-root-token' \
                  -H 'Content-Type: application/json' \
                  -d '{"token_policies":"vassago-policy","token_ttl":"1h","token_max_ttl":"24h"}' ;
                echo OPENBAO_INIT_DONE
            """)
            .waitingFor(Wait.forLogMessage(".*OPENBAO_INIT_DONE.*", 1)
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
            .dependsOn(raumDb, openBao, openBaoInit);

    static UUID orgId;
    static final UUID SERVICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final String BOOTSTRAP_USERNAME = "bootstrap_admin";
    static final String BOOTSTRAP_PASSWORD = "B00tstr@pPass1";

    static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext ctx) {
            System.out.println("[INITIALIZER] AppRole login...");
            WebClient bao = WebClient.builder()
                    .baseUrl("http://localhost:%d".formatted(openBao.getMappedPort(8200)))
                    .defaultHeader("X-Vault-Token", "dev-root-token")
                    .build();

            @SuppressWarnings("unchecked")
            Map<String, Object> roleIdResponse = bao.get()
                    .uri("/v1/auth/approle/role/vassago/role-id")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            assertThat(roleIdResponse).isNotNull();
            @SuppressWarnings("unchecked")
            String roleId = (String) ((Map<String, Object>) roleIdResponse.get("data")).get("role_id");

            @SuppressWarnings("unchecked")
            Map<String, Object> secretIdResponse = bao.post()
                    .uri("/v1/auth/approle/role/vassago/secret-id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            assertThat(secretIdResponse).isNotNull();
            @SuppressWarnings("unchecked")
            String secretId = (String) ((Map<String, Object>) secretIdResponse.get("data")).get("secret_id");

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
            String vassagoToken = (String) auth.get("client_token");
            assertThat(vassagoToken).isNotBlank();
            System.out.println("[INITIALIZER] Token obtained successfully.");

            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(ctx,
                    "raum.base-url=http://localhost:%d".formatted(raum.getMappedPort(8080)),
                    "vassago.service-id=" + SERVICE_ID,
                    "openbao.base-url=http://localhost:%d".formatted(openBao.getMappedPort(8200)),
                    "vassago.jwt.transit-key-name=vassago-jwt",
                    "vassago.openbao.token=" + vassagoToken
            );
        }
    }

    @BeforeAll
    static void bootstrap() throws IOException, InterruptedException {
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
        UUID generatedServiceId = UUID.fromString((String) service.get("id"));

        // Force service id to fixed UUID so Vassago's service-id bean matches
        raumDb.execInContainer(
                "psql", "-U", "postgres", "-d", "raum",
                "-c", "UPDATE services SET id = '%s' WHERE id = '%s'"
                        .formatted(SERVICE_ID, generatedServiceId)
        );

        raumClient.post().uri("/credentials")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(CredentialsDTO.builder()
                        .orgId(orgId)
                        .serviceId(SERVICE_ID)
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

        String roles = "{\"" + SERVICE_ID + "\":[\""
                + VassagoRole.VASSAGO_ADMIN.name() + "\",\""
                + VassagoRole.VASSAGO_USER.name() + "\"]}";
        customerDb.execInContainer(
                "psql", "-U", "admin", "-d", "customerdb",
                "-c", """
                INSERT INTO users (name, last_name, email, username, password, roles, is_ready)
                VALUES ('Bootstrap', 'Admin', 'admin@bootstrap.local', 'bootstrap_admin',
                        '$2a$10$xI03I5H6IoRGzfpHm4IUGOlQooxsVSVkJM3JMI4QFrJyXvR.6/gw.',
                        '%s', true);
                """.formatted(roles)
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
        request.setName("Jane");
        request.setLastName("Doe");
        request.setEmail("jane.doe@example.com");
        request.setUsername("janedoe");
        request.setRoles(Map.of(SERVICE_ID.toString(), List.of("VASSAGO_USER")));

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
        assertThat(response.getRoles()).isEqualTo(Map.of(SERVICE_ID.toString(), List.of("VASSAGO_USER")));
        assertThat(response.getTemporaryPassword()).isNotBlank();
    }

    @Test
    void createUser_poolReusesConnectionOnSecondRequest() {
        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(port))
                .build();
        String token = obtainToken(client);

        UserRequestDTO first = new UserRequestDTO();
        first.setName("Alice");
        first.setLastName("Smith");
        first.setEmail("alice@example.com");
        first.setUsername("alicesmith");
        first.setRoles(Map.of(SERVICE_ID.toString(), List.of("VASSAGO_USER")));

        UserRequestDTO second = new UserRequestDTO();
        second.setName("Bob");
        second.setLastName("Jones");
        second.setEmail("bob@example.com");
        second.setUsername("bobjones");
        second.setRoles(Map.of(SERVICE_ID.toString(), List.of("VASSAGO_USER")));

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