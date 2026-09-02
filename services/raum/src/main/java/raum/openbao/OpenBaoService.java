package raum.openbao;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import common.dto.CredentialsDTO;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class OpenBaoService {
    private final WebClient webClient;
    private final String kvMount;
    private final String host;
    private final AtomicReference<String> token = new AtomicReference<>();

    public OpenBaoService(
            @Value("${openbao.host}") String host,
            @Value("${openbao.kv.mount}") String kvMount) {
        this.host = host;
        this.webClient = WebClient.builder()
                .baseUrl(host)
                .filter((request, next) -> next.exchange(
                        ClientRequest.from(request)
                                .headers(headers -> headers.set("X-Vault-Token", token.get()))
                                .build()))
                .build();
        this.kvMount = kvMount;
    }

    /** Replaces the token used for subsequent requests, following a (re)provisioning login or renewal. */
    public void setToken(String newToken) {
        token.set(newToken);
    }

    public Mono<Void> renewSelf() {
        return webClient.post()
                .uri("/v1/auth/token/renew-self")
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            log.error("renewSelf FAILED [{}]: {}", response.statusCode(), body);
                            return Mono.error(new RuntimeException("renewSelf failed: " + body));
                        })
                )
                .bodyToMono(Void.class);
    }

    public Mono<Boolean> validateToken(String token) {
        return WebClient.builder()
                .baseUrl(host)
                .defaultHeader("X-Vault-Token", token)
                .build()
                .get()
                .uri("/v1/auth/token/lookup-self")
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new RuntimeException("Token validation failed: " + body))))
                .bodyToMono(Void.class)
                .thenReturn(true)
                .onErrorReturn(false);
    }

    public Mono<Void> storeCredentials(UUID id, String username, String password) {
        return webClient.post()
                .uri("/v1/{mount}/data/credentials/{id}", kvMount, id)
                .bodyValue(Map.of("data", Map.of(
                        "username", username,
                        "password", password
                )))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            log.error("storeCredentials FAILED [{}]: {}", response.statusCode(), body);
                            return Mono.error(new RuntimeException("storeCredentials failed: " + body));
                        })
                )
                .bodyToMono(Void.class);
    }

    public Mono<Void> registerDatabaseConnection(UUID id, String username, String password,
                                                 String dbHost, int dbPort, String dbName, UUID orgId) {
        String connectionName = id.toString();
        String adminRoleName = CredentialTier.ADMIN.roleName(connectionName);
        String memberRoleName = CredentialTier.MEMBER.roleName(connectionName);
        String connectionUrl = String.format(
                "postgresql://{{username}}:{{password}}@%s:%d/%s?sslmode=disable",
                dbHost, dbPort, dbName);

        return webClient.post()
                .uri("/v1/database/config/{name}", connectionName)
                .bodyValue(Map.of(
                        "plugin_name", "postgresql-database-plugin",
                        "allowed_roles", adminRoleName + "," + memberRoleName,
                        "connection_url", connectionUrl,
                        "username", username,
                        "password", password
                ))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            log.error("registerDatabaseConnection FAILED [{}]: {}", response.statusCode(), body);
                            return Mono.error(new RuntimeException("registerDatabaseConnection failed: " + body));
                        })
                )
                .bodyToMono(Void.class)
                .then(createRole(connectionName, adminRoleName, dbName, username, orgId,
                        "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO \"{{name}}\"; "))
                .then(createRole(connectionName, memberRoleName, dbName, username, orgId,
                        // Read-only: a caller who only holds a non-admin role for the target service
                        // (e.g. VASSAGO_MEMBER, BIME_VIEWER) can never write through this connection —
                        // even a raw psql session, and even to their own row — closing the self-elevation
                        // path (UPDATE users SET roles = ...) that a blanket-write grant left open. See
                        // raum.controllers.CredentialsController for tier resolution.
                        "GRANT SELECT ON ALL TABLES IN SCHEMA public TO \"{{name}}\"; "));
    }

    private Mono<Void> createRole(String connectionName, String roleName, String dbName, String ownerUsername,
                                   UUID orgId, String grantStatement) {
        return webClient.post()
                .uri("/v1/database/roles/{role}", roleName)
                .bodyValue(Map.of(
                        "db_name", connectionName,
                        "creation_statements", "CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; " +
                                "GRANT CONNECT ON DATABASE \"" + dbName + "\" TO \"{{name}}\"; " +
                                "GRANT USAGE ON SCHEMA public TO \"{{name}}\"; " +
                                grantStatement +
                                "ALTER ROLE \"{{name}}\" SET app.org_id = '" + orgId + "';",
                        "revocation_statements", "REASSIGN OWNED BY \"{{name}}\" TO \"" + ownerUsername + "\"; " +
                                "DROP OWNED BY \"{{name}}\"; " +
                                "DROP ROLE IF EXISTS \"{{name}}\";",
                        "default_ttl", "1h",
                        "max_ttl", "24h"
                ))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            log.error("createRole FAILED [{}]: {}", response.statusCode(), body);
                            return Mono.error(new RuntimeException("createRole failed: " + body));
                        })
                )
                .bodyToMono(Void.class);
    }

    public Mono<CredentialsDTO> getStaticCredentials(UUID id) {
        return webClient.get()
                .uri("/v1/{mount}/data/credentials/{id}", kvMount, id)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            log.error("getStaticCredentials FAILED [{}]: {}", response.statusCode(), body);
                            return Mono.error(new RuntimeException("getStaticCredentials failed: " + body));
                        })
                )
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(response -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> outer = (Map<String, Object>) response.get("data");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) outer.get("data");
                    CredentialsDTO dto = new CredentialsDTO();
                    dto.setUserName((String) data.get("username"));
                    dto.setPassword((String) data.get("password"));
                    return dto;
                });
    }

    public Mono<CredentialsDTO> issueEphemeralCredentials(UUID id) {
        return issueEphemeralCredentials(id, CredentialTier.ADMIN);
    }

    public Mono<CredentialsDTO> issueEphemeralCredentials(UUID id, CredentialTier tier) {
        return fetchDatabaseCreds(tier.roleName(id.toString()));
    }

    /**
     * Revokes every outstanding lease issued under both tiers of a credentials row's Vault role, in
     * one shot each - no need to track individual lease IDs, {@code revoke-prefix} kills anything
     * under that role's {@code database/creds/<role>} path regardless of how many leases (or renewals)
     * are currently live. Used when an org is deactivated ({@link raum.services.OrganizationService})
     * so already-issued ephemeral DB connections stop working immediately, rather than just running
     * out their TTL (default 1h, max 24h).
     */
    public Mono<Void> revokeAllLeasesForCredential(UUID credentialId) {
        return revokeLeasesForRole(CredentialTier.ADMIN.roleName(credentialId.toString()))
                .then(revokeLeasesForRole(CredentialTier.MEMBER.roleName(credentialId.toString())));
    }

    private Mono<Void> revokeLeasesForRole(String roleName) {
        return webClient.post()
                .uri("/v1/sys/leases/revoke-prefix/database/creds/{role}", roleName)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            log.error("revokeLeasesForRole FAILED [{}]: {}", response.statusCode(), body);
                            return Mono.error(new RuntimeException("revokeLeasesForRole failed: " + body));
                        })
                )
                .bodyToMono(Void.class)
                .onErrorResume(e -> {
                    log.warn("revokeAllLeasesForCredential: continuing past revoke failure for role {}", roleName, e);
                    return Mono.empty();
                });
    }

    /**
     * Ensures a read-only DR-backup role exists for an already-registered database connection
     * (see {@link #registerDatabaseConnection}). Reuses that connection rather than registering a
     * new one, since the physical instance behind it is shared across every org/service credential
     * row that points at the same (host, port, db_name) — one representative connection is enough.
     *
     * <p>The connection's {@code allowed_roles} was set at org-onboarding time to only the two
     * ephemeral tier roles ({@link CredentialTier#ADMIN}/{@link CredentialTier#MEMBER}) — Vault
     * refuses to issue creds for any role not on that list, so this must widen it to include the
     * backup role (while preserving both tier roles - re-registering a connection replaces
     * {@code allowed_roles} wholesale, it doesn't append) before (re)creating it. Re-registering the
     * connection needs the same admin credentials used originally, fetched back from the static KV
     * entry.
     *
     * <p>Checks Vault for actual current state first (rather than caching "already registered" in app
     * memory) so this stays correct across restarts, multiple raum instances, and anyone changing
     * things out-of-band in Vault directly — Vault itself is always the source of truth, never a local
     * assumption that could drift from it. The role's existence and the connection's allowed_roles are
     * two independent Vault objects — a role can exist while the connection no longer allows it (e.g.
     * if the connection was re-registered since), so both are checked separately rather than treating
     * "role exists" as proof the connection already allows it too.
     */
    public Mono<Void> registerBackupRole(UUID connectionId, String dbHost, int dbPort, String dbName) {
        String connectionName = connectionId.toString();
        String roleName = connectionName + "-dr-backup-role";
        return Mono.zip(connectionAllowsRole(connectionName, roleName), backupRoleExists(roleName))
                .flatMap(state -> {
                    Mono<Void> ensureAllowed = state.getT1()
                            ? Mono.empty()
                            : getStaticCredentials(connectionId)
                                    .flatMap(adminCreds -> allowBackupRoleOnConnection(connectionName, dbHost, dbPort, dbName, roleName, adminCreds));
                    Mono<Void> ensureRole = state.getT2() ? Mono.empty() : createBackupRole(connectionName, roleName, dbName);
                    return ensureAllowed.then(ensureRole);
                });
    }

    private Mono<Boolean> connectionAllowsRole(String connectionName, String roleName) {
        return webClient.get()
                .uri("/v1/database/config/{name}", connectionName)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                                .map(body -> {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> data = (Map<String, Object>) body.get("data");
                                    Object allowedRoles = data == null ? null : data.get("allowed_roles");
                                    return allowedRoles instanceof List<?> roles && roles.contains(roleName);
                                });
                    }
                    if (response.statusCode().value() == 404) {
                        return response.releaseBody().thenReturn(false);
                    }
                    return response.bodyToMono(String.class).flatMap(body -> {
                        log.error("connectionAllowsRole FAILED [{}]: {}", response.statusCode(), body);
                        return Mono.error(new RuntimeException("connectionAllowsRole failed: " + body));
                    });
                });
    }

    private Mono<Boolean> backupRoleExists(String roleName) {
        return webClient.get()
                .uri("/v1/database/roles/{role}", roleName)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.releaseBody().thenReturn(true);
                    }
                    if (response.statusCode().value() == 404) {
                        return response.releaseBody().thenReturn(false);
                    }
                    return response.bodyToMono(String.class).flatMap(body -> {
                        log.error("backupRoleExists FAILED [{}]: {}", response.statusCode(), body);
                        return Mono.error(new RuntimeException("backupRoleExists failed: " + body));
                    });
                });
    }

    private Mono<Void> allowBackupRoleOnConnection(String connectionName, String dbHost, int dbPort, String dbName,
                                                     String backupRoleName, CredentialsDTO adminCreds) {
        String connectionUrl = String.format(
                "postgresql://{{username}}:{{password}}@%s:%d/%s?sslmode=disable",
                dbHost, dbPort, dbName);
        return webClient.post()
                .uri("/v1/database/config/{name}", connectionName)
                .bodyValue(Map.of(
                        "plugin_name", "postgresql-database-plugin",
                        "allowed_roles", CredentialTier.ADMIN.roleName(connectionName) + "," +
                                CredentialTier.MEMBER.roleName(connectionName) + "," + backupRoleName,
                        "connection_url", connectionUrl,
                        "username", adminCreds.getUserName(),
                        "password", adminCreds.getPassword()
                ))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            log.error("allowBackupRoleOnConnection FAILED [{}]: {}", response.statusCode(), body);
                            return Mono.error(new RuntimeException("allowBackupRoleOnConnection failed: " + body));
                        })
                )
                .bodyToMono(Void.class);
    }

    private Mono<Void> createBackupRole(String connectionName, String roleName, String dbName) {
        return webClient.post()
                .uri("/v1/database/roles/{role}", roleName)
                .bodyValue(Map.of(
                        "db_name", connectionName,
                        "creation_statements", "CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; " +
                                "GRANT CONNECT ON DATABASE \"" + dbName + "\" TO \"{{name}}\"; " +
                                "GRANT USAGE ON SCHEMA public TO \"{{name}}\"; " +
                                "GRANT SELECT ON ALL TABLES IN SCHEMA public TO \"{{name}}\";",
                        "revocation_statements", "DROP ROLE IF EXISTS \"{{name}}\";",
                        "default_ttl", "1h",
                        "max_ttl", "2h"
                ))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            log.error("registerBackupRole FAILED [{}]: {}", response.statusCode(), body);
                            return Mono.error(new RuntimeException("registerBackupRole failed: " + body));
                        })
                )
                .bodyToMono(Void.class);
    }

    public Mono<CredentialsDTO> issueBackupCredentials(UUID connectionId) {
        return fetchDatabaseCreds(connectionId + "-dr-backup-role");
    }

    private Mono<CredentialsDTO> fetchDatabaseCreds(String roleName) {
        return webClient.get()
                .uri("/v1/database/creds/{role}", roleName)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            log.error("fetchDatabaseCreds FAILED [{}]: {}", response.statusCode(), body);
                            return Mono.error(new RuntimeException("fetchDatabaseCreds failed: " + body));
                        })
                )
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(response -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) response.get("data");
                    long leaseDuration = ((Number) response.getOrDefault("lease_duration", 3600L)).longValue();
                    String leaseId = (String) response.getOrDefault("lease_id", "");
                    CredentialsDTO dto = new CredentialsDTO();
                    dto.setUserName((String) data.get("username"));
                    dto.setPassword((String) data.get("password"));
                    dto.setLeaseDuration(leaseDuration);
                    dto.setLeaseId(leaseId);
                    return dto;
                })
                .retryWhen(Retry.backoff(4, Duration.ofMillis(200))
                        .maxBackoff(Duration.ofSeconds(2))
                        .filter(t -> t.getMessage() != null && t.getMessage().contains("tuple concurrently updated"))
                        .doBeforeRetry(signal -> log.warn("fetchDatabaseCreds for {} hit OpenBao write race, retry {}",
                                roleName, signal.totalRetries() + 1)));
    }

    private static final String TRANSIT_KEY = "dr-backup";

    /**
     * Idempotent check-then-create for the {@code dr-backup} transit key, mirroring
     * {@link #registerBackupRole}'s style: query Vault for actual current state first rather than
     * caching "already provisioned" in app memory, so this stays correct across restarts, multiple
     * raum instances, and anyone changing things out-of-band in Vault directly.
     *
     * <p>Deliberately does not attempt to mount the {@code transit/} secrets engine itself -
     * mounting a new engine is a {@code sudo}-protected Vault operation and this class's token is a
     * narrowly-scoped service policy, not an admin one. {@code transit/} is already mounted platform-
     * wide by {@code scripts/init-openbao.sh} (it's also where {@code transit/keys/vassago-jwt}
     * lives) - this only ever needs to create one more key under it.
     */
    public Mono<Void> ensureTransitKey() {
        return webClient.get()
                .uri("/v1/transit/keys/{key}", TRANSIT_KEY)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.releaseBody().then();
                    }
                    if (response.statusCode().value() == 404) {
                        return createTransitKey();
                    }
                    return response.bodyToMono(String.class).flatMap(body -> {
                        log.error("ensureTransitKey lookup FAILED [{}]: {}", response.statusCode(), body);
                        return Mono.error(new RuntimeException("ensureTransitKey lookup failed: " + body));
                    });
                });
    }

    private Mono<Void> createTransitKey() {
        return webClient.post()
                .uri("/v1/transit/keys/{key}", TRANSIT_KEY)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            log.error("createTransitKey FAILED [{}]: {}", response.statusCode(), body);
                            return Mono.error(new RuntimeException("createTransitKey failed: " + body));
                        })
                )
                .bodyToMono(Void.class);
    }

    /** Encrypts DR backup content via Vault's transit engine (encryption-as-a-service) - the raw key
     * never touches raum's process. Returns Vault's own {@code vault:v1:...} ciphertext envelope. */
    public Mono<String> encrypt(byte[] plaintext) {
        String encoded = Base64.getEncoder().encodeToString(plaintext);
        return ensureTransitKey().then(webClient.post()
                .uri("/v1/transit/encrypt/{key}", TRANSIT_KEY)
                .bodyValue(Map.of("plaintext", encoded))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            log.error("encrypt FAILED [{}]: {}", response.statusCode(), body);
                            return Mono.error(new RuntimeException("encrypt failed: " + body));
                        })
                )
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(response -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) response.get("data");
                    return (String) data.get("ciphertext");
                }));
    }

    /** Reverses {@link #encrypt(byte[])}. */
    public Mono<byte[]> decrypt(String ciphertext) {
        return ensureTransitKey().then(webClient.post()
                .uri("/v1/transit/decrypt/{key}", TRANSIT_KEY)
                .bodyValue(Map.of("ciphertext", ciphertext))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            log.error("decrypt FAILED [{}]: {}", response.statusCode(), body);
                            return Mono.error(new RuntimeException("decrypt failed: " + body));
                        })
                )
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(response -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) response.get("data");
                    return Base64.getDecoder().decode((String) data.get("plaintext"));
                }));
    }

    public Mono<S3CredentialsDTO> getBackupS3Credentials() {
        return webClient.get()
                .uri("/v1/{mount}/data/dr-backup/s3", kvMount)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            log.error("getBackupS3Credentials FAILED [{}]: {}", response.statusCode(), body);
                            return Mono.error(new RuntimeException("getBackupS3Credentials failed: " + body));
                        })
                )
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(response -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> outer = (Map<String, Object>) response.get("data");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) outer.get("data");
                    return new S3CredentialsDTO(
                            (String) data.get("endpoint"),
                            (String) data.get("bucket"),
                            (String) data.get("access_key"),
                            (String) data.get("secret_key")
                    );
                });
    }
}