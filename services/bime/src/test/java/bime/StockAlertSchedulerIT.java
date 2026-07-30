package bime;

import bime.dto.*;
import bime.services.StockAlertCheckService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link StockAlertCheckService} — the SQL that detects threshold dips/recoveries and
 * triggers emails — directly against the real (testcontainer) database, the way the scheduler
 * would call it per org. The scheduler's own org-iteration/vault-token plumbing is covered by
 * unit tests instead, since it has no meaningful behaviour to exercise against a real DB.
 */
class StockAlertSchedulerIT extends BaseIT {

    @LocalServerPort
    int port;

    @Autowired
    private StockAlertCheckService checkService;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(15))
                .build();
        mockAdminJwt();
        when(mailgunService.sendStockAlertEmail(anyString(), anyString(), anyString(), anyInt(), anyInt(), isNull()))
                .thenReturn(Mono.empty());
    }

    @Test
    void dipBelowThreshold_triggersAlertAndSendsEmailOnce() {
        Fixture f = buildFixture("alerts-a@example.com");
        setThreshold(f.variantId(), f.locationId(), 10);
        recordMovement(f.variantId(), f.locationId(), 5);

        checkService.checkOrg(ORG_ID, testHandle).block();

        List<StockAlertResponseDTO> active = getActiveAlerts();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getQuantity()).isEqualTo(5);
        assertThat(active.get(0).getThreshold()).isEqualTo(10);

        verify(mailgunService, times(1)).sendStockAlertEmail(
                org.mockito.ArgumentMatchers.eq("alerts-a@example.com"),
                anyString(), anyString(), anyInt(), anyInt(), isNull());
    }

    @Test
    void dipStayingBelowThreshold_doesNotResendEmailOnSubsequentTicks() {
        Fixture f = buildFixture("alerts-dedupe@example.com");
        setThreshold(f.variantId(), f.locationId(), 10);
        recordMovement(f.variantId(), f.locationId(), 5);

        checkService.checkOrg(ORG_ID, testHandle).block();
        checkService.checkOrg(ORG_ID, testHandle).block();
        checkService.checkOrg(ORG_ID, testHandle).block();

        assertThat(getActiveAlerts()).hasSize(1);
        verify(mailgunService, times(1)).sendStockAlertEmail(
                anyString(), anyString(), anyString(), anyInt(), anyInt(), isNull());
    }

    @Test
    void recoveryAboveThreshold_clearsAlert() {
        Fixture f = buildFixture("alerts-recover@example.com");
        setThreshold(f.variantId(), f.locationId(), 10);
        recordMovement(f.variantId(), f.locationId(), 5);
        checkService.checkOrg(ORG_ID, testHandle).block();
        assertThat(getActiveAlerts()).hasSize(1);

        recordMovement(f.variantId(), f.locationId(), 20); // 5 + 20 = 25, above threshold
        checkService.checkOrg(ORG_ID, testHandle).block();

        assertThat(getActiveAlerts()).isEmpty();
    }

    @Test
    void secondDipAfterRecovery_triggersFreshEmail() {
        Fixture f = buildFixture("alerts-redip@example.com");
        setThreshold(f.variantId(), f.locationId(), 10);
        recordMovement(f.variantId(), f.locationId(), 5);
        checkService.checkOrg(ORG_ID, testHandle).block();

        recordMovement(f.variantId(), f.locationId(), 20); // recovers to 25
        checkService.checkOrg(ORG_ID, testHandle).block();
        assertThat(getActiveAlerts()).isEmpty();

        recordMovement(f.variantId(), f.locationId(), -20); // dips back to 5
        checkService.checkOrg(ORG_ID, testHandle).block();

        assertThat(getActiveAlerts()).hasSize(1);
        verify(mailgunService, times(2)).sendStockAlertEmail(
                anyString(), anyString(), anyString(), anyInt(), anyInt(), isNull());
    }

    @Test
    void thresholdRemovedWhileAlertActive_clearsAlertOnNextTick() {
        Fixture f = buildFixture("alerts-removed@example.com");
        setThreshold(f.variantId(), f.locationId(), 10);
        recordMovement(f.variantId(), f.locationId(), 5);
        checkService.checkOrg(ORG_ID, testHandle).block();
        assertThat(getActiveAlerts()).hasSize(1);

        client.delete().uri("/stock/alerts/thresholds?variantId={v}&locationId={l}", f.variantId(), f.locationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNoContent();

        checkService.checkOrg(ORG_ID, testHandle).block();

        assertThat(getActiveAlerts()).isEmpty();
    }

    // --- Adversarial: missing notification email must not crash the check ---

    @Test
    void dipWithNoNotificationEmailSet_stillRecordsAlertButSendsNoEmail() {
        Fixture f = buildFixture(null);
        setThreshold(f.variantId(), f.locationId(), 10);
        recordMovement(f.variantId(), f.locationId(), 5);

        checkService.checkOrg(ORG_ID, testHandle).block();

        assertThat(getActiveAlerts()).hasSize(1);
        verify(mailgunService, never()).sendStockAlertEmail(
                anyString(), anyString(), anyString(), anyInt(), anyInt(), isNull());
    }

    // --- Adversarial: quantity exactly at threshold must trigger (boundary is "at or below") ---

    @Test
    void quantityExactlyAtThreshold_triggersAlert() {
        Fixture f = buildFixture("alerts-boundary@example.com");
        setThreshold(f.variantId(), f.locationId(), 10);
        recordMovement(f.variantId(), f.locationId(), 10);

        checkService.checkOrg(ORG_ID, testHandle).block();

        assertThat(getActiveAlerts()).hasSize(1);
    }

    @Test
    void quantityOneAboveThreshold_doesNotTrigger() {
        Fixture f = buildFixture("alerts-boundary2@example.com");
        setThreshold(f.variantId(), f.locationId(), 10);
        recordMovement(f.variantId(), f.locationId(), 11);

        checkService.checkOrg(ORG_ID, testHandle).block();

        assertThat(getActiveAlerts()).isEmpty();
    }

    // --- Adversarial: a scheduler tick for one org must not see or clear another org's alerts ---

    @Test
    void checkOrg_isScopedToTheGivenOrg_doesNotTouchOtherOrgsAlerts() {
        Fixture aFixture = buildFixture("org-a@example.com");
        setThreshold(aFixture.variantId(), aFixture.locationId(), 10);
        recordMovement(aFixture.variantId(), aFixture.locationId(), 1);

        mockAdminJwtForOrg(ORG_ID_B);
        Fixture bFixture = buildFixture("org-b@example.com");
        setThreshold(bFixture.variantId(), bFixture.locationId(), 10);
        recordMovement(bFixture.variantId(), bFixture.locationId(), 1);

        // Only check org A's tick.
        checkService.checkOrg(ORG_ID, testHandle).block();

        mockAdminJwt();
        assertThat(getActiveAlerts()).hasSize(1);

        mockAdminJwtForOrg(ORG_ID_B);
        assertThat(getActiveAlerts()).isEmpty(); // org B's tick never ran

        verify(mailgunService, times(1)).sendStockAlertEmail(
                org.mockito.ArgumentMatchers.eq("org-a@example.com"),
                anyString(), anyString(), anyInt(), anyInt(), isNull());
    }

    // --- Helpers ---

    private record Fixture(UUID variantId, UUID locationId) {}

    private Fixture buildFixture(String notificationEmail) {
        LocationRequestDTO locDto = new LocationRequestDTO();
        locDto.setName("Warehouse-" + UUID.randomUUID());
        locDto.setCode("WH-" + UUID.randomUUID().toString().substring(0, 8));
        locDto.setIsActive(true);
        locDto.setNotificationEmail(notificationEmail);
        LocationResponseDTO location = client.post().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(locDto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(LocationResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(location).isNotNull();

        ProductRequestDTO prodDto = new ProductRequestDTO();
        prodDto.setSku("ALERT-SKU-" + UUID.randomUUID());
        prodDto.setName("Alert Product");
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

        ProductMetadataRequestDTO metaDto = new ProductMetadataRequestDTO();
        metaDto.setName("Unit-" + UUID.randomUUID());
        ProductMetadataResponseDTO meta = client.post().uri("/metadata")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(metaDto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductMetadataResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(meta).isNotNull();

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
        varDto.setSku("ALERT-VAR-" + UUID.randomUUID());
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

        return new Fixture(variant.getId(), location.getId());
    }

    private void setThreshold(UUID variantId, UUID locationId, int threshold) {
        StockAlertThresholdRequestDTO dto = new StockAlertThresholdRequestDTO();
        dto.setVariantId(variantId);
        dto.setLocationId(locationId);
        dto.setThreshold(threshold);
        client.put().uri("/stock/alerts/thresholds")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk();
    }

    private void recordMovement(UUID variantId, UUID locationId, int delta) {
        StockMovementRequestDTO dto = new StockMovementRequestDTO();
        dto.setVariantId(variantId);
        dto.setLocationId(locationId);
        dto.setMovementType(delta >= 0 ? MovementType.INBOUND : MovementType.OUTBOUND);
        dto.setDelta(delta);
        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk();
    }

    private List<StockAlertResponseDTO> getActiveAlerts() {
        List<StockAlertResponseDTO> result = client.get().uri("/stock/alerts/active")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockAlertResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(result).isNotNull();
        return result;
    }
}
