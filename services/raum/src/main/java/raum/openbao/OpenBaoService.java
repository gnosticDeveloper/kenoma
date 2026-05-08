package raum.openbao;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import raum.DTO.CredentialsDTO;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Service
public class OpenBaoService {

    private final WebClient webClient;
    private final String encryptionKeyName;

    public OpenBaoService(
            @Value("${openbao.host}") String host,
            @Value("${openbao.token}") String token,
            @Value("${openbao.transit.credential-key-name}") String encryptionKeyName) {
        this.webClient = WebClient.builder()
                .baseUrl(host)
                .defaultHeader("X-Vault-Token", token)
                .build();
        this.encryptionKeyName = encryptionKeyName;
    }

    public Mono<byte[]> encrypt(String plaintext) {
        String encoded = Base64.getEncoder().encodeToString(plaintext.getBytes());
        return webClient.post()
                .uri("/v1/transit/encrypt/{key}", encryptionKeyName)
                .bodyValue(Map.of("plaintext", encoded))
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    Map<?, ?> data = (Map<?, ?>) response.get("data");
                    return ((String) data.get("ciphertext")).getBytes();
                });
    }

    public Mono<String> decrypt(String ciphertext) {
        return webClient.post()
                .uri("/v1/transit/decrypt/{key}", encryptionKeyName)
                .bodyValue(Map.of("ciphertext", ciphertext))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            System.err.println("OPENBAO 400 ERROR BODY: " + body);
                            return Mono.error(new RuntimeException("Bad Request: " + body));
                        })
                )
                .bodyToMono(Map.class)
                .map(response -> {
                    Map<?, ?> data = (Map<?, ?>) response.get("data");
                    String encoded = (String) data.get("plaintext");
                    return new String(Base64.getDecoder().decode(encoded));
                });
    }

    public Mono<CredentialsDTO> issueEphemeralCredentials(
            String adminUser, String adminPassword,
            String dbHost, int dbPort, String dbName) {
        String connectionName = "raum-" + UUID.randomUUID();
        String roleName = connectionName + "-role";
        String connectionUrl = String.format(
                "postgresql://{{username}}:{{password}}@%s:%d/%s?sslmode=disable",
                dbHost, dbPort, dbName);

        return registerDb(connectionName, roleName, connectionUrl, adminUser, adminPassword)
                .then(createRole(connectionName, roleName, dbName))  // <-- pass dbName
                .then(issueCredentials(roleName))
                .flatMap(creds -> deregisterDb(connectionName, roleName).thenReturn(creds))
                .onErrorResume(e -> deregisterDb(connectionName, roleName).then(Mono.error(e)));
    }

    private Mono<Void> registerDb(String connectionName, String roleName,
                                  String connectionUrl, String adminUser, String adminPassword) {
        return webClient.post()
                .uri("/v1/database/config/{name}", connectionName)
                .bodyValue(Map.of(
                        "plugin_name", "postgresql-database-plugin",
                        "allowed_roles", roleName,
                        "connection_url", connectionUrl,
                        "username", adminUser,
                        "password", adminPassword
                ))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            System.err.println("registerDb FAILED [" + response.statusCode() + "]: " + body);
                            return Mono.error(new RuntimeException("registerDb failed: " + body));
                        })
                )
                .bodyToMono(Void.class)
                .doOnSuccess(v -> System.err.println("registerDb OK: " + connectionName))
                .doOnError(e -> System.err.println("registerDb ERROR: " + e.getMessage()));
    }

    private Mono<Void> createRole(String connectionName, String roleName, String dbName) {
        return webClient.post()
                .uri("/v1/database/roles/{role}", roleName)
                .bodyValue(Map.of(
                        "db_name", connectionName,
                        "creation_statements", "CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; GRANT CONNECT ON DATABASE \"" + dbName + "\" TO \"{{name}}\";",
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
                .bodyToMono(Void.class)
                .doOnSuccess(v -> System.err.println("createRole OK: " + roleName))
                .doOnError(e -> System.err.println("createRole ERROR: " + e.getMessage()));
    }

    private Mono<CredentialsDTO> issueCredentials(String roleName) {
        return webClient.get()
                .uri("/v1/database/creds/{role}", roleName)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            System.err.println("issueCredentials FAILED [" + response.statusCode() + "]: " + body);
                            return Mono.error(new RuntimeException("issueCredentials failed: " + body));
                        })
                )
                .bodyToMono(Map.class)
                .doOnError(e -> System.err.println("issueCredentials ERROR: " + e.getMessage()))
                .map(response -> {
                    Map<?, ?> data = (Map<?, ?>) response.get("data");
                    return CredentialsDTO.builder()
                            .userName((String) data.get("username"))
                            .password((String) data.get("password"))
                            .build();
                });
    }

    private Mono<Void> deregisterDb(String connectionName, String roleName) {
        return webClient.delete()
                .uri("/v1/database/roles/{role}", roleName)
                .retrieve()
                .bodyToMono(Void.class)
                .then(webClient.delete()
                        .uri("/v1/database/config/{name}", connectionName)
                        .retrieve()
                        .bodyToMono(Void.class));
    }
}