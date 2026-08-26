package bime;

import bime.dto.MetadataOptionRequestDTO;
import bime.dto.MetadataOptionResponseDTO;
import bime.dto.ProductMetadataAssignmentItemDTO;
import bime.dto.ProductMetadataRequestDTO;
import bime.dto.ProductMetadataResponseDTO;
import bime.dto.ProductRequestDTO;
import bime.dto.ProductResponseDTO;
import bime.dto.ProductVariantRequestDTO;
import bime.dto.ProductVariantResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProductIT extends BaseIT {

    @LocalServerPort
    int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(15))
                .build();
        mockAdminJwt();
    }

    @Test
    void createProduct_persistsToDatabase() {
        ProductResponseDTO response = client.post().uri("/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(productRequest("SKU-001", "Widget"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.getId()).isNotNull();
        assertThat(response.getSku()).isEqualTo("SKU-001");
        assertThat(response.getName()).isEqualTo("Widget");
        assertThat(response.getOrgId()).isEqualTo(ORG_ID);
        assertThat(response.getIsActive()).isTrue();
    }

    @Test
    void getProductById_returnsProduct() {
        ProductResponseDTO created = createProduct("SKU-002", "Gadget");

        ProductResponseDTO response = client.get().uri("/products/{id}", created.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(created.getId());
        assertThat(response.getSku()).isEqualTo("SKU-002");
    }

    @Test
    void getProducts_returnsAll() {
        createProduct("SKU-A", "Product A");
        createProduct("SKU-B", "Product B");

        client.get().uri("/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ProductResponseDTO.class)
                .hasSize(2);
    }

    @Test
    void getProducts_variantCount_reflectsActualVariants() {
        ProductResponseDTO noVariants = createProduct("SKU-NOVAR", "No Variants Product");
        ProductResponseDTO withVariant = createProduct("SKU-WITHVAR", "With Variant Product");

        List<ProductResponseDTO> beforeVariant = client.get().uri("/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ProductResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(beforeVariant).isNotNull();
        assertThat(findBySku(beforeVariant, "SKU-NOVAR").getVariantCount()).isZero();
        assertThat(findBySku(beforeVariant, "SKU-WITHVAR").getVariantCount()).isZero();

        // withVariant has no assigned metadata, so an empty option set is a complete ("standard")
        // variant.
        ProductVariantRequestDTO variantDto = new ProductVariantRequestDTO();
        variantDto.setOptionIds(List.of());
        ProductVariantResponseDTO variant = client.post().uri("/products/{id}/variants", withVariant.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(variantDto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(variant).isNotNull();

        List<ProductResponseDTO> afterVariant = client.get().uri("/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ProductResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(afterVariant).isNotNull();
        assertThat(findBySku(afterVariant, "SKU-NOVAR").getVariantCount()).isZero();
        assertThat(findBySku(afterVariant, "SKU-WITHVAR").getVariantCount()).isEqualTo(1);
    }

    private static ProductResponseDTO findBySku(List<ProductResponseDTO> products, String sku) {
        return products.stream()
                .filter(p -> sku.equals(p.getSku()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(sku + " not found in product list"));
    }

    @Test
    void updateProduct_changesFields() {
        ProductResponseDTO created = createProduct("SKU-OLD", "Old Product");

        ProductRequestDTO update = new ProductRequestDTO();
        update.setSku("SKU-NEW");
        update.setName("New Product");
        update.setDescription("Updated description");
        update.setIsActive(true);

        ProductResponseDTO updated = client.put().uri("/products/{id}", created.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(update)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(updated).isNotNull();
        assertThat(updated.getSku()).isEqualTo("SKU-NEW");
        assertThat(updated.getName()).isEqualTo("New Product");
        assertThat(updated.getDescription()).isEqualTo("Updated description");
    }

    @Test
    void deactivateProduct_returns204() {
        ProductResponseDTO created = createProduct("SKU-DEL", "To Deactivate");

        client.delete().uri("/products/{id}", created.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void getProduct_returns404_forUnknownId() {
        client.get().uri("/products/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void updateProduct_returns404_forUnknownId() {
        ProductRequestDTO dto = new ProductRequestDTO();
        dto.setSku("GHOST-SKU");
        dto.setName("Ghost");
        dto.setIsActive(true);

        client.put().uri("/products/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void deactivateProduct_returns404_forUnknownId() {
        client.delete().uri("/products/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void createProduct_returns409_forDuplicateSku() {
        createProduct("DUPE-SKU", "First");

        client.post().uri("/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(productRequest("DUPE-SKU", "Second"))
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void getProducts_filterByOptionIds_returnsOnlyMatching() {
        ProductMetadataResponseDTO meta = createMetadata("Color-" + UUID.randomUUID());
        MetadataOptionResponseDTO option = addOption(meta.getId(), "Red");

        ProductResponseDTO matching = createProduct("SKU-FILT-MATCH-" + UUID.randomUUID(), "Matching Product");
        ProductResponseDTO nonMatching = createProduct("SKU-FILT-NOMATCH-" + UUID.randomUUID(), "Non-matching Product");

        ProductMetadataAssignmentItemDTO item = new ProductMetadataAssignmentItemDTO();
        item.setMetadataId(meta.getId());
        item.setOptionIds(List.of(option.getId()));
        client.put().uri("/products/{id}/metadata", matching.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(List.of(item))
                .exchange()
                .expectStatus().isNoContent();

        List<ProductResponseDTO> filtered = client.get().uri(uriBuilder -> uriBuilder
                        .path("/products")
                        .queryParam("optionIds", option.getId())
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ProductResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(filtered).isNotNull();
        assertThat(filtered).extracting(ProductResponseDTO::getId).contains(matching.getId());
        assertThat(filtered).extracting(ProductResponseDTO::getId).doesNotContain(nonMatching.getId());
    }

    @Test
    void getProducts_matchAllTrue_requiresEveryOption_matchAllFalseMatchesAny() {
        ProductMetadataResponseDTO colorMeta = createMetadata("Color-" + UUID.randomUUID());
        MetadataOptionResponseDTO red = addOption(colorMeta.getId(), "Red");
        ProductMetadataResponseDTO sizeMeta = createMetadata("Size-" + UUID.randomUUID());
        MetadataOptionResponseDTO large = addOption(sizeMeta.getId(), "Large");

        // Has both Red and Large selected.
        ProductResponseDTO both = createProduct("SKU-BOTH-" + UUID.randomUUID(), "Both");
        assignMetadata(both.getId(), Map.of(colorMeta.getId(), red.getId(), sizeMeta.getId(), large.getId()));

        // Has only Red selected (no Size assignment at all).
        ProductResponseDTO redOnly = createProduct("SKU-REDONLY-" + UUID.randomUUID(), "Red only");
        assignMetadata(redOnly.getId(), Map.of(colorMeta.getId(), red.getId()));

        // Default (matchAll=false / OR): both products match since each has at least one of the options.
        List<ProductResponseDTO> anyMatch = client.get().uri(uriBuilder -> uriBuilder
                        .path("/products")
                        .queryParam("optionIds", red.getId())
                        .queryParam("optionIds", large.getId())
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ProductResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(anyMatch).isNotNull();
        assertThat(anyMatch).extracting(ProductResponseDTO::getId).contains(both.getId(), redOnly.getId());

        // matchAll=true: only the product with both options selected qualifies.
        List<ProductResponseDTO> allMatch = client.get().uri(uriBuilder -> uriBuilder
                        .path("/products")
                        .queryParam("optionIds", red.getId())
                        .queryParam("optionIds", large.getId())
                        .queryParam("matchAll", "true")
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ProductResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(allMatch).isNotNull();
        assertThat(allMatch).extracting(ProductResponseDTO::getId).contains(both.getId());
        assertThat(allMatch).extracting(ProductResponseDTO::getId).doesNotContain(redOnly.getId());
    }

    @Test
    void getProducts_filterByUnknownOptionId_returnsEmptyNotError() {
        createProduct("SKU-UNKNOWNOPT-" + UUID.randomUUID(), "Unrelated Product");

        client.get().uri(uriBuilder -> uriBuilder
                        .path("/products")
                        .queryParam("optionIds", UUID.randomUUID())
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ProductResponseDTO.class)
                .hasSize(0);
    }

    private void assignMetadata(UUID productId, Map<UUID, UUID> metadataIdToOptionId) {
        List<ProductMetadataAssignmentItemDTO> items = metadataIdToOptionId.entrySet().stream()
                .map(e -> {
                    ProductMetadataAssignmentItemDTO item = new ProductMetadataAssignmentItemDTO();
                    item.setMetadataId(e.getKey());
                    item.setOptionIds(List.of(e.getValue()));
                    return item;
                })
                .toList();
        client.put().uri("/products/{id}/metadata", productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(items)
                .exchange()
                .expectStatus().isNoContent();
    }

    private ProductMetadataResponseDTO createMetadata(String name) {
        ProductMetadataRequestDTO dto = new ProductMetadataRequestDTO();
        dto.setName(name);
        ProductMetadataResponseDTO response = client.post().uri("/metadata")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductMetadataResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private MetadataOptionResponseDTO addOption(UUID metadataId, String value) {
        MetadataOptionRequestDTO dto = new MetadataOptionRequestDTO();
        dto.setValue(value);
        MetadataOptionResponseDTO response = client.post().uri("/metadata/{id}/options", metadataId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(MetadataOptionResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    ProductResponseDTO createProduct(String sku, String name) {
        ProductResponseDTO response = client.post().uri("/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(productRequest(sku, name))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private static ProductRequestDTO productRequest(String sku, String name) {
        ProductRequestDTO dto = new ProductRequestDTO();
        dto.setSku(sku);
        dto.setName(name);
        dto.setIsActive(true);
        return dto;
    }
}
