package raum.openbao;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import raum.DTO.CredentialsDTO;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@Service
public class OpenBaoService {

    private final WebClient webClient;
    private final String kvMount;

    public OpenBaoService(
            @Value("${openbao.host}") String host,
            @Value("${openbao.token}") String token,
            @Value("${openbao.kv.mount}") String kvMount) {
        this.webClient = WebClient.builder()
                .baseUrl(host)
                .defaultHeader("X-Vault-Token", token)
                .build();
        this.kvMount = kvMount;
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
                .then(createRole(connectionName, roleName, dbName));
    }

    // one of these will be created per credential registered
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
                .bodyToMono(Void.class);
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
                .bodyToMono(Map.class)
                .map(response -> {
                    Map<?, ?> data = (Map<?, ?>) response.get("data");
                    return CredentialsDTO.builder()
                            .userName((String) data.get("username"))
                            .password((String) data.get("password"))
                            .build();
                });
    }
}