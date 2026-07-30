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

class StockAlertIT extends BaseIT {

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
    void setThreshold_persistsAndIsReadable() {
        VariantFixture fixture = buildVariantFixture();

        StockAlertThresholdResponseDTO response = putThreshold(fixture.variantId(), fixture.locationId(), 5);

        assertThat(response.getVariantId()).isEqualTo(fixture.variantId());
        assertThat(response.getLocationId()).isEqualTo(fixture.locationId());
        assertThat(response.getThreshold()).isEqualTo(5);
        assertThat(response.getOrgId()).isEqualTo(ORG_ID);
    }

    @Test
    void setThreshold_isUpsert_secondCallReplacesValue() {
        VariantFixture fixture = buildVariantFixture();
        putThreshold(fixture.variantId(), fixture.locationId(), 5);

        StockAlertThresholdResponseDTO updated = putThreshold(fixture.variantId(), fixture.locationId(), 20);

        assertThat(updated.getThreshold()).isEqualTo(20);
        client.get().uri("/stock/alerts/thresholds?variantId={v}&locationId={l}", fixture.variantId(), fixture.locationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockAlertThresholdResponseDTO.class)
                .hasSize(1);
    }

    @Test
    void setThreshold_returns404_forUnknownVariant() {
        LocationResponseDTO location = postLocation("Loc", "L-1");
        StockAlertThresholdRequestDTO dto = new StockAlertThresholdRequestDTO();
        dto.setVariantId(UUID.randomUUID());
        dto.setLocationId(location.getId());
        dto.setThreshold(5);

        client.put().uri("/stock/alerts/thresholds")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void setThreshold_returns404_forUnknownLocation() {
        VariantFixture fixture = buildVariantFixture();
        StockAlertThresholdRequestDTO dto = new StockAlertThresholdRequestDTO();
        dto.setVariantId(fixture.variantId());
        dto.setLocationId(UUID.randomUUID());
        dto.setThreshold(5);

        client.put().uri("/stock/alerts/thresholds")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void deleteThreshold_removesIt() {
        VariantFixture fixture = buildVariantFixture();
        putThreshold(fixture.variantId(), fixture.locationId(), 5);

        client.delete().uri("/stock/alerts/thresholds?variantId={v}&locationId={l}", fixture.variantId(), fixture.locationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNoContent();

        client.get().uri("/stock/alerts/thresholds?variantId={v}&locationId={l}", fixture.variantId(), fixture.locationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockAlertThresholdResponseDTO.class)
                .hasSize(0);
    }

    @Test
    void deleteThreshold_returns404_whenNotSet() {
        VariantFixture fixture = buildVariantFixture();

        client.delete().uri("/stock/alerts/thresholds?variantId={v}&locationId={l}", fixture.variantId(), fixture.locationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    // --- Adversarial: permission boundaries ---

    @Test
    void setThreshold_returns403_withViewerRole() {
        VariantFixture fixture = buildVariantFixture();
        mockViewerJwt();

        StockAlertThresholdRequestDTO dto = new StockAlertThresholdRequestDTO();
        dto.setVariantId(fixture.variantId());
        dto.setLocationId(fixture.locationId());
        dto.setThreshold(5);

        client.put().uri("/stock/alerts/thresholds")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void deleteThreshold_returns403_withViewerRole() {
        VariantFixture fixture = buildVariantFixture();
        putThreshold(fixture.variantId(), fixture.locationId(), 5);
        mockViewerJwt();

        client.delete().uri("/stock/alerts/thresholds?variantId={v}&locationId={l}", fixture.variantId(), fixture.locationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void getThresholds_returns401_withNoToken() {
        client.get().uri("/stock/alerts/thresholds")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void getActiveAlerts_returns401_withNoToken() {
        client.get().uri("/stock/alerts/active")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // --- Adversarial: cross-org threshold smuggling via body-supplied IDs ---
    // The threshold INSERT joins product_variants/locations on org_id = caller's org, so an org
    // cannot set (or overwrite) a threshold for another org's variant+location pair even though
    // both IDs are caller-supplied in the request body, not derived from any org-scoped path.

    @Test
    void setThreshold_cannotTargetAnotherOrgsVariantAndLocation() {
        mockAdminJwt();
        VariantFixture orgAFixture = buildVariantFixture();

        mockAdminJwtForOrg(ORG_ID_B);
        StockAlertThresholdRequestDTO dto = new StockAlertThresholdRequestDTO();
        dto.setVariantId(orgAFixture.variantId());
        dto.setLocationId(orgAFixture.locationId());
        dto.setThreshold(1);

        client.put().uri("/stock/alerts/thresholds")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void setThreshold_cannotMixOwnVariantWithAnotherOrgsLocation() {
        mockAdminJwtForOrg(ORG_ID_B);
        LocationResponseDTO orgBLocation = postLocation("Org B Loc", "OB-MIX");

        mockAdminJwt();
        VariantFixture orgAFixture = buildVariantFixture();

        StockAlertThresholdRequestDTO dto = new StockAlertThresholdRequestDTO();
        dto.setVariantId(orgAFixture.variantId());
        dto.setLocationId(orgBLocation.getId());
        dto.setThreshold(1);

        client.put().uri("/stock/alerts/thresholds")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void orgA_threshold_notVisibleToOrgB() {
        mockAdminJwt();
        VariantFixture fixture = buildVariantFixture();
        putThreshold(fixture.variantId(), fixture.locationId(), 5);

        mockAdminJwtForOrg(ORG_ID_B);
        client.get().uri("/stock/alerts/thresholds")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockAlertThresholdResponseDTO.class)
                .hasSize(0);
    }

    @Test
    void orgA_threshold_cannotBeDeletedByOrgB() {
        mockAdminJwt();
        VariantFixture fixture = buildVariantFixture();
        putThreshold(fixture.variantId(), fixture.locationId(), 5);

        mockAdminJwtForOrg(ORG_ID_B);
        client.delete().uri("/stock/alerts/thresholds?variantId={v}&locationId={l}", fixture.variantId(), fixture.locationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();

        mockAdminJwt();
        client.get().uri("/stock/alerts/thresholds?variantId={v}&locationId={l}", fixture.variantId(), fixture.locationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockAlertThresholdResponseDTO.class)
                .hasSize(1);
    }

    // --- Helpers ---

    private StockAlertThresholdResponseDTO putThreshold(UUID variantId, UUID locationId, int threshold) {
        StockAlertThresholdRequestDTO dto = new StockAlertThresholdRequestDTO();
        dto.setVariantId(variantId);
        dto.setLocationId(locationId);
        dto.setThreshold(threshold);

        StockAlertThresholdResponseDTO response = client.put().uri("/stock/alerts/thresholds")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(StockAlertThresholdResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private record VariantFixture(UUID variantId, UUID locationId) {}

    private VariantFixture buildVariantFixture() {
        LocationResponseDTO location = postLocation("Loc-" + UUID.randomUUID().toString().substring(0, 6), "LOC-" + UUID.randomUUID().toString().substring(0, 6));
        ProductResponseDTO product = postProduct("SKU-" + UUID.randomUUID(), "Product");
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
        varDto.setSku("VAR-" + UUID.randomUUID());
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

        return new VariantFixture(variant.getId(), location.getId());
    }

    private LocationResponseDTO postLocation(String name, String code) {
        LocationRequestDTO dto = new LocationRequestDTO();
        dto.setName(name);
        dto.setCode(code);
        dto.setIsActive(true);
        LocationResponseDTO response = client.post().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(LocationResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private ProductResponseDTO postProduct(String sku, String name) {
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
}
