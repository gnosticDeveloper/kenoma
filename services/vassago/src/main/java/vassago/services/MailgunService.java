package vassago.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
public class MailgunService {
    private final WebClient webClient;
    private final String domain;
    private final String from;
    private final String appBaseUrl;

    public MailgunService(
            @Value("${mailgun.api-key}") String apiKey,
            @Value("${mailgun.domain}") String domain,
            @Value("${mailgun.from}") String from,
            @Value("${app.base-url}") String appBaseUrl) {
        this.domain = domain;
        this.from = from;
        this.appBaseUrl = appBaseUrl;
        this.webClient = WebClient.builder()
                .baseUrl("https://api.mailgun.net/v3")
                .defaultHeaders(headers -> headers.setBasicAuth("api", apiKey))
                .build();
    }

    public Mono<Void> sendPasswordResetEmail(String toEmail, UUID orgId, String token) {
        String link = "%s/verify?orgId=%s&token=%s".formatted(appBaseUrl, orgId, token);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("from", from);
        form.add("to", toEmail);
        form.add("subject", "Reset your password");
        form.add("text", "Reset your password by visiting: " + link);

        return webClient.post()
                .uri("/{domain}/messages", domain)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .toBodilessEntity()
                .then();
    }

    public Mono<Void> sendVerificationEmail(String toEmail, UUID orgId, String token) {
        String link = "%s/verify?orgId=%s&token=%s".formatted(appBaseUrl, orgId, token);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("from", from);
        form.add("to", toEmail);
        form.add("subject", "Confirm your account");
        form.add("text", "Set your password by visiting: " + link);

        return webClient.post()
                .uri("/{domain}/messages", domain)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .toBodilessEntity()
                .then();
    }
}
