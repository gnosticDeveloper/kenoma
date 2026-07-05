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
}