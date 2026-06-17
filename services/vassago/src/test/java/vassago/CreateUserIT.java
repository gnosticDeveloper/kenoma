package vassago;

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
    static final PostgreSQLContainer operationalDb = new PostgreSQLContainer("postgres:18.1-alpine3.23")
            .withNetwork(network)
            .withNetworkAliases("operational-postgres")
            .withDatabaseName("operationaldb")
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
                    .withStartupTimeout(Duration.ofSeconds(60)));

    static UUID orgId;
    static UUID vassagoServiceId;
    static final String BOOTSTRAP_USERNAME = "bootstrap_admin";
    static final String BOOTSTRAP_PASSWORD = "B00tstr@pPass1";

    static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext ctx) {
            WebClient bao = WebClient.builder()
                    .baseUrl("http://localhost:%d".formatted(openBao.getMappedPort(8200)))
                    .defaultHeader("X-Vault-Token", "dev-root-token")
                    .build();

            @SuppressWarnings("unchecked")
            Map<String, Object> roleIdResponse = bao.get()
                    .uri("/v1/auth/approle/role/vassago/role-id")
                    .retrieve().bodyToMono(Map.class).block();
            assertThat(roleIdResponse).isNotNull();
            @SuppressWarnings("unchecked")
            String roleId = (String) ((Map<String, Object>) roleIdResponse.get("data")).get("role_id");

            @SuppressWarnings("unchecked")
            Map<String, Object> secretIdResponse = bao.post()
                    .uri("/v1/auth/approle/role/vassago/secret-id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of())
                    .retrieve().bodyToMono(Map.class).block();
            assertThat(secretIdResponse).isNotNull();
            @SuppressWarnings("unchecked")
            String secretId = (String) ((Map<String, Object>) secretIdResponse.get("data")).get("secret_id");

            @SuppressWarnings("unchecked")
            Map<String, Object> loginResponse = bao.post()
                    .uri("/v1/auth/approle/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("role_id", roleId, "secret_id", secretId))
                    .retrieve().bodyToMono(Map.class).block();
            assertThat(loginResponse).isNotNull();
            @SuppressWarnings("unchecked")
            String vassagoToken = (String) ((Map<String, Object>) loginResponse.get("auth")).get("client_token");
            assertThat(vassagoToken).isNotBlank();

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

            raum.withEnv("RAUM_SERVICE_ID", raumServiceIdStr)
                    .withEnv("RAUM_OPENBAO_TOKEN", vassagoToken)
                    .withEnv("RAUM_JWT_TRANSIT_KEY_NAME", "vassago-jwt");
            raum.start();

            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(ctx,
                    "raum.base-url=http://localhost:%d".formatted(raum.getMappedPort(8080)),
                    "vassago.service-id=" + vassagoServiceIdStr,
                    "openbao.base-url=http://localhost:%d".formatted(openBao.getMappedPort(8200)),
                    "vassago.jwt.transit-key-name=vassago-jwt",
                    "vassago.openbao.token=" + vassagoToken
            );
        }
    }

    @BeforeAll
    static void bootstrap() throws Exception {
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
                        "connection_url", "postgresql://{{username}}:{{password}}@operational-postgres:5432/operationaldb?sslmode=disable",
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
                                GRANT CONNECT ON DATABASE "operationaldb" TO "{{name}}";
                                GRANT USAGE ON SCHEMA public TO "{{name}}";
                                GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "{{name}}";
                                """,
                        "default_ttl", "1h",
                        "max_ttl", "24h"
                ))
                .retrieve().bodyToMono(Void.class).block();

        String roles = "{\"" + vassagoServiceId + "\":[\""
                + VassagoRole.VASSAGO_ADMIN.name() + "\",\""
                + VassagoRole.VASSAGO_USER.name() + "\"]}";
        operationalDb.execInContainer("psql", "-U", "admin", "-d", "operationaldb",
                "-c", """
                        INSERT INTO users (name, last_name, email, username, password, roles, is_ready)
                        VALUES ('Bootstrap', 'Admin', 'admin@bootstrap.local', 'bootstrap_admin',
                                '$2a$10$xI03I5H6IoRGzfpHm4IUGOlQooxsVSVkJM3JMI4QFrJyXvR.6/gw.',
                                '%s', true)
                        ON CONFLICT (username) DO NOTHING;
                        """.formatted(roles));
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
        request.setRoles(Map.of(vassagoServiceId.toString(), List.of("VASSAGO_USER")));

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
        assertThat(response.getRoles()).isEqualTo(Map.of(vassagoServiceId.toString(), List.of("VASSAGO_USER")));
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
        first.setRoles(Map.of(vassagoServiceId.toString(), List.of("VASSAGO_USER")));

        UserRequestDTO second = new UserRequestDTO();
        second.setName("Bob");
        second.setLastName("Jones");
        second.setEmail("bob@example.com");
        second.setUsername("bobjones");
        second.setRoles(Map.of(vassagoServiceId.toString(), List.of("VASSAGO_USER")));

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