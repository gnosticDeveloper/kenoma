package bime;

import bime.dto.BatchResponseDTO;
import bime.dto.BatchStatus;
import bime.dto.LocationRequestDTO;
import bime.dto.LocationResponseDTO;
import bime.dto.MovementType;
import bime.dto.ProductRequestDTO;
import bime.dto.ProductResponseDTO;
import bime.dto.ProductVariantRequestDTO;
import bime.dto.ProductVariantResponseDTO;
import bime.dto.RecallReportDTO;
import bime.dto.RecallRequestDTO;
import bime.dto.StockMovementRequestDTO;
import bime.dto.StockMovementResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RecallIT extends BaseIT {

    @LocalServerPort
    int port;

    private WebTestClient client;
    private UUID variantId;
    private UUID locationId;
    private UUID batchId;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(15))
                .build();
        mockAdminJwt();

        ProductRequestDTO p = new ProductRequestDTO();
        p.setSku("RECALL-" + UUID.randomUUID());
        p.setName("Recall Product");
        p.setIsActive(true);
        p.setTracksBatches(true);
        ProductResponseDTO product = post("/products", p, ProductResponseDTO.class);

        ProductVariantRequestDTO v = new ProductVariantRequestDTO();
        v.setOptionIds(List.of());
        ProductVariantResponseDTO variant = client.post().uri("/products/{p}/variants", product.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(v)
                .exchange().expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class).returnResult().getResponseBody();
        variantId = variant.getId();

        LocationRequestDTO l = new LocationRequestDTO();
        l.setName("WH-" + UUID.randomUUID());
        l.setCode("WH-" + UUID.randomUUID().toString().substring(0, 8));
        l.setIsActive(true);
        locationId = post("/locations", l, LocationResponseDTO.class).getId();

        StockMovementRequestDTO in = new StockMovementRequestDTO();
        in.setVariantId(variantId);
        in.setLocationId(locationId);
        in.setMovementType(MovementType.INBOUND);
        in.setDelta(BigDecimal.valueOf(30));
        in.setBatchCode("LOT-A");
        in.setExpiryDate(LocalDate.of(2026, 12, 31));
        post("/stock/movements", in, StockMovementResponseDTO.class);

        batchId = client.get().uri("/batches?variantId={v}", variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBodyList(BatchResponseDTO.class).returnResult().getResponseBody()
                .get(0).getId();
    }

    @Test
    void recall_stampsStatusAndNote() {
        RecallRequestDTO dto = new RecallRequestDTO();
        dto.setNote("supplier notice");
        BatchResponseDTO recalled = post("/batches/" + batchId + "/recall", dto, BatchResponseDTO.class);
        assertThat(recalled.getStatus()).isEqualTo(BatchStatus.RECALLED);
        assertThat(recalled.getRecallNote()).isEqualTo("supplier notice");
        assertThat(recalled.getRecalledAt()).isNotNull();
    }

    @Test
    void recall_twiceConflicts() {
        post("/batches/" + batchId + "/recall", new RecallRequestDTO(), BatchResponseDTO.class);
        client.post().uri("/batches/{id}/recall", batchId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void liftRecall_restoresActive() {
        post("/batches/" + batchId + "/recall", new RecallRequestDTO(), BatchResponseDTO.class);
        BatchResponseDTO lifted = client.post().uri("/batches/{id}/lift-recall", batchId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBody(BatchResponseDTO.class).returnResult().getResponseBody();
        assertThat(lifted.getStatus()).isEqualTo(BatchStatus.ACTIVE);
        assertThat(lifted.getRecallNote()).isNull();
    }

    @Test
    void liftRecall_whenNotRecalled_conflicts() {
        client.post().uri("/batches/{id}/lift-recall", batchId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void recallReport_listsAffectedLocationsAndHistory() {
        RecallReportDTO report = client.get().uri("/batches/{id}/recall-report", batchId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBody(RecallReportDTO.class).returnResult().getResponseBody();

        assertThat(report.getBatch().getId()).isEqualTo(batchId);
        assertThat(report.getAffectedLocations()).hasSize(1);
        assertThat(report.getAffectedLocations().get(0).getQuantity()).isEqualByComparingTo("30");
        assertThat(report.getHistory()).hasSize(1);
        assertThat(report.getHistory().get(0).getBatchId()).isEqualTo(batchId);
    }

    @Test
    void recall_requiresRecallManagePermission() {
        mockViewerJwt();
        client.post().uri("/batches/{id}/recall", batchId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isForbidden();

        mockStockOperatorJwt();
        client.post().uri("/batches/{id}/recall", batchId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isForbidden();
    }

    private <T> T post(String uri, Object body, Class<T> type) {
        T result = client.post().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isOk()
                .expectBody(type).returnResult().getResponseBody();
        assertThat(result).isNotNull();
        return result;
    }
}
