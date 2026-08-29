package bime;

import bime.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UomConversionIT extends BaseIT {

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
        // "case"/"pallet" are custom (non-standard) units - the org's catalog must already have
        // them before a variant can reference them. org_units isn't truncated between tests (no
        // FK from it back to the tables BaseIT truncates), so registering is idempotent here.
        registerUnitIfAbsent("case");
        registerUnitIfAbsent("pallet");
    }

    private void registerUnitIfAbsent(String name) {
        OrgUnitRequestDTO dto = new OrgUnitRequestDTO();
        dto.setName(name);
        client.post().uri("/units")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange();
    }

    @Test
    void setConversion_persistsAndIsReadable() {
        UUID variantId = buildVariant();

        UomConversionResponseDTO response = putConversion(variantId, "case", 24);

        assertThat(response.getVariantId()).isEqualTo(variantId);
        assertThat(response.getUomName()).isEqualTo("case");
        assertThat(response.getFactor()).isEqualByComparingTo(BigDecimal.valueOf(24));

        List<UomConversionResponseDTO> listed = client.get().uri("/variants/{v}/uom-conversions", variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UomConversionResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).getUomName()).isEqualTo("case");
    }

    @Test
    void setConversion_isUpsert_secondCallReplacesFactor() {
        UUID variantId = buildVariant();
        putConversion(variantId, "case", 24);

        UomConversionResponseDTO updated = putConversion(variantId, "case", 12);

        assertThat(updated.getFactor()).isEqualByComparingTo(BigDecimal.valueOf(12));
        List<UomConversionResponseDTO> listed = client.get().uri("/variants/{v}/uom-conversions", variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UomConversionResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(listed).hasSize(1);
    }

    @Test
    void setConversion_rejectsNonPositiveFactor() {
        UUID variantId = buildVariant();
        UomConversionRequestDTO dto = new UomConversionRequestDTO();
        dto.setUomName("case");
        dto.setFactor(BigDecimal.ZERO);

        client.put().uri("/variants/{v}/uom-conversions", variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void setConversion_returns404_forUnknownVariant() {
        UomConversionRequestDTO dto = new UomConversionRequestDTO();
        dto.setUomName("case");
        dto.setFactor(BigDecimal.TEN);

        client.put().uri("/variants/{v}/uom-conversions", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void createVariant_withUomConversions_persistsThem() {
        UUID productId = createProductId();
        ProductVariantRequestDTO dto = new ProductVariantRequestDTO();
        dto.setOptionIds(List.of());
        dto.setUomConversions(List.of(uomConversion("case", 24), uomConversion("pallet", 480)));

        ProductVariantResponseDTO variant = createVariant(productId, dto);

        assertThat(variant.getUomConversions()).hasSize(2);
        assertThat(variant.getUomConversions())
                .extracting(UomConversionResponseDTO::getUomName)
                .containsExactlyInAnyOrder("case", "pallet");

        List<UomConversionResponseDTO> listed = client.get().uri("/variants/{v}/uom-conversions", variant.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UomConversionResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(listed).hasSize(2);
    }

    @Test
    void createVariant_withUomConversions_rejectsNonPositiveFactor() {
        UUID productId = createProductId();
        ProductVariantRequestDTO dto = new ProductVariantRequestDTO();
        dto.setOptionIds(List.of());
        dto.setUomConversions(List.of(uomConversion("case", 0)));

        createVariantExpectBadRequest(productId, dto);
    }

    @Test
    void createVariant_withUomConversions_rejectsDuplicateUomName() {
        UUID productId = createProductId();
        ProductVariantRequestDTO dto = new ProductVariantRequestDTO();
        dto.setOptionIds(List.of());
        dto.setUomConversions(List.of(uomConversion("case", 24), uomConversion("case", 12)));

        createVariantExpectBadRequest(productId, dto);
    }

    @Test
    void createVariant_withoutUomConversions_hasEmptyList() {
        UUID variantId = buildVariant();

        List<UomConversionResponseDTO> listed = client.get().uri("/variants/{v}/uom-conversions", variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UomConversionResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(listed).isEmpty();
    }

    @Test
    void setConversion_withoutPriceOverride_effectivePriceDerivesFromVariantPrice() {
        UUID productId = createProductId();
        ProductVariantRequestDTO varDto = new ProductVariantRequestDTO();
        varDto.setOptionIds(List.of());
        varDto.setPrice(BigDecimal.valueOf(2));
        varDto.setPriceCurrency("USD");
        UUID variantId = createVariant(productId, varDto).getId();

        UomConversionResponseDTO conversion = putConversion(variantId, "case", 24);

        assertThat(conversion.getPrice()).isNull();
        assertThat(conversion.getEffectivePrice()).isEqualByComparingTo(BigDecimal.valueOf(48));
    }

    @Test
    void setConversion_withPriceOverride_effectivePriceUsesOverride() {
        UUID productId = createProductId();
        ProductVariantRequestDTO varDto = new ProductVariantRequestDTO();
        varDto.setOptionIds(List.of());
        varDto.setPrice(BigDecimal.valueOf(2));
        varDto.setPriceCurrency("USD");
        UUID variantId = createVariant(productId, varDto).getId();

        UomConversionRequestDTO dto = new UomConversionRequestDTO();
        dto.setUomName("case");
        dto.setFactor(BigDecimal.valueOf(24));
        dto.setPrice(BigDecimal.valueOf(40));
        UomConversionResponseDTO conversion = client.put().uri("/variants/{v}/uom-conversions", variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(UomConversionResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(conversion).isNotNull();
        assertThat(conversion.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(40));
        assertThat(conversion.getEffectivePrice()).isEqualByComparingTo(BigDecimal.valueOf(40));
    }

    @Test
    void setConversion_variantHasNoPrice_effectivePriceIsNull() {
        UUID variantId = buildVariant();

        UomConversionResponseDTO conversion = putConversion(variantId, "case", 24);

        assertThat(conversion.getPrice()).isNull();
        assertThat(conversion.getEffectivePrice()).isNull();
    }

    @Test
    void setConversion_effectiveCostIsAlwaysDerivedFromVariantCost_noOverridePossible() {
        UUID productId = createProductId();
        ProductVariantRequestDTO varDto = new ProductVariantRequestDTO();
        varDto.setOptionIds(List.of());
        varDto.setCost(BigDecimal.valueOf(1));
        varDto.setCostCurrency("USD");
        UUID variantId = createVariant(productId, varDto).getId();

        UomConversionResponseDTO conversion = putConversion(variantId, "case", 24);

        assertThat(conversion.getEffectiveCost()).isEqualByComparingTo(BigDecimal.valueOf(24));
    }

    @Test
    void setConversion_variantHasNoCost_effectiveCostIsNull() {
        UUID variantId = buildVariant();

        UomConversionResponseDTO conversion = putConversion(variantId, "case", 24);

        assertThat(conversion.getEffectiveCost()).isNull();
    }

    @Test
    void setConversion_rejectsNonPositivePriceOverride() {
        UUID variantId = buildVariant();
        UomConversionRequestDTO dto = new UomConversionRequestDTO();
        dto.setUomName("case");
        dto.setFactor(BigDecimal.valueOf(24));
        dto.setPrice(BigDecimal.ZERO);

        client.put().uri("/variants/{v}/uom-conversions", variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void deleteConversion_removesIt() {
        UUID variantId = buildVariant();
        putConversion(variantId, "case", 24);

        client.delete().uri("/variants/{v}/uom-conversions/case", variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNoContent();

        client.get().uri("/variants/{v}/uom-conversions", variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UomConversionResponseDTO.class)
                .hasSize(0);
    }

    @Test
    void deleteConversion_returns404_whenNotFound() {
        UUID variantId = buildVariant();

        client.delete().uri("/variants/{v}/uom-conversions/case", variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void recordMovement_withUom_convertsToBaseUnitsInBalance() {
        UUID variantId = buildVariant();
        UUID locationId = createLocation();
        putConversion(variantId, "case", 24);

        StockMovementResponseDTO movement = recordMovementWithUom(variantId, locationId, MovementType.INBOUND, 2, "case");

        assertThat(movement.getDelta()).isEqualByComparingTo(BigDecimal.valueOf(48));
        assertThat(movement.getUom()).isEqualTo("case");
        assertThat(movement.getUomQuantity()).isEqualByComparingTo(BigDecimal.valueOf(2));

        List<StockBalanceResponseDTO> balances = client.get()
                .uri("/stock/balances?variantId={v}", variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockBalanceResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(balances).hasSize(1);
        assertThat(balances.get(0).getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(48));
    }

    @Test
    void recordMovement_withUom_outboundConvertsCorrectly() {
        UUID variantId = buildVariant();
        UUID locationId = createLocation();
        putConversion(variantId, "case", 24);
        recordMovementWithUom(variantId, locationId, MovementType.INBOUND, 2, "case");

        StockMovementResponseDTO movement = recordMovementWithUom(variantId, locationId, MovementType.OUTBOUND, -1, "case");

        assertThat(movement.getDelta()).isEqualByComparingTo(BigDecimal.valueOf(-24));

        List<StockBalanceResponseDTO> balances = client.get()
                .uri("/stock/balances?variantId={v}", variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockBalanceResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(balances).hasSize(1);
        assertThat(balances.get(0).getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(24));
    }

    @Test
    void recordMovement_withoutUom_stillWorksInBaseUnits() {
        UUID variantId = buildVariant();
        UUID locationId = createLocation();

        StockMovementResponseDTO movement = recordMovementWithUom(variantId, locationId, MovementType.INBOUND, 5, null);

        assertThat(movement.getDelta()).isEqualByComparingTo(BigDecimal.valueOf(5));
        assertThat(movement.getUom()).isNull();
        assertThat(movement.getUomQuantity()).isNull();
    }

    @Test
    void recordMovement_withUnknownUom_returns400() {
        UUID variantId = buildVariant();
        UUID locationId = createLocation();

        StockMovementRequestDTO dto = new StockMovementRequestDTO();
        dto.setVariantId(variantId);
        dto.setLocationId(locationId);
        dto.setMovementType(MovementType.INBOUND);
        dto.setDelta(BigDecimal.valueOf(2));
        dto.setUom("pallet");

        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    private UomConversionResponseDTO putConversion(UUID variantId, String uomName, int factor) {
        UomConversionRequestDTO dto = new UomConversionRequestDTO();
        dto.setUomName(uomName);
        dto.setFactor(BigDecimal.valueOf(factor));
        UomConversionResponseDTO response = client.put().uri("/variants/{v}/uom-conversions", variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(UomConversionResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private StockMovementResponseDTO recordMovementWithUom(UUID variantId, UUID locationId, MovementType type, int delta, String uom) {
        StockMovementRequestDTO dto = new StockMovementRequestDTO();
        dto.setVariantId(variantId);
        dto.setLocationId(locationId);
        dto.setMovementType(type);
        dto.setDelta(BigDecimal.valueOf(delta));
        dto.setUom(uom);
        StockMovementResponseDTO response = client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(StockMovementResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private UUID buildVariant() {
        ProductVariantRequestDTO varDto = new ProductVariantRequestDTO();
        varDto.setOptionIds(List.of());
        return createVariant(createProductId(), varDto).getId();
    }

    private UUID createProductId() {
        ProductRequestDTO prodDto = new ProductRequestDTO();
        prodDto.setSku("UOM-SKU-" + UUID.randomUUID());
        prodDto.setName("UoM Product");
        prodDto.setIsActive(true);
        ProductResponseDTO product = client.post().uri("/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(prodDto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(product).isNotNull();
        return product.getId();
    }

    private ProductVariantResponseDTO createVariant(UUID productId, ProductVariantRequestDTO dto) {
        ProductVariantResponseDTO variant = client.post()
                .uri("/products/{productId}/variants", productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(variant).isNotNull();
        return variant;
    }

    private void createVariantExpectBadRequest(UUID productId, ProductVariantRequestDTO dto) {
        client.post().uri("/products/{productId}/variants", productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    private static UomConversionRequestDTO uomConversion(String uomName, int factor) {
        UomConversionRequestDTO c = new UomConversionRequestDTO();
        c.setUomName(uomName);
        c.setFactor(BigDecimal.valueOf(factor));
        return c;
    }

    private UUID createLocation() {
        LocationRequestDTO dto = new LocationRequestDTO();
        dto.setName("Loc-" + UUID.randomUUID());
        dto.setCode("LC-" + UUID.randomUUID().toString().substring(0, 8));
        dto.setIsActive(true);
        LocationResponseDTO location = client.post().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(LocationResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(location).isNotNull();
        return location.getId();
    }
}
