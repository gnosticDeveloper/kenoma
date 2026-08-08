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
                                                 String dbHost, int dbPort, String dbName) {
        String connectionName = id.toString();
        String roleName = connectionName + "-role";
        String connectionUrl = String.format(
                "postgresql://{{username}}:{{password}}@%s:%d/%s?sslmode=disable",
                dbHost, dbPort, dbName);

        return webClient.post()
                .uri("/v1/database/config/{name}", connectionName)
                .bodyValue(Map.of(
                        "plugin_name", "postgresql-database-plugin",
                        "allowed_roles", roleName,
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
                .then(createRole(connectionName, roleName, dbName, username));
    }

    private Mono<Void> createRole(String connectionName, String roleName, String dbName, String ownerUsername) {
        return webClient.post()
                .uri("/v1/database/roles/{role}", roleName)
                .bodyValue(Map.of(
                        "db_name", connectionName,
                        "creation_statements", "CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; " +
                                "GRANT CONNECT ON DATABASE \"" + dbName + "\" TO \"{{name}}\"; " +
                                "GRANT USAGE ON SCHEMA public TO \"{{name}}\"; " +
                                "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO \"{{name}}\";",
                        // Defensive: ephemeral roles are not expected to own any objects (schema DDL runs under the
                        // static credentials — see SchemaProvisioner.staticClientFor), but REASSIGN OWNED BY is a
                        // no-op if the role owns nothing, so this protects against the role ever ending up owning
                        // something and blocking its own revocation ("cannot be dropped because objects depend on it").
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
        return fetchDatabaseCreds(id + "-role");
    }

    /**
     * Ensures a read-only DR-backup role exists for an already-registered database connection
     * (see {@link #registerDatabaseConnection}). Reuses that connection rather than registering a
     * new one, since the physical instance behind it is shared across every org/service credential
     * row that points at the same (host, port, db_name) — one representative connection is enough.
     *
     * <p>The connection's {@code allowed_roles} was set at org-onboarding time to only the ephemeral
     * "{id}-role" — Vault refuses to issue creds for any role not on that list, so this must widen it
     * to include the backup role before (re)creating it. Re-registering the connection needs the same
     * admin credentials used originally, fetched back from the static KV entry.
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
                        "allowed_roles", connectionName + "-role," + backupRoleName,
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
                });
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