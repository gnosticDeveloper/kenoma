package vassago.clients;

import common.dto.BasicCredentialDTO;
import common.dto.CredentialsDTO;
import common.exception.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import vassago.openbao.OpenBaoService;

import java.util.UUID;

@Component
public class RaumClient {
    private final WebClient webClient;
    private final OpenBaoService openBaoService;

    public RaumClient(
            @Value("${raum.base-url}") String raumBaseUrl,
            OpenBaoService openBaoService) {
        this.webClient = WebClient.builder()
                .baseUrl(raumBaseUrl)
                .build();
        this.openBaoService = openBaoService;
    }

    public Mono<CredentialsDTO> getEphemeralCredentials(BasicCredentialDTO request) {
        return webClient.post()
                .uri("/credentials/ephemeral")
                .header("X-Vault-Token", openBaoService.getToken())
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response ->
                        Mono.error(new NotFoundException("No database credentials found for the requested organization")))
                .bodyToMono(CredentialsDTO.class);
    }

    /** Used by {@link vassago.services.AuthService#login} to reject a deactivated org's user
     * explicitly, rather than relying on the DB connection pool incidentally being cold enough
     * to hit {@link #getEphemeralCredentials} and fail there. Fails closed (treats as inactive)
     * on any error reaching raum - this check exists to be a hard security gate, so an
     * unreachable raum should block login, not silently let it through unchecked. */
    public Mono<Boolean> isOrgActive(UUID orgId) {
        return webClient.get()
                .uri("/orgs/{id}/active", orgId)
                .header("X-Vault-Token", openBaoService.getToken())
                .retrieve()
                .bodyToMono(Boolean.class)
                .onErrorReturn(false);
    }
}