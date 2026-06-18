package vassago.clients;

import common.dto.BasicCredentialDTO;
import common.dto.CredentialsDTO;
import common.exception.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class RaumClient {
    private final WebClient webClient;
    private final String openbaoToken;

    public RaumClient(
            @Value("${raum.base-url}") String raumBaseUrl,
            @Value("${vassago.openbao.token}") String openbaoToken) {
        this.webClient = WebClient.builder()
                .baseUrl(raumBaseUrl)
                .build();
        this.openbaoToken = openbaoToken;
    }

    public Mono<CredentialsDTO> getEphemeralCredentials(BasicCredentialDTO request) {
        return webClient.post()
                .uri("/credentials/ephemeral")
                .header("X-Vault-Token", openbaoToken)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response ->
                        Mono.error(new NotFoundException("No database credentials found for the requested organization")))
                .bodyToMono(CredentialsDTO.class);
    }
}