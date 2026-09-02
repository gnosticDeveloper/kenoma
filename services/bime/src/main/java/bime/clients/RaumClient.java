package bime.clients;

import bime.dto.OrgCurrencyDTO;
import bime.openbao.OpenBaoService;
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

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class RaumClient {

    private final WebClient webClient;
    private final OpenBaoService openBaoService;

    public RaumClient(@Value("${raum.base-url}") String raumBaseUrl, OpenBaoService openBaoService) {
        this.webClient = WebClient.builder()
                .baseUrl(raumBaseUrl)
                .build();
        this.openBaoService = openBaoService;
    }

    /**
     * Request-path credential fetch: forwards the end-user's JWT (for org + roles → tier) AND
     * Bime's own service AppRole token (which raum now requires as proof a real service is asking).
     */
    public Mono<CredentialsDTO> getEphemeralCredentials(BasicCredentialDTO request) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ((BimeAuthentication) ctx.getAuthentication()).getJwtToken())
                .flatMap(jwt -> webClient.post()
                        .uri("/credentials/ephemeral")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                        .header("X-Vault-Token", openBaoService.getToken())
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

    /** Org's base currency and refresh mode, needed to stamp variant prices at write time. Vault-token authenticated. */
    public Mono<OrgCurrencyDTO> getOrgCurrency(UUID orgId, String vaultToken) {
        return webClient.get()
                .uri("/orgs/{orgId}/currency", orgId)
                .header("X-Vault-Token", vaultToken)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response ->
                        Mono.error(new NotFoundException("Organization not found")))
                .bodyToMono(OrgCurrencyDTO.class);
    }

    /**
     * Conversion rate between two currencies, from raum's stored exchange_rates table (never a
     * live external call). Returns just the rate so callers can apply it to many values locally
     * (e.g. a variant listing). Vault-token authenticated.
     */
    public Mono<BigDecimal> getRate(String fromCurrency, String toCurrency, String vaultToken) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/pricing/rate")
                        .queryParam("from", fromCurrency)
                        .queryParam("to", toCurrency)
                        .build())
                .header("X-Vault-Token", vaultToken)
                .retrieve()
                .bodyToMono(BigDecimal.class);
    }
}
