package raum.onboarding;

import common.dto.CredentialsDTO;
import common.utils.RolesUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import raum.clients.VassagoClient;
import raum.dto.OnboardingRequestDTO;
import raum.migration.MigrationRunner;
import raum.openbao.OpenBaoService;
import raum.repository.CredentialsRepository;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Order(1)
public class VassagoOnboardingStrategy implements OnboardingStrategy {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final VassagoClient vassagoClient;
    private final PasswordEncoder passwordEncoder;
    private final CredentialsRepository credentialsRepository;
    private final OpenBaoService openBaoService;
    private final MigrationRunner migrationRunner;

    @Value("${vassago.service-id}")
    private UUID vassagoServiceId;

    @Value("${bime.service-id}")
    private UUID bimeServiceId;

    @Value("${raum.service-id}")
    private UUID raumServiceId;

    public VassagoOnboardingStrategy(VassagoClient vassagoClient, PasswordEncoder passwordEncoder,
                                     CredentialsRepository credentialsRepository, OpenBaoService openBaoService,
                                     MigrationRunner migrationRunner) {
        this.vassagoClient = vassagoClient;
        this.passwordEncoder = passwordEncoder;
        this.credentialsRepository = credentialsRepository;
        this.openBaoService = openBaoService;
        this.migrationRunner = migrationRunner;
    }

    @Override
    public UUID getServiceId() {
        return vassagoServiceId;
    }

    @Override
    public Mono<Void> execute(UUID orgId, CredentialsDTO credentials, OnboardingRequestDTO request, OnboardingContext context) {
        Map<String, List<String>> userRoles = Map.of(
                vassagoServiceId.toString(), List.of("VASSAGO_ADMIN"),
                bimeServiceId.toString(), List.of("BIME_ADMIN"),
                raumServiceId.toString(), List.of("RAUM_OWNER")
        );
        return createTempSession(orgId, credentials)
                .flatMap(session -> {
                    context.setJwt(session.jwt());
                    context.setCleanup(session.cleanup());
                    return vassagoClient.createUser(
                            session.jwt(),
                            request.getName(),
                            request.getLastName(),
                            request.getEmail(),
                            request.getUsername(),
                            userRoles,
                            request.getLocale()
                    );
                })
                .then();
    }

    public Mono<TempSession> createTempSession(UUID orgId, CredentialsDTO vassagoCredentials) {
        String tempUsername = "_temp_" + UUID.randomUUID().toString().replace("-", "");
        String tempPassword = generateToken();
        String tempEmail = tempUsername + "@_onboarding.internal";
        String passwordHash = passwordEncoder.encode(tempPassword);
        String roles = RolesUtils.serialize(Map.of(
                vassagoServiceId.toString(), List.of("VASSAGO_ADMIN"),
                bimeServiceId.toString(), List.of("BIME_ADMIN"),
                raumServiceId.toString(), List.of("RAUM_OWNER")
        ));

        DatabaseClient client = SchemaProvisioner.buildClient(vassagoCredentials);

        return credentialsRepository.findByOrgIdAndServiceId(orgId, vassagoServiceId)
                .flatMap(migrationRunner::migrateInstance)
                .then(SchemaProvisioner.staticClientFor(credentialsRepository, openBaoService, orgId, vassagoServiceId, vassagoCredentials)
                        .flatMap(schemaClient -> SchemaProvisioner.grantDml(schemaClient, vassagoCredentials.getUserName())))
                .then(Mono.defer(() -> client.sql("""
                        INSERT INTO users (org_id, name, last_name, email, username, password, roles, is_ready, created_at, modified_at)
                        VALUES (:orgId, :name, :lastName, :email, :username, :password, :roles, true, :now, :now)
                        RETURNING id
                        """)
                        .bind("orgId", orgId)
                        .bind("name", "_Onboarding")
                        .bind("lastName", "Temp")
                        .bind("email", tempEmail)
                        .bind("username", tempUsername)
                        .bind("password", passwordHash)
                        .bind("roles", roles)
                        .bind("now", Instant.now())
                        .fetch()
                        .one()))
                .flatMap(row -> {
                    UUID tempAdminId = (UUID) row.get("id");
                    Mono<Void> cleanup = deleteUser(client, tempAdminId)
                            .onErrorResume(e -> credentialsRepository.findByOrgIdAndServiceId(orgId, vassagoServiceId)
                                    .flatMap(creds -> openBaoService.issueEphemeralCredentials(creds.getId()))
                                    .flatMap(fresh -> deleteUser(SchemaProvisioner.buildClient(fresh), tempAdminId)));
                    return vassagoClient.login(orgId, tempUsername, tempPassword)
                            .map(jwt -> new TempSession(jwt, cleanup));
                });
    }

    public record TempSession(String jwt, Mono<Void> cleanup) {}

    private Mono<Void> deleteUser(DatabaseClient client, UUID userId) {
        return client.sql("DELETE FROM users WHERE id = :id")
                .bind("id", userId)
                .fetch()
                .rowsUpdated()
                .then();
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
