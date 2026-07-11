package vassago.openbao;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class OpenBaoService {
    private final WebClient webClient;
    private final AtomicReference<String> token = new AtomicReference<>();

    public OpenBaoService(@Value("${openbao.base-url}") String host) {
        this.webClient = WebClient.builder()
                .baseUrl(host)
                .filter((request, next) -> next.exchange(
                        ClientRequest.from(request)
                                .headers(headers -> headers.set("X-Vault-Token", token.get()))
                                .build()))
                .build();
    }

    /** Shared client whose X-Vault-Token header always reflects the current token. */
    public WebClient client() {
        return webClient;
    }

    public String getToken() {
        return token.get();
    }

    /** Replaces the token used for subsequent requests, following a (re)provisioning login or renewal. */
    public void setToken(String newToken) {
        token.set(newToken);
    }

    public Mono<Void> renewSelf() {
        return webClient.post()
                .uri("/v1/auth/token/renew-self")
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            log.error("renewSelf FAILED [{}]: {}", response.statusCode(), body);
                            return Mono.error(new RuntimeException("renewSelf failed: " + body));
                        })
                )
                .bodyToMono(Void.class);
    }
}
