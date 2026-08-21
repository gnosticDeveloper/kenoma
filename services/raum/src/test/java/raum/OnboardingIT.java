package raum;

import common.security.JwtValidator;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import raum.clients.BimeClient;
import raum.clients.VassagoClient;
import raum.dto.OnboardingRequestDTO;
import raum.onboarding.BimePreset;
import raum.onboarding.OnboardingConfigStore;
import raum.onboarding.OnboardingRetryScheduler;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = OnboardingIT.Initializer.class)
class OnboardingIT {

    static final Network network = Network.newNetwork();

    @SuppressWarnings({"resource", "rawtypes"})
    static final PostgreSQLContainer raumDb = new PostgreSQLContainer("postgres:18.1-alpine3.23")
            .withNetwork(network)
            .withNetworkAliases("raum-postgres")
            .withDatabaseName("raum")
            .withUsername("postgres")
            .withPassword("postgres");

    @SuppressWarnings({"resource", "rawtypes"})
    static final PostgreSQLContainer vassagoDb = new PostgreSQLContainer("postgres:18.1-alpine3.23")
            .withNetwork(network)
            .withNetworkAliases("vassago-postgres")
            .withDatabaseName("vassago")
            .withUsername("admin")
            .withPassword("adminpass");

    @SuppressWarnings({"resource", "rawtypes"})
    static final PostgreSQLContainer bimeDb = new PostgreSQLContainer("postgres:18.1-alpine3.23")
            .withNetwork(network)
            .withNetworkAliases("bime-postgres")
            .withDatabaseName("bime")
            .withUsername("admin")
            .withPassword("adminpass");

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
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    static {
        raumDb.start();
        TestMigrations.migrate(raumDb, "raum");
        vassagoDb.start();
        // Pre-migrated (rather than left for the onboarding call under test to create live) because
        // @BeforeEach.setUp() below runs "DELETE FROM users" before the very first test's onboarding
        // call ever happens.
        TestMigrations.migrate(vassagoDb, "vassago");
        bimeDb.start();
        openBao.start();
        redis.start();
    }

    @MockitoBean
    JwtValidator jwtValidator;

    @MockitoBean
    VassagoClient vassagoClient;

    @MockitoBean
    BimeClient bimeClient;

    // This suite's OpenBao container never has AppRole auth enabled, so real
    // provisioning has nothing to log into; the token is seeded directly below.
    @MockitoBean
    raum.openbao.OpenBaoProvisioner openBaoProvisioner;

    @Autowired
    raum.openbao.OpenBaoService openBaoService;

    @Autowired
    OnboardingConfigStore configStore;

    @Autowired
    OnboardingRetryScheduler retryScheduler;

    static UUID orgId;
    static UUID raumServiceId;
    static UUID vassagoServiceId;
    static UUID bimeServiceId;
    static UUID vassagoCredId;
    static UUID bimeCredId;

    @LocalServerPort
    int port;

    private WebTestClient client;

    private static final AtomicBoolean infraReady = new AtomicBoolean(false);

