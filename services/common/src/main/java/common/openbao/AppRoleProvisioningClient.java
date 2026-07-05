package common.openbao;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * Reactive OpenBao client for self-provisioning: writing a service's own AppRole
 * policy/role, minting a secret-id, and logging in. Shared by any app that needs
 * to bootstrap its own working AppRole from a narrower "provisioner" credential
 * instead of relying on an external init script to do it.
 */
public class AppRoleProvisioningClient {

    public Mono<Void> writePolicy(WebClient provisionerClient, String policyName, String hcl) {
        return provisionerClient.put()
                .uri("/v1/sys/policies/acl/{name}", policyName)
                .bodyValue(Map.of("policy", hcl))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body ->
                                Mono.error(new IllegalStateException("writePolicy failed [" + response.statusCode() + "]: " + body))))
                .bodyToMono(Void.class);
    }

    public Mono<Void> ensureAppRole(WebClient provisionerClient, String roleName, String policyName,
                                     Duration tokenTtl, Duration tokenMaxTtl) {
        return provisionerClient.put()
                .uri("/v1/auth/approle/role/{role}", roleName)
                .bodyValue(Map.of(
                        "token_policies", policyName,
                        "token_ttl", tokenTtl.toSeconds() + "s",
                        "token_max_ttl", tokenMaxTtl.toSeconds() + "s",
                        "secret_id_ttl", "0"
                ))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body ->
                                Mono.error(new IllegalStateException("ensureAppRole failed [" + response.statusCode() + "]: " + body))))
                .bodyToMono(Void.class);
    }

    public Mono<String> readRoleId(WebClient provisionerClient, String roleName) {
        return provisionerClient.get()
                .uri("/v1/auth/approle/role/{role}/role-id", roleName)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body ->
                                Mono.error(new IllegalStateException("readRoleId failed [" + response.statusCode() + "]: " + body))))
                .bodyToMono(VaultResponse.class)
                .map(res -> (String) res.data().get("role_id"));
    }

    public Mono<String> generateSecretId(WebClient provisionerClient, String roleName) {
        return provisionerClient.post()
                .uri("/v1/auth/approle/role/{role}/secret-id", roleName)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body ->
                                Mono.error(new IllegalStateException("generateSecretId failed [" + response.statusCode() + "]: " + body))))
                .bodyToMono(VaultResponse.class)
                .map(res -> (String) res.data().get("secret_id"));
    }

    public Mono<String> login(WebClient anonymousClient, String roleId, String secretId) {
        return anonymousClient.post()
                .uri("/v1/auth/approle/login")
                .bodyValue(Map.of("role_id", roleId, "secret_id", secretId))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body ->
                                Mono.error(new IllegalStateException("AppRole login failed [" + response.statusCode() + "]: " + body))))
                .bodyToMono(VaultLoginResponse.class)
                .map(res -> res.auth().get("client_token").toString());
    }

    private record VaultResponse(Map<String, Object> data) {
    }

    private record VaultLoginResponse(Map<String, Object> auth) {
    }
}
