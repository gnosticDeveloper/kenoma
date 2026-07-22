package raum.clients;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class VassagoClient {

    private final WebClient webClient;

    public VassagoClient(@Value("${vassago.base-url}") String vassagoBaseUrl) {
        this.webClient = WebClient.builder().baseUrl(vassagoBaseUrl).build();
    }

    public Mono<String> login(UUID orgId, String username, String password) {
        return webClient.post()
                .uri("/auth/login")
                .bodyValue(new LoginRequest(orgId, username, password))
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .flatMap(body -> Mono.error(new RuntimeException("Vassago login failed: " + body))))
                .bodyToMono(LoginResponse.class)
                .map(LoginResponse::token);
    }

    public Mono<UUID> createUser(String jwt, String name, String lastName, String email, String username,
                                  Map<String, List<String>> roles, String locale) {
        return webClient.post()
                .uri("/user")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .bodyValue(new UserRequest(email, name, lastName, username, roles, locale))
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .flatMap(body -> Mono.error(new RuntimeException("Vassago user creation failed: " + body))))
                .bodyToMono(UserResponse.class)
                .map(UserResponse::id);
    }

    public Mono<Void> deleteUser(String jwt, UUID userId) {
        return webClient.delete()
                .uri("/user/{id}", userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .flatMap(body -> Mono.error(new RuntimeException("Vassago user deletion failed: " + body))))
                .bodyToMono(Void.class);
    }

    record LoginRequest(UUID orgId, String username, String password) {}
    record LoginResponse(String token) {}
    record UserRequest(String email, String name, String lastName, String username, Map<String, List<String>> roles, String locale) {}
    record UserResponse(UUID id) {}
}