    @BeforeAll
    static void configureInfrastructure() throws Exception {
        if (!infraReady.compareAndSet(false, true)) return;

        orgId = uuid(raumDb, "SELECT id FROM organizations WHERE name = 'Platform' LIMIT 1");
        vassagoCredId = uuid(raumDb,
                "SELECT c.id FROM credentials c JOIN services s ON c.service_id = s.id WHERE s.name = 'Vassago' LIMIT 1");
        bimeCredId = uuid(raumDb,
                "SELECT c.id FROM credentials c JOIN services s ON c.service_id = s.id WHERE s.name = 'Bime' LIMIT 1");

        // Point credentials at test containers so VassagoOnboardingStrategy's direct R2DBC client can reach them.
        raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum", "-c",
                "UPDATE credentials SET db_host='localhost', db_port=%d WHERE id='%s';"
                        .formatted(vassagoDb.getMappedPort(5432), vassagoCredId));
        raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum", "-c",
                "UPDATE credentials SET db_host='localhost', db_port=%d WHERE id='%s';"
                        .formatted(bimeDb.getMappedPort(5432), bimeCredId));

        WebClient bao = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(openBao.getMappedPort(8200)))
                .defaultHeader("X-Vault-Token", "dev-root-token")
                .build();

        bao.post().uri("/v1/sys/mounts/database")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "database"))
                .retrieve().bodyToMono(Void.class).onErrorResume(e -> Mono.empty()).block();

        // Static (superuser) credentials in KV, mirroring OpenBaoService.storeCredentials — SchemaProvisioner
        // reads these to run schema DDL under the DB owner rather than a short-lived ephemeral role.
        bao.post().uri("/v1/secret/data/credentials/{id}", vassagoCredId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("data", Map.of("username", "admin", "password", "adminpass")))
                .retrieve().bodyToMono(Void.class).block();

        bao.post().uri("/v1/secret/data/credentials/{id}", bimeCredId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("data", Map.of("username", "admin", "password", "adminpass")))
                .retrieve().bodyToMono(Void.class).block();

        bao.post().uri("/v1/database/config/{id}", vassagoCredId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "plugin_name", "postgresql-database-plugin",
                        "allowed_roles", vassagoCredId + "-role",
                        "connection_url", "postgresql://{{username}}:{{password}}@vassago-postgres:5432/vassago?sslmode=disable",
                        "username", "admin",
                        "password", "adminpass"
                ))
                .retrieve().bodyToMono(Void.class).block();

        bao.post().uri("/v1/database/roles/{role}", vassagoCredId + "-role")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "db_name", vassagoCredId.toString(),
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

        bao.post().uri("/v1/database/config/{id}", bimeCredId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "plugin_name", "postgresql-database-plugin",
                        "allowed_roles", bimeCredId + "-role",
                        "connection_url", "postgresql://{{username}}:{{password}}@bime-postgres:5432/bime?sslmode=disable",
                        "username", "admin",
                        "password", "adminpass"
                ))
                .retrieve().bodyToMono(Void.class).block();

        bao.post().uri("/v1/database/roles/{role}", bimeCredId + "-role")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "db_name", bimeCredId.toString(),
                        "creation_statements", """
                                CREATE ROLE "{{name}}" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}';
                                GRANT CONNECT ON DATABASE "bime" TO "{{name}}";
                                GRANT USAGE ON SCHEMA public TO "{{name}}";
                                GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "{{name}}";
                                """,
                        "default_ttl", "1h",
                        "max_ttl", "24h"
                ))
                .retrieve().bodyToMono(Void.class).block();
    }

    @BeforeEach
    void setUp() throws Exception {
        openBaoService.setToken("dev-root-token");
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
        // Reset between tests: clear credential state and any leaked temp users from failure scenarios.
        raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum", "-c",
                "UPDATE credentials SET is_initialized = false WHERE org_id = '%s';".formatted(orgId));
        vassagoDb.execInContainer("psql", "-U", "admin", "-d", "vassago", "-c",
                "DELETE FROM users WHERE username LIKE '_temp_%';");
    }

    // -------------------------------------------------------------------------
    // Auth
    // -------------------------------------------------------------------------

    @Test
    void initiateOnboarding_returns401_withoutJwt() {
        client.post().uri("/onboarding/{id}", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(buildRequest(BimePreset.CLOTHING_STORE))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void initiateOnboarding_returns403_withRaumAdminRole() {
        mockAdminJwt();
        client.post().uri("/onboarding/{id}", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(buildRequest(BimePreset.CLOTHING_STORE))
                .exchange()
                .expectStatus().isForbidden();
    }

    // -------------------------------------------------------------------------
    // Happy path — core flow
    // -------------------------------------------------------------------------

    @Test
    void initiateOnboarding_returns204_andMarksAllCredentialsInitialized() throws Exception {
        stubVassagoAndBime();
        mockOnboardingJwt();

        client.post().uri("/onboarding/{id}", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(buildRequest(BimePreset.CLOTHING_STORE))
                .exchange()
                .expectStatus().isNoContent();

        assertThat(uninitializedCount()).isZero();
    }

    @Test
    void initiateOnboarding_createsOrgAdminUserInVassago() {
        stubVassagoAndBime();
        mockOnboardingJwt();

        client.post().uri("/onboarding/{id}", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(buildRequest(BimePreset.CLOTHING_STORE))
                .exchange()
                .expectStatus().isNoContent();

        verify(vassagoClient).createUser(
                anyString(), eq("Alice"), eq("Smith"),
                eq("alice@example.com"), eq("alice"), anyMap(), any());
    }

    @Test
    void initiateOnboarding_persistsPresetConfigInRedis() {
        stubVassagoAndBime();
        mockOnboardingJwt();

        client.post().uri("/onboarding/{id}", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(buildRequest(BimePreset.CLOTHING_STORE))
                .exchange()
                .expectStatus().isNoContent();

        Map<String, String> config = configStore.get(bimeCredId).block(Duration.ofSeconds(5));
        assertThat(config).isNotNull().containsEntry("preset", "CLOTHING_STORE").containsEntry("locale", "en");
    }

    @Test
    void initiateOnboarding_persistsRequestedLocaleInRedis() {
        stubVassagoAndBime();
        mockOnboardingJwt();

        OnboardingRequestDTO request = buildRequest(BimePreset.CLOTHING_STORE);
        request.setLocale("es");

        client.post().uri("/onboarding/{id}", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isNoContent();

        Map<String, String> config = configStore.get(bimeCredId).block(Duration.ofSeconds(5));
        assertThat(config).isNotNull().containsEntry("locale", "es");
    }

    @Test
    void initiateOnboarding_cleansTempUserFromVassagoDb() throws Exception {
        stubVassagoAndBime();
        mockOnboardingJwt();

        client.post().uri("/onboarding/{id}", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(buildRequest(BimePreset.CLOTHING_STORE))
                .exchange()
                .expectStatus().isNoContent();

        String tempCount = vassagoDb.execInContainer("psql", "-U", "admin", "-d", "vassago",
                        "-t", "-A", "-c", "SELECT COUNT(*) FROM users WHERE username LIKE '_temp_%';")
                .getStdout().trim();
        assertThat(Integer.parseInt(tempCount)).isZero();
    }

    // -------------------------------------------------------------------------
    // Preset coverage
    // -------------------------------------------------------------------------

    @Test
    void initiateOnboarding_clothingStore_seedsThreeLocations() {
        stubVassagoAndBime();
        mockOnboardingJwt();

        client.post().uri("/onboarding/{id}", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(buildRequest(BimePreset.CLOTHING_STORE))
                .exchange()
                .expectStatus().isNoContent();

        verify(bimeClient, times(3)).createLocation(anyString(), anyString(), anyString());
    }

    @Test
    void initiateOnboarding_bookStore_seedsOneLocation() {
        stubVassagoAndBime();
        mockOnboardingJwt();

        client.post().uri("/onboarding/{id}", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(buildRequest(BimePreset.BOOK_STORE))
                .exchange()
                .expectStatus().isNoContent();

        verify(bimeClient, times(1)).createLocation(anyString(), anyString(), anyString());
    }

    @Test
    void initiateOnboarding_repairShop_seedsTwoLocations() {
        stubVassagoAndBime();
        mockOnboardingJwt();

        client.post().uri("/onboarding/{id}", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(buildRequest(BimePreset.REPAIR_SHOP))
                .exchange()
                .expectStatus().isNoContent();

        verify(bimeClient, times(2)).createLocation(anyString(), anyString(), anyString());
    }

    @Test
    void initiateOnboarding_storageWarehouse_seedsFourLocations() {
        stubVassagoAndBime();
        mockOnboardingJwt();

        client.post().uri("/onboarding/{id}", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(buildRequest(BimePreset.STORAGE_WAREHOUSE))
                .exchange()
                .expectStatus().isNoContent();

        verify(bimeClient, times(4)).createLocation(anyString(), anyString(), anyString());
    }

    // -------------------------------------------------------------------------
    // Failure / partial state
    // -------------------------------------------------------------------------

    @Test
    void initiateOnboarding_whenBimeStrategyFails_returns5xx_andLeavesVassagoInitializedOnly() throws Exception {
        // Vassago strategy succeeds, then Bime blows up on the first location call.
        stubVassagoAndBime();
        when(bimeClient.createLocation(anyString(), anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("Bime unavailable")));
        mockOnboardingJwt();

        client.post().uri("/onboarding/{id}", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(buildRequest(BimePreset.CLOTHING_STORE))
                .exchange()
                .expectStatus().is5xxServerError();

        String vassagoInit = raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                        "-t", "-A", "-c",
                        "SELECT is_initialized FROM credentials WHERE id = '%s';".formatted(vassagoCredId))
                .getStdout().trim();
        String bimeInit = raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                        "-t", "-A", "-c",
                        "SELECT is_initialized FROM credentials WHERE id = '%s';".formatted(bimeCredId))
                .getStdout().trim();

        assertThat(vassagoInit).isEqualTo("t");
        assertThat(bimeInit).isEqualTo("f");
    }

    @Test
    void initiateOnboarding_whenVassagoLoginFails_returns5xx_andNeitherCredentialInitialized() throws Exception {
        when(vassagoClient.login(any(UUID.class), anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("Vassago unreachable")));
        mockOnboardingJwt();

        client.post().uri("/onboarding/{id}", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(buildRequest(BimePreset.CLOTHING_STORE))
                .exchange()
                .expectStatus().is5xxServerError();

        assertThat(uninitializedCount()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // Idempotency
    // -------------------------------------------------------------------------

    @Test
    void initiateOnboarding_succeedsOnRepeatCall_whenCredentialsAlreadyInitialized() {
        // There is no guard against re-onboarding; both calls must succeed without throwing.
        stubVassagoAndBime();
        mockOnboardingJwt();

        client.post().uri("/onboarding/{id}", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(buildRequest(BimePreset.CLOTHING_STORE))
                .exchange()
                .expectStatus().isNoContent();

        // Stubs reset by @MockitoBean between calls, so re-stub.
        stubVassagoAndBime();

        client.post().uri("/onboarding/{id}", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(buildRequest(BimePreset.CLOTHING_STORE))
                .exchange()
                .expectStatus().isNoContent();
    }

    // -------------------------------------------------------------------------
    // OnboardingRetryScheduler
    // -------------------------------------------------------------------------

    @Test
    void retry_skipsOrg_whenVassagoCredentialIsUninitialized() throws Exception {
        // Both credentials are uninitialized (default after @BeforeEach reset).
        // Scheduler must skip the entire org and leave both untouched.
        retryScheduler.retryUninitialized();

        Thread.sleep(2000); // give the async subscribe chain time to run and settle

        assertThat(uninitializedCount()).isEqualTo(2);
    }

    @Test
    void retry_reinitializesBimeCredential_whenOnlyBimeIsUninitialized() throws Exception {
        // Mark Vassago as already initialized; leave Bime uninitialized.
        raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum", "-c",
                "UPDATE credentials SET is_initialized = true WHERE id = '%s';".formatted(vassagoCredId));

        // Store the config the scheduler needs to reconstruct the BimePreset request.
        configStore.save(bimeCredId, Map.of("preset", "CLOTHING_STORE")).block(Duration.ofSeconds(5));

        stubVassagoAndBime();

        retryScheduler.retryUninitialized();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(uninitializedCount()).isZero());
    }

    @Test
    void retry_skipsBimeRetry_whenConfigNotInRedis() throws Exception {
        // Mark Vassago initialized but leave NO config in Redis for Bime.
        // configStore.get() will return Mono.empty() → retryCredential silently skips.
        raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum", "-c",
                "UPDATE credentials SET is_initialized = true WHERE id = '%s';".formatted(vassagoCredId));

        // Ensure any leftover config from another test is gone.
        configStore.delete(bimeCredId).block(Duration.ofSeconds(5));

        retryScheduler.retryUninitialized();

        Thread.sleep(2000);

        String bimeInit = raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                        "-t", "-A", "-c",
                        "SELECT is_initialized FROM credentials WHERE id = '%s';".formatted(bimeCredId))
                .getStdout().trim();
        assertThat(bimeInit).isEqualTo("f");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void stubVassagoAndBime() {
        when(vassagoClient.login(any(UUID.class), anyString(), anyString()))
                .thenReturn(Mono.just("fake-jwt"));
        when(vassagoClient.createUser(anyString(), anyString(), anyString(), anyString(), anyString(), anyMap(), any()))
                .thenReturn(Mono.just(UUID.randomUUID()));
        when(bimeClient.createLocation(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(UUID.randomUUID()));
        when(bimeClient.createProduct(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(UUID.randomUUID()));
        when(bimeClient.createMetadata(anyString(), anyString()))
                .thenReturn(Mono.just(UUID.randomUUID()));
        when(bimeClient.addOption(anyString(), any(UUID.class), anyString()))
                .thenReturn(Mono.just(UUID.randomUUID()));
        when(bimeClient.assignMetadata(anyString(), any(UUID.class), anyList()))
                .thenReturn(Mono.empty());
        when(bimeClient.createVariant(anyString(), any(UUID.class), anyList(), anyString()))
                .thenReturn(Mono.just(UUID.randomUUID()));
        when(bimeClient.recordStockMovement(anyString(), any(UUID.class), any(UUID.class), anyInt()))
                .thenReturn(Mono.empty());
    }

    @SuppressWarnings("unchecked")
    private void mockOnboardingJwt() {
        Claims claims = mock(Claims.class);
        String rolesJson = "{\"" + raumServiceId + "\":[\"RAUM_ONBOARDING\"]}";
        when(claims.getSubject()).thenReturn("test-onboarding");
        when(claims.get(eq("orgId"), eq(String.class))).thenReturn(orgId.toString());
        when(claims.get(eq("roles"), eq(String.class))).thenReturn(rolesJson);
        when(jwtValidator.validateToken(anyString())).thenReturn(Mono.just(claims));
    }

    @SuppressWarnings("unchecked")
    private void mockAdminJwt() {
        Claims claims = mock(Claims.class);
        String rolesJson = "{\"" + raumServiceId + "\":[\"RAUM_ADMIN\"]}";
        when(claims.getSubject()).thenReturn("test-admin");
        when(claims.get(eq("orgId"), eq(String.class))).thenReturn(orgId.toString());
        when(claims.get(eq("roles"), eq(String.class))).thenReturn(rolesJson);
        when(jwtValidator.validateToken(anyString())).thenReturn(Mono.just(claims));
    }

    private OnboardingRequestDTO buildRequest(BimePreset preset) {
        OnboardingRequestDTO req = new OnboardingRequestDTO();
        req.setName("Alice");
        req.setLastName("Smith");
        req.setEmail("alice@example.com");
        req.setUsername("alice");
        req.setBimePreset(preset);
        return req;
    }

    private int uninitializedCount() throws Exception {
        return Integer.parseInt(
                raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum", "-t", "-A", "-c",
                                "SELECT COUNT(*) FROM credentials WHERE org_id = '%s' AND is_initialized = false;"
                                        .formatted(orgId))
                        .getStdout().trim());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static UUID uuid(PostgreSQLContainer container, String sql) throws Exception {
        return UUID.fromString(
                container.execInContainer("psql", "-U", container.getUsername(),
                                "-d", container.getDatabaseName(), "-t", "-A", "-c", sql + ";")
                        .getStdout().trim());
    }

    // -------------------------------------------------------------------------
    // Context initializer
    // -------------------------------------------------------------------------

    static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        private static final AtomicBoolean initialized = new AtomicBoolean(false);
        private static String raumServiceIdStr;
        private static String vassagoServiceIdStr;
        private static String bimeServiceIdStr;

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
                vassagoServiceIdStr = raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                                "-t", "-A", "-c", "SELECT id FROM services WHERE name = 'Vassago' LIMIT 1;")
                        .getStdout().trim();
                bimeServiceIdStr = raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                                "-t", "-A", "-c", "SELECT id FROM services WHERE name = 'Bime' LIMIT 1;")
                        .getStdout().trim();

                raumServiceId = UUID.fromString(raumServiceIdStr);
                vassagoServiceId = UUID.fromString(vassagoServiceIdStr);
                bimeServiceId = UUID.fromString(bimeServiceIdStr);
            } catch (Exception e) {
                throw new RuntimeException("Failed to read service IDs", e);
            }
        }

        private static void injectProperties(ConfigurableApplicationContext ctx) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(ctx,
                    "spring.r2dbc.url=r2dbc:postgresql://localhost:%d/raum".formatted(raumDb.getMappedPort(5432)),
                    "spring.r2dbc.username=postgres",
                    "spring.r2dbc.password=postgres",
                    "openbao.host=http://localhost:%d".formatted(openBao.getMappedPort(8200)),
                    "openbao.kv.mount=secret",
                    "RAUM_SERVICE_ID=" + raumServiceIdStr,
                    "vassago.base-url=http://fake-vassago:8081",
                    "vassago.service-id=" + vassagoServiceIdStr,
                    "vassago.jwt.public-key-refresh-cron=-",
                    "bime.base-url=http://fake-bime:8082",
                    "bime.service-id=" + bimeServiceIdStr,
                    "spring.data.redis.host=localhost",
                    "spring.data.redis.port=" + redis.getMappedPort(6379),
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
}
