package raum.clients;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@Component
public class BimeClient {

    private final WebClient webClient;

    public BimeClient(@Value("${bime.base-url}") String bimeBaseUrl) {
        this.webClient = WebClient.builder().baseUrl(bimeBaseUrl).build();
    }

    public Mono<UUID> createLocation(String jwt, String name, String code) {
        return webClient.post()
                .uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .bodyValue(new LocationRequest(name, code, true))
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .flatMap(body -> Mono.error(new RuntimeException("Bime createLocation failed: " + body))))
                .bodyToMono(IdResponse.class)
                .map(IdResponse::id);
    }

    public Mono<UUID> createProduct(String jwt, String sku, String name, String description) {
        return webClient.post()
                .uri("/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .bodyValue(new ProductRequest(sku, name, description, true))
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .flatMap(body -> Mono.error(new RuntimeException("Bime createProduct failed: " + body))))
                .bodyToMono(IdResponse.class)
                .map(IdResponse::id);
    }

    public Mono<UUID> createMetadata(String jwt, String name) {
        return webClient.post()
                .uri("/metadata")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .bodyValue(new MetadataRequest(name))
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .flatMap(body -> Mono.error(new RuntimeException("Bime createMetadata failed: " + body))))
                .bodyToMono(IdResponse.class)
                .map(IdResponse::id);
    }

    public Mono<UUID> addOption(String jwt, UUID metadataId, String value) {
        return webClient.post()
                .uri("/metadata/{id}/options", metadataId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .bodyValue(new OptionRequest(value))
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .flatMap(body -> Mono.error(new RuntimeException("Bime addOption failed: " + body))))
                .bodyToMono(IdResponse.class)
                .map(IdResponse::id);
    }

    public Mono<Void> assignMetadata(String jwt, UUID productId, List<MetadataAssignmentItem> assignments) {
        return webClient.put()
                .uri("/products/{id}/metadata", productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .bodyValue(assignments)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .flatMap(body -> Mono.error(new RuntimeException("Bime assignMetadata failed: " + body))))
                .bodyToMono(Void.class);
    }

    public Mono<UUID> createVariant(String jwt, UUID productId, List<UUID> optionIds, String sku) {
        return webClient.post()
                .uri("/products/{productId}/variants", productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .bodyValue(new VariantRequest(optionIds, sku, true))
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .flatMap(body -> Mono.error(new RuntimeException("Bime createVariant failed: " + body))))
                .bodyToMono(IdResponse.class)
                .map(IdResponse::id);
    }

    public Mono<Void> recordStockMovement(String jwt, UUID variantId, UUID locationId, int delta) {
        return webClient.post()
                .uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .bodyValue(new StockMovementRequest(variantId, locationId, "INBOUND", delta, "Initial preset stock"))
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .flatMap(body -> Mono.error(new RuntimeException("Bime recordStockMovement failed: " + body))))
                .bodyToMono(Void.class);
    }

    record LocationRequest(String name, String code, Boolean isActive) {}
    record ProductRequest(String sku, String name, String description, Boolean isActive) {}
    record MetadataRequest(String name) {}
    record OptionRequest(String value) {}
    record VariantRequest(List<UUID> optionIds, String sku, Boolean isActive) {}
    record StockMovementRequest(UUID variantId, UUID locationId, String movementType, int delta, String note) {}
    public record MetadataAssignmentItem(UUID metadataId, List<UUID> optionIds) {}
    record IdResponse(UUID id) {}
}
