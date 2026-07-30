package bime.clients;

import bime.security.BimeAuthentication;
import common.dto.BasicCredentialDTO;
import common.dto.CredentialsDTO;
import common.exception.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class RaumClient {

    private final WebClient webClient;

    public RaumClient(@Value("${raum.base-url}") String raumBaseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(raumBaseUrl)
                .build();
    }

    public Mono<CredentialsDTO> getEphemeralCredentials(BasicCredentialDTO request) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ((BimeAuthentication) ctx.getAuthentication()).getJwtToken())
                .flatMap(jwt -> webClient.post()
                        .uri("/credentials/ephemeral")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                        .bodyValue(request)
                        .retrieve()
                        .onStatus(HttpStatusCode::is4xxClientError, response ->
                                Mono.error(new NotFoundException("No database credentials found for the requested organization")))
                        .bodyToMono(CredentialsDTO.class));
    }

    /**
     * Same endpoint as {@link #getEphemeralCredentials(BasicCredentialDTO)}, but authenticates
     * with Bime's own OpenBao AppRole token instead of a user JWT. Used by scheduled jobs, which
     * run with no {@code ReactiveSecurityContextHolder} context to pull a JWT from.
     */
    public Mono<CredentialsDTO> getEphemeralCredentials(BasicCredentialDTO request, String vaultToken) {
        return webClient.post()
                .uri("/credentials/ephemeral")
                .header("X-Vault-Token", vaultToken)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response ->
                        Mono.error(new NotFoundException("No database credentials found for the requested organization")))
                .bodyToMono(CredentialsDTO.class);
    }

    /** Lists active org IDs for the stock alert scheduler to iterate over. Vault-token authenticated. */
    public Flux<UUID> getActiveOrgIds(String vaultToken) {
        return webClient.get()
                .uri("/orgs/active-ids")
                .header("X-Vault-Token", vaultToken)
                .retrieve()
                .bodyToFlux(UUID.class);
    }
}
