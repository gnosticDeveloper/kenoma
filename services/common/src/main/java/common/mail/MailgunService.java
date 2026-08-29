package common.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.UUID;

@Service
public class MailgunService {
    private final WebClient webClient;
    private final String domain;
    private final String from;
    private final String invoiceFrom;
    private final String stockAlertFrom;
    private final String appBaseUrl;

    public MailgunService(
            @Value("${mailgun.api-key}") String apiKey,
            @Value("${mailgun.domain}") String domain,
            @Value("${mailgun.from}") String from,
            @Value("${mailgun.invoice-from:${mailgun.from}}") String invoiceFrom,
            @Value("${mailgun.stock-alert-from:alerts@${mailgun.domain}}") String stockAlertFrom,
            @Value("${app.base-url}") String appBaseUrl) {
        this.domain = domain;
        this.from = from;
        this.invoiceFrom = invoiceFrom;
        this.stockAlertFrom = stockAlertFrom;
        this.appBaseUrl = appBaseUrl;
        this.webClient = WebClient.builder()
                .baseUrl("https://api.mailgun.net/v3")
                .defaultHeaders(headers -> headers.setBasicAuth("api", apiKey))
                .build();
    }

    public Mono<Void> sendLinkEmail(String toEmail, UUID orgId, String token, String locale,
                                     String subjectKey, String bodyKey, String linkType) {
        String link = "%s/verify?orgId=%s&token=%s&type=%s".formatted(appBaseUrl, orgId, token, linkType);
        ResourceBundle messages = messagesFor(locale);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("from", from);
        form.add("to", toEmail);
        form.add("subject", messages.getString(subjectKey));
        form.add("text", MessageFormat.format(messages.getString(bodyKey), link));

        return webClient.post()
                .uri("/{domain}/messages", domain)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .toBodilessEntity()
                .then();
    }

    public Mono<Void> sendPasswordResetEmail(String toEmail, UUID orgId, String token, String locale) {
        return sendLinkEmail(toEmail, orgId, token, locale, "reset.subject", "reset.body", "user");
    }

    public Mono<Void> sendVerificationEmail(String toEmail, UUID orgId, String token, String locale) {
        return sendLinkEmail(toEmail, orgId, token, locale, "verify.subject", "verify.body", "user");
    }

    public Mono<Void> sendBillingEmailVerification(String toEmail, UUID orgId, String token, String locale) {
        return sendLinkEmail(toEmail, orgId, token, locale, "billing_verify.subject", "billing_verify.body", "billing");
    }

    public Mono<Void> sendContactEmailVerification(String toEmail, UUID orgId, String token, String locale) {
        return sendLinkEmail(toEmail, orgId, token, locale, "contact_verify.subject", "contact_verify.body", "contact");
    }

    public Mono<Void> sendLocationNotificationEmailVerification(String toEmail, UUID orgId, String token, String locale) {
        return sendLinkEmail(toEmail, orgId, token, locale, "location_verify.subject", "location_verify.body", "location");
    }

    public Mono<Void> sendStockAlertEmail(String toEmail, String productLabel, String locationName,
                                           BigDecimal quantity, BigDecimal threshold, String locale) {
        ResourceBundle messages = messagesFor(locale);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("from", stockAlertFrom);
        form.add("to", toEmail);
        form.add("subject", MessageFormat.format(messages.getString("stock_alert.subject"), productLabel, locationName));
        form.add("text", MessageFormat.format(messages.getString("stock_alert.body"),
                productLabel, locationName, quantity, threshold));

        return webClient.post()
                .uri("/{domain}/messages", domain)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .toBodilessEntity()
                .then();
    }

    public Mono<Void> sendInvoiceEmail(String toEmail, byte[] pdfBytes, String fileName, String locale) {
        ResourceBundle messages = messagesFor(locale);

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("from", invoiceFrom);
        builder.part("to", toEmail);
        builder.part("subject", messages.getString("invoice.subject"));
        builder.part("text", messages.getString("invoice.body"));
        builder.part("attachment", new ByteArrayResource(pdfBytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        }).header(HttpHeaders.CONTENT_DISPOSITION, "form-data; name=attachment; filename=" + fileName);

        return webClient.post()
                .uri("/{domain}/messages", domain)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .toBodilessEntity()
                .then();
    }

    private static ResourceBundle messagesFor(String locale) {
        Locale target = locale == null || locale.isBlank() ? Locale.ENGLISH : Locale.forLanguageTag(locale);
        try {
            return ResourceBundle.getBundle("email", target);
        } catch (MissingResourceException e) {
            return ResourceBundle.getBundle("email", Locale.ENGLISH);
        }
    }
}
