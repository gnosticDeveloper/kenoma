package vassago.clients;

import common.dto.BasicCredentialDTO;
import common.dto.CredentialsDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class RaumClient {

        private final WebClient webClient;

        public RaumClient(@Value("${raum.base-url}") String raumBaseUrl) {
            this.webClient = WebClient.builder()
                    .baseUrl(raumBaseUrl)
                    .build();
        }

        public Mono<CredentialsDTO> getEphemeralCredentials(BasicCredentialDTO request) {
            return webClient.post()
                    .uri("/credentials/ephemeral")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(CredentialsDTO.class);
        }
}
