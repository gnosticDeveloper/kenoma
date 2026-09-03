package bime;

import bime.dto.LocationResponseDTO;
import bime.dto.MovementType;
import bime.dto.NotificationEmailVerifyRequestDTO;
import bime.dto.ProductResponseDTO;
import bime.dto.ProductVariantResponseDTO;
import bime.dto.StockMovementRequestDTO;
import bime.dto.StockMovementResponseDTO;
import bime.services.BatchExpiryCheckService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchExpiryCheckServiceIT extends BaseIT {

    @LocalServerPort
    int port;

    @Autowired
    private BatchExpiryCheckService checkService;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(15))
                .build();
        mockAdminJwt();
        when(mailgunService.sendLocationNotificationEmailVerification(anyString(), any(), anyString(), isNull()))
                .thenReturn(Mono.empty());
        when(mailgunService.sendBatchExpiryEmail(anyString(), anyString(), anyString(), anyString(), anyString(), any(BigDecimal.class), isNull()))
                .thenReturn(Mono.empty());
    }

    @Test
    void batchInsideWindow_triggersOneAlertEmail_thenNotAgain() {
        UUID[] vl = fixture("batch-expiry@example.com");
        inbound(vl[0], vl[1], 12, "LOT-SOON", LocalDate.now().plusDays(10));

        checkService.checkOrg(ORG_ID, testHandle).block();
        verify(mailgunService, times(1)).sendBatchExpiryEmail(
                eq("batch-expiry@example.com"), anyString(), anyString(), eq("LOT-SOON"), anyString(), any(BigDecimal.class), isNull());

        checkService.checkOrg(ORG_ID, testHandle).block();
        verify(mailgunService, times(1)).sendBatchExpiryEmail(
                eq("batch-expiry@example.com"), anyString(), anyString(), eq("LOT-SOON"), anyString(), any(BigDecimal.class), isNull());
    }

    @Test
    void batchOutsideWindow_sendsNothing() {
        UUID[] vl = fixture("batch-far@example.com");
        inbound(vl[0], vl[1], 8, "LOT-FAR", LocalDate.now().plusDays(200));

        checkService.checkOrg(ORG_ID, testHandle).block();
        verify(mailgunService, never()).sendBatchExpiryEmail(
                anyString(), anyString(), anyString(), anyString(), anyString(), any(BigDecimal.class), isNull());
    }

    @Test
    void recallingBatchClearsTheAlert() {
        UUID[] vl = fixture("batch-recall-clears@example.com");
        inbound(vl[0], vl[1], 5, "LOT-R", LocalDate.now().plusDays(5));
        checkService.checkOrg(ORG_ID, testHandle).block();

        UUID batchId = client.get().uri("/batches?variantId={v}", vl[0])
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBodyList(bime.dto.BatchResponseDTO.class).returnResult().getResponseBody()
                .get(0).getId();
        client.post().uri("/batches/{id}/recall", batchId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk();

        checkService.checkOrg(ORG_ID, testHandle).block();

        Long remaining = testHandle.client()
                .sql("SELECT count(*) AS c FROM batch_expiry_alerts WHERE org_id = :o")
                .bind("o", ORG_ID)
                .fetch().one().map(r -> ((Number) r.get("c")).longValue()).block();
        org.assertj.core.api.Assertions.assertThat(remaining).isZero();
    }

    // ---------------------------------------------------------------------------------------------

    private UUID[] fixture(String notificationEmail) {
        Map<String, Object> loc = Map.of(
                "name", "WH-" + UUID.randomUUID(),
                "code", "WH-" + UUID.randomUUID().toString().substring(0, 8),
                "isActive", true,
                "notificationEmail", notificationEmail);
        LocationResponseDTO location = client.post().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(loc)
                .exchange().expectStatus().isOk()
                .expectBody(LocationResponseDTO.class).returnResult().getResponseBody();

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailgunService, org.mockito.Mockito.atLeastOnce()).sendLocationNotificationEmailVerification(
                eq(notificationEmail), eq(location.getOrgId()), tokenCaptor.capture(), isNull());
        client.post().uri("/locations/notification-email/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new NotificationEmailVerifyRequestDTO(location.getOrgId(), tokenCaptor.getValue()))
                .exchange().expectStatus().isNoContent();

        Map<String, Object> prod = Map.of(
                "sku", "EXP-" + UUID.randomUUID(), "name", "Exp Product", "isActive", true, "tracksBatches", true);
        ProductResponseDTO product = client.post().uri("/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(prod)
                .exchange().expectStatus().isOk()
                .expectBody(ProductResponseDTO.class).returnResult().getResponseBody();

        ProductVariantResponseDTO variant = client.post().uri("/products/{p}/variants", product.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("optionIds", List.of()))
                .exchange().expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class).returnResult().getResponseBody();

        return new UUID[]{variant.getId(), location.getId()};
    }

    private void inbound(UUID variantId, UUID locationId, int qty, String batchCode, LocalDate expiry) {
        StockMovementRequestDTO dto = new StockMovementRequestDTO();
        dto.setVariantId(variantId);
        dto.setLocationId(locationId);
        dto.setMovementType(MovementType.INBOUND);
        dto.setDelta(BigDecimal.valueOf(qty));
        dto.setBatchCode(batchCode);
        dto.setExpiryDate(expiry);
        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(dto)
                .exchange().expectStatus().isOk()
                .expectBody(StockMovementResponseDTO.class).returnResult();
    }
}
