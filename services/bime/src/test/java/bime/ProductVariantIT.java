package bime;

import bime.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProductVariantIT extends BaseIT {

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
    void createVariant_withValidOptions() {
        Setup s = buildProductWithMetadata();

        ProductVariantRequestDTO dto = new ProductVariantRequestDTO();
        dto.setOptionIds(List.of(s.optionId));
        dto.setSku("VAR-001");

        ProductVariantResponseDTO variant = client.post()
                .uri("/products/{productId}/variants", s.productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(variant).isNotNull();
        assertThat(variant.getId()).isNotNull();
        assertThat(variant.getSku()).isEqualTo("VAR-001");
        assertThat(variant.getProductId()).isEqualTo(s.productId);
        assertThat(variant.getOptions()).hasSize(1);
        assertThat(variant.getOptions().get(0).getId()).isEqualTo(s.optionId);
    }

    @Test
    void createVariant_rejectsOptionNotInPalette() {
        Setup s = buildProductWithMetadata();

        ProductMetadataResponseDTO otherMeta = createMetadata("Unassigned");
        MetadataOptionResponseDTO foreignOption = addOption(otherMeta.getId(), "Foreign");

        ProductVariantRequestDTO dto = new ProductVariantRequestDTO();
        dto.setOptionIds(List.of(foreignOption.getId()));

        client.post().uri("/products/{productId}/variants", s.productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void createVariant_rejectsMissingMetadataKey() {
        ProductResponseDTO product = createProduct("SKU-MISS", "Missing Meta Product");

        ProductMetadataResponseDTO meta1 = createMetadata("Size");
        MetadataOptionResponseDTO opt1 = addOption(meta1.getId(), "Small");
        ProductMetadataResponseDTO meta2 = createMetadata("Color");
        addOption(meta2.getId(), "Red");

        ProductMetadataAssignmentItemDTO assign1 = new ProductMetadataAssignmentItemDTO();
        assign1.setMetadataId(meta1.getId());
        assign1.setOptionIds(List.of(opt1.getId()));
        ProductMetadataAssignmentItemDTO assign2 = new ProductMetadataAssignmentItemDTO();
        assign2.setMetadataId(meta2.getId());
        assign2.setOptionIds(List.of());

        client.put().uri("/products/{id}/metadata", product.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(List.of(assign1, assign2))
                .exchange()
                .expectStatus().isNoContent();

        ProductVariantRequestDTO dto = new ProductVariantRequestDTO();
        dto.setOptionIds(List.of(opt1.getId()));

        client.post().uri("/products/{productId}/variants", product.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void getVariantsForProduct_returnsVariants() {
        Setup s = buildProductWithMetadata();

        ProductVariantRequestDTO dto = new ProductVariantRequestDTO();
        dto.setOptionIds(List.of(s.optionId));
        dto.setSku("VAR-LIST");

        client.post().uri("/products/{productId}/variants", s.productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk();

        client.get().uri("/products/{productId}/variants", s.productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ProductVariantResponseDTO.class)
                .hasSize(1);
    }

    @Test
    void patchVariant_changesSku() {
        Setup s = buildProductWithMetadata();
        ProductVariantResponseDTO created = createVariant(s.productId, s.optionId, "OLD-SKU");

        ProductVariantRequestDTO patch = new ProductVariantRequestDTO();
        patch.setSku("NEW-SKU");

        ProductVariantResponseDTO updated = client.patch()
                .uri("/products/{productId}/variants/{variantId}", s.productId, created.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(patch)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(updated).isNotNull();
        assertThat(updated.getSku()).isEqualTo("NEW-SKU");
    }

    @Test
    void deactivateVariant_returns204() {
        Setup s = buildProductWithMetadata();
        ProductVariantResponseDTO created = createVariant(s.productId, s.optionId, "VAR-DEL");

        client.delete().uri("/products/{productId}/variants/{variantId}", s.productId, created.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void patchVariant_returns404_forUnknownId() {
        Setup s = buildProductWithMetadata();

        ProductVariantRequestDTO patch = new ProductVariantRequestDTO();
        patch.setSku("GHOST-SKU");

        client.patch().uri("/products/{productId}/variants/{variantId}", s.productId, UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(patch)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void deactivateVariant_returns404_forUnknownId() {
        Setup s = buildProductWithMetadata();

        client.delete().uri("/products/{productId}/variants/{variantId}", s.productId, UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    private record Setup(UUID productId, UUID optionId) {}

    private Setup buildProductWithMetadata() {
        ProductResponseDTO product = createProduct("SKU-VAR-" + UUID.randomUUID(), "Variant Test Product");
        ProductMetadataResponseDTO meta = createMetadata("Type-" + UUID.randomUUID());
        MetadataOptionResponseDTO option = addOption(meta.getId(), "Standard");

        ProductMetadataAssignmentItemDTO item = new ProductMetadataAssignmentItemDTO();
        item.setMetadataId(meta.getId());
        item.setOptionIds(List.of(option.getId()));

        client.put().uri("/products/{id}/metadata", product.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(List.of(item))
                .exchange()
                .expectStatus().isNoContent();

        return new Setup(product.getId(), option.getId());
    }

    private ProductVariantResponseDTO createVariant(UUID productId, UUID optionId, String sku) {
        ProductVariantRequestDTO dto = new ProductVariantRequestDTO();
        dto.setOptionIds(List.of(optionId));
        dto.setSku(sku);
        ProductVariantResponseDTO response = client.post()
                .uri("/products/{productId}/variants", productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private ProductResponseDTO createProduct(String sku, String name) {
        ProductRequestDTO dto = new ProductRequestDTO();
        dto.setSku(sku);
        dto.setName(name);
        dto.setIsActive(true);
        ProductResponseDTO response = client.post().uri("/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
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
}
