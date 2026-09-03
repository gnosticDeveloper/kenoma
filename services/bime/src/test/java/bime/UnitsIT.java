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

class UnitsIT extends BaseIT {

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
    void getUnits_seedsStandardUnitsOnFirstCall() {
        List<OrgUnitResponseDTO> units = client.get().uri("/units")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(OrgUnitResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(units).isNotNull();
        assertThat(units).extracting(OrgUnitResponseDTO::getName)
                .contains("units", "kg", "g", "m", "cm", "l", "ml");
        assertThat(units).filteredOn(u -> u.getName().equals("kg")).first()
                .extracting(OrgUnitResponseDTO::isStandard).isEqualTo(true);
    }

    @Test
    void getUnits_isIdempotent_doesNotDuplicateOnRepeatedCalls() {
        client.get().uri("/units").header(HttpHeaders.AUTHORIZATION, "Bearer test-token").exchange();

        List<OrgUnitResponseDTO> units = client.get().uri("/units")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(OrgUnitResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(units).isNotNull();
        assertThat(units).extracting(OrgUnitResponseDTO::getName)
                .filteredOn(n -> n.equals("kg")).hasSize(1);
    }

    @Test
    void createUnit_addsCustomUnit_notFlaggedStandard() {
        OrgUnitResponseDTO created = createUnit("dozen");

        assertThat(created.getName()).isEqualTo("dozen");
        assertThat(created.isStandard()).isFalse();
    }

    @Test
    void createUnit_normalizesCase() {
        OrgUnitResponseDTO created = createUnit("Dozen");

        assertThat(created.getName()).isEqualTo("dozen");
    }

    @Test
    void createUnit_rejectsDuplicateName_caseInsensitive() {
        createUnit("dozen");

        OrgUnitRequestDTO dto = new OrgUnitRequestDTO();
        dto.setName("DOZEN");
        client.post().uri("/units")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void createUnit_rejectsBlankName() {
        OrgUnitRequestDTO dto = new OrgUnitRequestDTO();
        dto.setName("   ");
        client.post().uri("/units")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void deleteUnit_removesUnusedCustomUnit() {
        OrgUnitResponseDTO created = createUnit("dozen");

        client.delete().uri("/units/{id}", created.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNoContent();

        List<OrgUnitResponseDTO> units = client.get().uri("/units")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(OrgUnitResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(units).isNotNull();
        assertThat(units).extracting(OrgUnitResponseDTO::getName).doesNotContain("dozen");
    }

    @Test
    void deleteUnit_returns404_whenNotFound() {
        client.delete().uri("/units/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void deleteUnit_rejectedWhenInUseByAVariant() {
        OrgUnitResponseDTO unit = createUnit("dozen");
        UUID productId = createProductId();
        ProductVariantRequestDTO dto = new ProductVariantRequestDTO();
        dto.setOptionIds(List.of());
        dto.setBaseUom("dozen");
        client.post().uri("/products/{productId}/variants", productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk();

        client.delete().uri("/units/{id}", unit.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void createVariant_withUnregisteredCustomBaseUom_returns400() {
        UUID productId = createProductId();
        ProductVariantRequestDTO dto = new ProductVariantRequestDTO();
        dto.setOptionIds(List.of());
        dto.setBaseUom("crate");

        client.post().uri("/products/{productId}/variants", productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void createVariant_withStandardBaseUom_autoRegistersWithoutPriorSetup() {
        UUID productId = createProductId();
        ProductVariantRequestDTO dto = new ProductVariantRequestDTO();
        dto.setOptionIds(List.of());
        dto.setBaseUom("kg");

        ProductVariantResponseDTO variant = client.post().uri("/products/{productId}/variants", productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(variant).isNotNull();
        assertThat(variant.getBaseUom()).isEqualTo("kg");
    }

    @Test
    void createVariant_baseUomIsCaseInsensitive() {
        UUID productId = createProductId();
        ProductVariantRequestDTO dto = new ProductVariantRequestDTO();
        dto.setOptionIds(List.of());
        dto.setBaseUom("KG");

        ProductVariantResponseDTO variant = client.post().uri("/products/{productId}/variants", productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(variant).isNotNull();
        assertThat(variant.getBaseUom()).isEqualTo("kg");
    }

    @Test
    void recordMovement_standardUnitConversion_computedWithoutAnExplicitConversionRow() {
        UUID productId = createProductId();
        ProductVariantRequestDTO dto = new ProductVariantRequestDTO();
        dto.setOptionIds(List.of());
        dto.setBaseUom("kg");
        ProductVariantResponseDTO variant = client.post().uri("/products/{productId}/variants", productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(variant).isNotNull();
        assertThat(variant.getUomConversions()).isEmpty();

        LocationRequestDTO locDto = new LocationRequestDTO();
        locDto.setName("Loc-" + UUID.randomUUID());
        locDto.setCode("LC-" + UUID.randomUUID().toString().substring(0, 8));
        locDto.setIsActive(true);
        LocationResponseDTO location = client.post().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(locDto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(LocationResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(location).isNotNull();

        StockMovementRequestDTO movementDto = new StockMovementRequestDTO();
        movementDto.setVariantId(variant.getId());
        movementDto.setLocationId(location.getId());
        movementDto.setMovementType(MovementType.INBOUND);
        movementDto.setDelta(BigDecimal.valueOf(500));
        movementDto.setUom("g");

        StockMovementResponseDTO movement = client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(movementDto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(StockMovementResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(movement).isNotNull();
        assertThat(movement.getDelta()).isEqualByComparingTo(BigDecimal.valueOf(0.5));
    }

    @Test
    void recordMovement_incompatibleDimensions_rejected() {
        UUID productId = createProductId();
        ProductVariantRequestDTO dto = new ProductVariantRequestDTO();
        dto.setOptionIds(List.of());
        dto.setBaseUom("kg");
        ProductVariantResponseDTO variant = client.post().uri("/products/{productId}/variants", productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(variant).isNotNull();

        LocationRequestDTO locDto = new LocationRequestDTO();
        locDto.setName("Loc-" + UUID.randomUUID());
        locDto.setCode("LC-" + UUID.randomUUID().toString().substring(0, 8));
        locDto.setIsActive(true);
        LocationResponseDTO location = client.post().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(locDto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(LocationResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(location).isNotNull();

        StockMovementRequestDTO movementDto = new StockMovementRequestDTO();
        movementDto.setVariantId(variant.getId());
        movementDto.setLocationId(location.getId());
        movementDto.setMovementType(MovementType.INBOUND);
        movementDto.setDelta(BigDecimal.valueOf(5));
        movementDto.setUom("m"); // length, incompatible with the variant's mass base unit (kg)

        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(movementDto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    private OrgUnitResponseDTO createUnit(String name) {
        OrgUnitRequestDTO dto = new OrgUnitRequestDTO();
        dto.setName(name);
        OrgUnitResponseDTO response = client.post().uri("/units")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrgUnitResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private UUID createProductId() {
        ProductRequestDTO prodDto = new ProductRequestDTO();
        prodDto.setSku("UNIT-SKU-" + UUID.randomUUID());
        prodDto.setName("Unit Test Product");
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
}
