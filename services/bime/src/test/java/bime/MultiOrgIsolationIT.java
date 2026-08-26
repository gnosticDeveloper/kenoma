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

class MultiOrgIsolationIT extends BaseIT {

    @LocalServerPort
    int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(15))
                .build();
    }

    // --- Location isolation ---

    @Test
    void orgA_location_notVisibleToOrgB_getList() {
        mockAdminJwt();
        postLocation("Org A Warehouse", "OA-WH");

        mockAdminJwtForOrg(ORG_ID_B);
        client.get().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(LocationResponseDTO.class)
                .hasSize(0);
    }

    @Test
    void orgA_location_returns404_toOrgB_getById() {
        mockAdminJwt();
        LocationResponseDTO location = postLocation("Org A Location", "OA-LOC");

        mockAdminJwtForOrg(ORG_ID_B);
        client.get().uri("/locations/{id}", location.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void orgA_location_returns404_toOrgB_update() {
        mockAdminJwt();
        LocationResponseDTO location = postLocation("Org A Store", "OA-ST");

        mockAdminJwtForOrg(ORG_ID_B);
        LocationRequestDTO dto = new LocationRequestDTO();
        dto.setName("Hijacked");
        dto.setCode("OA-ST");
        dto.setIsActive(true);
        client.put().uri("/locations/{id}", location.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void orgA_location_returns404_toOrgB_deactivate() {
        mockAdminJwt();
        LocationResponseDTO location = postLocation("Org A Depot", "OA-DP");

        mockAdminJwtForOrg(ORG_ID_B);
        client.delete().uri("/locations/{id}", location.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void orgsCanShareSameLocationCode() {
        mockAdminJwt();
        postLocation("Org A Shared", "SHARED-CODE");

        mockAdminJwtForOrg(ORG_ID_B);
        client.post().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(locationRequest("Org B Shared", "SHARED-CODE"))
                .exchange()
                .expectStatus().isOk();
    }

    // --- Product isolation ---

    @Test
    void orgA_product_notVisibleToOrgB() {
        mockAdminJwt();
        postProduct("ORG-A-SKU", "Org A Product");

        mockAdminJwtForOrg(ORG_ID_B);
        client.get().uri("/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ProductResponseDTO.class)
                .hasSize(0);
    }

    @Test
    void orgA_product_returns404_toOrgB_getById() {
        mockAdminJwt();
        ProductResponseDTO product = postProduct("ORG-A-PROD", "Org A Thing");

        mockAdminJwtForOrg(ORG_ID_B);
        client.get().uri("/products/{id}", product.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void orgsCanShareSameSku() {
        mockAdminJwt();
        postProduct("SHARED-SKU", "Org A Widget");

        mockAdminJwtForOrg(ORG_ID_B);
        client.post().uri("/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(productRequest("SHARED-SKU", "Org B Widget"))
                .exchange()
                .expectStatus().isOk();
    }

    // --- Metadata isolation ---

    @Test
    void orgA_metadata_notVisibleToOrgB() {
        mockAdminJwt();
        postMetadata("Org A Metadata");

        mockAdminJwtForOrg(ORG_ID_B);
        client.get().uri("/metadata")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ProductMetadataResponseDTO.class)
                .hasSize(0);
    }

    @Test
    void orgA_product_returns404_toOrgB_assignMetadata() {
        mockAdminJwt();
        ProductResponseDTO product = postProduct("ORG-A-ASSIGN-SKU", "Org A Assign Target");
        ProductMetadataResponseDTO meta = postMetadata("Org A Assign Metadata");

        mockAdminJwtForOrg(ORG_ID_B);
        ProductMetadataAssignmentItemDTO item = new ProductMetadataAssignmentItemDTO();
        item.setMetadataId(meta.getId());
        client.put().uri("/products/{id}/metadata", product.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(List.of(item))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void orgA_product_returns404_toOrgB_patchMetadataOptions() {
        mockAdminJwt();
        ProductResponseDTO product = postProduct("ORG-A-PATCH-SKU", "Org A Patch Target");
        ProductMetadataResponseDTO meta = postMetadata("Org A Patch Metadata");

        mockAdminJwtForOrg(ORG_ID_B);
        MetadataOptionPatchDTO patch = new MetadataOptionPatchDTO();
        patch.setAdd(List.of());
        patch.setRemove(List.of());
        client.patch().uri("/products/{id}/metadata/{metadataId}/options", product.getId(), meta.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(patch)
                .exchange()
                .expectStatus().isNotFound();
    }

    // --- Stock isolation ---

    @Test
    void orgA_variant_returns404_toOrgB_stockMovement() {
        mockAdminJwt();
        VariantFixture fixture = buildVariantFixture();

        mockAdminJwtForOrg(ORG_ID_B);
        LocationRequestDTO locDto = new LocationRequestDTO();
        locDto.setName("Org B Location");
        locDto.setCode("OB-LOC");
        locDto.setIsActive(true);
        LocationResponseDTO orgBLocation = client.post().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(locDto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(LocationResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(orgBLocation).isNotNull();

        StockMovementRequestDTO movement = new StockMovementRequestDTO();
        movement.setVariantId(fixture.variantId());
        movement.setLocationId(orgBLocation.getId());
        movement.setMovementType(MovementType.INBOUND);
        movement.setDelta(10);

        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(movement)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void orgB_location_returns404_toOrgA_stockMovement() {
        mockAdminJwtForOrg(ORG_ID_B);
        LocationRequestDTO locDto = new LocationRequestDTO();
        locDto.setName("Org B Location");
        locDto.setCode("OB-LOC-2");
        locDto.setIsActive(true);
        LocationResponseDTO orgBLocation = client.post().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(locDto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(LocationResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(orgBLocation).isNotNull();

        mockAdminJwt();
        VariantFixture fixture = buildVariantFixture();

        StockMovementRequestDTO movement = new StockMovementRequestDTO();
        movement.setVariantId(fixture.variantId());
        movement.setLocationId(orgBLocation.getId());
        movement.setMovementType(MovementType.INBOUND);
        movement.setDelta(10);

        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(movement)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void orgA_stockMovements_notVisibleToOrgB() {
        mockAdminJwt();
        VariantFixture fixture = buildVariantFixture();
        StockMovementRequestDTO movement = new StockMovementRequestDTO();
        movement.setVariantId(fixture.variantId());
        movement.setLocationId(fixture.locationId());
        movement.setMovementType(MovementType.INBOUND);
        movement.setDelta(5);
        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(movement)
                .exchange()
                .expectStatus().isOk();

        mockAdminJwtForOrg(ORG_ID_B);
        client.get().uri("/stock/movements?variantId={v}", fixture.variantId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockMovementResponseDTO.class)
                .hasSize(0);
    }

    // --- SKU generation / filtering isolation (adversarial: cross-org option IDs) ---

    @Test
    void orgB_cannotUseOrgA_optionId_toFilterProducts() {
        mockAdminJwt();
        VariantFixture fixture = buildVariantFixture();

        // Org B guesses/reuses Org A's real option ID as a filter value. Even though the ID is
        // valid and resolves to a real row, it must not leak Org A's product into Org B's results,
        // nor let Org B use it to find its own (non-existent) matches by ID collision.
        mockAdminJwtForOrg(ORG_ID_B);
        client.get().uri(uriBuilder -> uriBuilder
                        .path("/products")
                        .queryParam("optionIds", fixture.optionId())
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ProductResponseDTO.class)
                .hasSize(0);
    }

    @Test
    void orgB_cannotUseOrgA_optionId_toFilterVariants() {
        mockAdminJwt();
        VariantFixture fixture = buildVariantFixture();

        mockAdminJwtForOrg(ORG_ID_B);
        client.get().uri(uriBuilder -> uriBuilder
                        .path("/products/{productId}/variants")
                        .queryParam("optionIds", fixture.optionId())
                        .build(fixture.productId()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void orgB_cannotUseOrgA_optionId_toSearchVariantsAcrossProducts() {
        mockAdminJwt();
        VariantFixture fixture = buildVariantFixture();

        mockAdminJwtForOrg(ORG_ID_B);
        client.get().uri(uriBuilder -> uriBuilder
                        .path("/products/variants/search")
                        .queryParam("optionIds", fixture.optionId())
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ProductVariantResponseDTO.class)
                .hasSize(0);
    }

    // --- Helpers ---

    private record VariantFixture(UUID variantId, UUID locationId, UUID productId, UUID optionId) {}

    private VariantFixture buildVariantFixture() {
        LocationResponseDTO location = postLocation("Org A Loc", "OA-FIX-" + UUID.randomUUID().toString().substring(0, 6));
        ProductResponseDTO product = postProduct("OA-SKU-" + UUID.randomUUID(), "Org A Product");
        ProductMetadataResponseDTO meta = postMetadata("Unit-" + UUID.randomUUID());

        MetadataOptionRequestDTO optDto = new MetadataOptionRequestDTO();
        optDto.setValue("Each");
        MetadataOptionResponseDTO option = client.post().uri("/metadata/{id}/options", meta.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(optDto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(MetadataOptionResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(option).isNotNull();

        ProductMetadataAssignmentItemDTO item = new ProductMetadataAssignmentItemDTO();
        item.setMetadataId(meta.getId());
        item.setOptionIds(List.of(option.getId()));
        client.put().uri("/products/{id}/metadata", product.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(List.of(item))
                .exchange()
                .expectStatus().isNoContent();

        ProductVariantRequestDTO varDto = new ProductVariantRequestDTO();
        varDto.setOptionIds(List.of(option.getId()));
        ProductVariantResponseDTO variant = client.post()
                .uri("/products/{productId}/variants", product.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(varDto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(variant).isNotNull();

        return new VariantFixture(variant.getId(), location.getId(), product.getId(), option.getId());
    }

    private LocationResponseDTO postLocation(String name, String code) {
        LocationResponseDTO response = client.post().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(locationRequest(name, code))
                .exchange()
                .expectStatus().isOk()
                .expectBody(LocationResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private ProductResponseDTO postProduct(String sku, String name) {
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

    private ProductMetadataResponseDTO postMetadata(String name) {
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

    private static LocationRequestDTO locationRequest(String name, String code) {
        LocationRequestDTO dto = new LocationRequestDTO();
        dto.setName(name);
        dto.setCode(code);
        dto.setIsActive(true);
        return dto;
    }

    private static ProductRequestDTO productRequest(String sku, String name) {
        ProductRequestDTO dto = new ProductRequestDTO();
        dto.setSku(sku);
        dto.setName(name);
        dto.setIsActive(true);
        return dto;
    }
}
