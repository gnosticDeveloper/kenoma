package bime;

import bime.clients.RaumClient;
import bime.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class ProductVariantIT extends BaseIT {

    @LocalServerPort
    int port;

    @MockitoBean
    RaumClient raumClient;

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
    void createVariant_rejectsDuplicateOptionCombination() {
        Setup s = buildProductWithMetadata();

        ProductVariantRequestDTO first = new ProductVariantRequestDTO();
        first.setOptionIds(List.of(s.optionId));
        first.setSku("VAR-DUP-1");
        client.post().uri("/products/{productId}/variants", s.productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(first)
                .exchange()
                .expectStatus().isOk();

        ProductVariantRequestDTO duplicate = new ProductVariantRequestDTO();
        duplicate.setOptionIds(List.of(s.optionId));
        duplicate.setSku("VAR-DUP-2");
        client.post().uri("/products/{productId}/variants", s.productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(duplicate)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void createVariant_rejectsDuplicateNoOptionCombination() {
        // Same rule for the "standard" (zero-option) variant shape.
        ProductResponseDTO product = createProduct("SKU-DUP-STD", "No Metadata Product");

        ProductVariantRequestDTO first = new ProductVariantRequestDTO();
        first.setOptionIds(List.of());
        first.setSku("VAR-STD-1");
        client.post().uri("/products/{productId}/variants", product.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(first)
                .exchange()
                .expectStatus().isOk();

        ProductVariantRequestDTO duplicate = new ProductVariantRequestDTO();
        duplicate.setOptionIds(List.of());
        duplicate.setSku("VAR-STD-2");
        client.post().uri("/products/{productId}/variants", product.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(duplicate)
                .exchange()
                .expectStatus().isBadRequest();
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

    @Test
    void batchUpdatePrices_viewerRole_returns403() {
        mockViewerJwt();
        VariantPriceUpdateDTO item = new VariantPriceUpdateDTO();
        item.setVariantId(UUID.randomUUID());
        item.setPrice(new BigDecimal("10.00"));
        VariantBatchPriceRequestDTO dto = new VariantBatchPriceRequestDTO();
        dto.setItems(List.of(item));

        client.patch().uri("/variants/pricing/batch")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void batchUpdatePrices_noAuth_returns401() {
        VariantPriceUpdateDTO item = new VariantPriceUpdateDTO();
        item.setVariantId(UUID.randomUUID());
        item.setPrice(new BigDecimal("10.00"));
        VariantBatchPriceRequestDTO dto = new VariantBatchPriceRequestDTO();
        dto.setItems(List.of(item));

        client.patch().uri("/variants/pricing/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void batchUpdatePrices_rejectsNegativeOrZeroPrice() {
        when(raumClient.getOrgCurrency(eq(ORG_ID), any()))
                .thenReturn(Mono.just(new OrgCurrencyDTO("ARS", "MANUAL", "USD")));
        Setup s = buildProductWithMetadata();
        ProductVariantResponseDTO variant = createVariant(s.productId, s.optionId, "PRICE-VAR-NEG");

        VariantPriceUpdateDTO negative = new VariantPriceUpdateDTO();
        negative.setVariantId(variant.getId());
        negative.setPrice(new BigDecimal("-1.00"));
        VariantBatchPriceRequestDTO negativeDto = new VariantBatchPriceRequestDTO();
        negativeDto.setItems(List.of(negative));

        client.patch().uri("/variants/pricing/batch")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(negativeDto)
                .exchange()
                .expectStatus().isBadRequest();

        VariantPriceUpdateDTO zero = new VariantPriceUpdateDTO();
        zero.setVariantId(variant.getId());
        zero.setPrice(BigDecimal.ZERO);
        VariantBatchPriceRequestDTO zeroDto = new VariantBatchPriceRequestDTO();
        zeroDto.setItems(List.of(zero));

        client.patch().uri("/variants/pricing/batch")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(zeroDto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void createVariant_rejectsNegativeOrZeroPrice() {
        Setup s = buildProductWithMetadata();

        ProductVariantRequestDTO dto = new ProductVariantRequestDTO();
        dto.setOptionIds(List.of(s.optionId));
        dto.setSku("PRICE-VAR-CREATE-NEG");
        dto.setPrice(new BigDecimal("-5.00"));
        dto.setPriceCurrency("USD");

        client.post().uri("/products/{productId}/variants", s.productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void batchUpdatePrices_updatesVariantsInOrg_rejectsForeignVariant() {
        when(raumClient.getOrgCurrency(eq(ORG_ID), any()))
                .thenReturn(Mono.just(new OrgCurrencyDTO("ARS", "MANUAL", "USD")));

        Setup s = buildProductWithMetadata();
        ProductVariantResponseDTO variant1 = createVariant(s.productId, s.optionId, "PRICE-VAR-1");

        Setup s2 = buildProductWithMetadata();
        ProductVariantResponseDTO variant2 = createVariant(s2.productId, s2.optionId, "PRICE-VAR-2");

        UUID foreignVariantId = UUID.randomUUID();

        VariantPriceUpdateDTO item1 = new VariantPriceUpdateDTO();
        item1.setVariantId(variant1.getId());
        item1.setPrice(new BigDecimal("10.00"));
        VariantPriceUpdateDTO item2 = new VariantPriceUpdateDTO();
        item2.setVariantId(variant2.getId());
        item2.setPrice(new BigDecimal("20.00"));

        VariantBatchPriceRequestDTO happyDto = new VariantBatchPriceRequestDTO();
        happyDto.setItems(List.of(item1, item2));

        client.patch().uri("/variants/pricing/batch")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(happyDto)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UUID.class)
                .hasSize(2)
                .contains(variant1.getId(), variant2.getId());

        ProductVariantResponseDTO refetched = client.get()
                .uri("/products/{productId}/variants/{variantId}", s.productId, variant1.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(refetched).isNotNull();
        assertThat(refetched.getPrice()).isEqualByComparingTo("10.00");
        assertThat(refetched.getPriceCurrency()).isEqualTo("USD");

        VariantPriceUpdateDTO foreignItem = new VariantPriceUpdateDTO();
        foreignItem.setVariantId(foreignVariantId);
        foreignItem.setPrice(new BigDecimal("5.00"));
        VariantBatchPriceRequestDTO mixedDto = new VariantBatchPriceRequestDTO();
        mixedDto.setItems(List.of(item1, foreignItem));

        client.patch().uri("/variants/pricing/batch")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(mixedDto)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void getVariantById_withCurrencyParam_convertsPrice() {
        when(raumClient.getOrgCurrency(eq(ORG_ID), any()))
                .thenReturn(Mono.just(new OrgCurrencyDTO("ARS", "MANUAL", "USD")));
        when(raumClient.getRate(eq("USD"), eq("ARS"), any()))
                .thenReturn(Mono.just(new BigDecimal("1000")));

        Setup s = buildProductWithMetadata();
        ProductVariantResponseDTO variant = createVariant(s.productId, s.optionId, "PRICE-VAR-CONV");

        VariantPriceUpdateDTO item = new VariantPriceUpdateDTO();
        item.setVariantId(variant.getId());
        item.setPrice(new BigDecimal("10.00"));
        VariantBatchPriceRequestDTO dto = new VariantBatchPriceRequestDTO();
        dto.setItems(List.of(item));
        client.patch().uri("/variants/pricing/batch")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk();

        ProductVariantResponseDTO converted = client.get()
                .uri("/products/{productId}/variants/{variantId}?currency=ARS", s.productId, variant.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(converted).isNotNull();
        assertThat(converted.getPrice()).isEqualByComparingTo("10000.00");
        assertThat(converted.getPriceCurrency()).isEqualTo("ARS");
    }

    @Test
    void getVariantById_withCurrencyParam_missingRate_returns404NotCrash() {
        // raumClient.getRate completing empty (no rate on file for this pair) must surface as a
        // clean error, not an NPE from multiplying by a null rate.
        when(raumClient.getOrgCurrency(eq(ORG_ID), any()))
                .thenReturn(Mono.just(new OrgCurrencyDTO("ARS", "MANUAL", "USD")));
        when(raumClient.getRate(eq("USD"), eq("XYZ"), any()))
                .thenReturn(Mono.empty());

        Setup s = buildProductWithMetadata();
        ProductVariantResponseDTO variant = createVariant(s.productId, s.optionId, "PRICE-VAR-NORATE");

        VariantPriceUpdateDTO item = new VariantPriceUpdateDTO();
        item.setVariantId(variant.getId());
        item.setPrice(new BigDecimal("10.00"));
        VariantBatchPriceRequestDTO dto = new VariantBatchPriceRequestDTO();
        dto.setItems(List.of(item));
        client.patch().uri("/variants/pricing/batch")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk();

        client.get()
                .uri("/products/{productId}/variants/{variantId}?currency=XYZ", s.productId, variant.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void getVariantById_withCurrencyParam_sameAsStoredCurrency_skipsRateLookup() {
        when(raumClient.getOrgCurrency(eq(ORG_ID), any()))
                .thenReturn(Mono.just(new OrgCurrencyDTO("USD", "MANUAL", "USD")));

        Setup s = buildProductWithMetadata();
        ProductVariantResponseDTO variant = createVariant(s.productId, s.optionId, "PRICE-VAR-SAME");

        VariantPriceUpdateDTO item = new VariantPriceUpdateDTO();
        item.setVariantId(variant.getId());
        item.setPrice(new BigDecimal("10.00"));
        VariantBatchPriceRequestDTO dto = new VariantBatchPriceRequestDTO();
        dto.setItems(List.of(item));
        client.patch().uri("/variants/pricing/batch")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk();

        ProductVariantResponseDTO result = client.get()
                .uri("/products/{productId}/variants/{variantId}?currency=USD", s.productId, variant.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.getPrice()).isEqualByComparingTo("10.00");
        assertThat(result.getPriceCurrency()).isEqualTo("USD");
        org.mockito.Mockito.verify(raumClient, org.mockito.Mockito.never()).getRate(any(), any(), any());
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
