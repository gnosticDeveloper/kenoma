package raum.openbao;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import common.dto.CredentialsDTO;
import reactor.core.publisher.Mono;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
                            System.err.println("renewSelf FAILED [" + response.statusCode() + "]: " + body);
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
                            System.err.println("storeCredentials FAILED [" + response.statusCode() + "]: " + body);
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
                            System.err.println("registerDatabaseConnection FAILED [" + response.statusCode() + "]: " + body);
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
                            System.err.println("createRole FAILED [" + response.statusCode() + "]: " + body);
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
                            System.err.println("getStaticCredentials FAILED [" + response.statusCode() + "]: " + body);
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
        String roleName = id + "-role";
        return webClient.get()
                .uri("/v1/database/creds/{role}", roleName)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            System.err.println("issueEphemeralCredentials FAILED [" + response.statusCode() + "]: " + body);
                            return Mono.error(new RuntimeException("issueEphemeralCredentials failed: " + body));
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
}