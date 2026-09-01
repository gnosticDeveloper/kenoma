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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Batch-tracked products moving through the transfer-order flow: FEFO allocation at dispatch,
  * per-lot balances carried to the destination, partial receipts, recall handling. */
class BatchTransferIT extends BaseIT {

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
    void dispatchFefoSplitsAcrossSourceBatches_thenFullReceiptCarriesLots() {
        UUID variantId = batchVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        LocalDate soon = LocalDate.now().plusDays(5);
        LocalDate later = LocalDate.now().plusDays(40);
        batchInbound(variantId, source, 30, "LOT-EARLY", soon);
        batchInbound(variantId, source, 25, "LOT-LATE", later);

        StockTransferResponseDTO transfer = dispatchTransfer(source, dest, variantId, 40);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.IN_TRANSIT);

        // FEFO: the whole earlier lot leaves first, the remainder comes from the later lot.
        assertThat(batchQtyAt(variantId, source, "LOT-EARLY")).isEqualByComparingTo("0");
        assertThat(batchQtyAt(variantId, source, "LOT-LATE")).isEqualByComparingTo("15");
        assertThat(variantQtyAt(variantId, source)).isEqualByComparingTo("15");
        assertThat(variantQtyAt(variantId, dest)).isEqualByComparingTo("0");

        List<StockTransferLineBatchDTO> legs = transfer.getLines().get(0).getBatches();
        assertThat(legs).extracting(StockTransferLineBatchDTO::getBatchCode)
                .containsExactly("LOT-EARLY", "LOT-LATE");
        assertThat(legs.get(0).getQtyDispatched()).isEqualByComparingTo("30");
        assertThat(legs.get(0).getQtyInTransit()).isEqualByComparingTo("30");

        UUID lineId = transfer.getLines().get(0).getId();
        transfer = receive(transfer.getId(), lineId, 40, false);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(batchQtyAt(variantId, dest, "LOT-EARLY")).isEqualByComparingTo("30");
        assertThat(batchQtyAt(variantId, dest, "LOT-LATE")).isEqualByComparingTo("10");
        assertThat(variantQtyAt(variantId, dest)).isEqualByComparingTo("40");

        legs = transfer.getLines().get(0).getBatches();
        assertThat(legs).extracting(StockTransferLineBatchDTO::getQtyReceived)
                .containsExactly(new BigDecimal("30.000"), new BigDecimal("10.000"));
        assertThat(legs).allSatisfy(l -> assertThat(l.getQtyInTransit()).isEqualByComparingTo("0"));
    }

    @Test
    void partialReceiptPostsEarliestExpiryLotFirst() {
        UUID variantId = batchVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        batchInbound(variantId, source, 30, "LOT-A", LocalDate.now().plusDays(5));
        batchInbound(variantId, source, 30, "LOT-B", LocalDate.now().plusDays(50));

        StockTransferResponseDTO transfer = dispatchTransfer(source, dest, variantId, 40); // 30 A + 10 B
        UUID lineId = transfer.getLines().get(0).getId();

        transfer = receive(transfer.getId(), lineId, 20, false);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PARTIALLY_RECEIVED);
        assertThat(batchQtyAt(variantId, dest, "LOT-A")).isEqualByComparingTo("20");
        assertThat(batchQtyAt(variantId, dest, "LOT-B")).isEqualByComparingTo("0");

        transfer = receive(transfer.getId(), lineId, 20, false);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(batchQtyAt(variantId, dest, "LOT-A")).isEqualByComparingTo("30");
        assertThat(batchQtyAt(variantId, dest, "LOT-B")).isEqualByComparingTo("10");
        assertThat(variantQtyAt(variantId, dest)).isEqualByComparingTo("40");
    }

    @Test
    void dispatchWithInsufficientActiveBatchStockFails_andLeavesTransferApproved() {
        UUID variantId = batchVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        batchInbound(variantId, source, 10, "LOT-ONLY", LocalDate.now().plusDays(10));

        StockTransferResponseDTO transfer = createTransfer(source, dest, variantId, 25);
        transfer = act(transfer.getId(), "submit");
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.APPROVED);

        client.post().uri("/stock/transfers/{id}/dispatch", transfer.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isBadRequest();

        // rolled back: nothing left the source, transfer still awaiting dispatch
        assertThat(get(transfer.getId()).getStatus()).isEqualTo(TransferStatus.APPROVED);
        assertThat(batchQtyAt(variantId, source, "LOT-ONLY")).isEqualByComparingTo("10");
        assertThat(variantQtyAt(variantId, source)).isEqualByComparingTo("10");
    }

    @Test
    void recalledSourceBatchIsSkippedByFefo() {
        UUID variantId = batchVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        batchInbound(variantId, source, 50, "LOT-BAD", LocalDate.now().plusDays(3));
        batchInbound(variantId, source, 20, "LOT-GOOD", LocalDate.now().plusDays(30));
        recall(batchId(variantId, "LOT-BAD"));

        // 20 is all the ACTIVE stock; the recalled lot is not touched
        StockTransferResponseDTO transfer = dispatchTransfer(source, dest, variantId, 20);
        assertThat(batchQtyAt(variantId, source, "LOT-GOOD")).isEqualByComparingTo("0");
        assertThat(batchQtyAt(variantId, source, "LOT-BAD")).isEqualByComparingTo("50");
        assertThat(transfer.getLines().get(0).getBatches())
                .extracting(StockTransferLineBatchDTO::getBatchCode)
                .containsExactly("LOT-GOOD");

        // asking for more than the active total fails
        StockTransferResponseDTO tooMuch = createTransfer(source, dest, variantId, 25);
        tooMuch = act(tooMuch.getId(), "submit");
        client.post().uri("/stock/transfers/{id}/dispatch", tooMuch.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void mixedBatchAndPlainLinesBothMoveCorrectly() {
        UUID batchV = batchVariant();
        UUID plainV = plainVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        batchInbound(batchV, source, 15, "LOT-M", LocalDate.now().plusDays(20));
        seedPlain(plainV, source, 15);

        StockTransferRequestDTO dto = new StockTransferRequestDTO();
        dto.setSourceLocationId(source);
        dto.setDestLocationId(dest);
        dto.setLines(List.of(line(batchV, 10), line(plainV, 12)));
        StockTransferResponseDTO transfer = postTransfer(dto);
        transfer = act(transfer.getId(), "submit");
        transfer = act(transfer.getId(), "dispatch");

        assertThat(batchQtyAt(batchV, source, "LOT-M")).isEqualByComparingTo("5");
        assertThat(variantQtyAt(plainV, source)).isEqualByComparingTo("3");

        transfer = receiveAll(transfer);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(batchQtyAt(batchV, dest, "LOT-M")).isEqualByComparingTo("10");
        assertThat(variantQtyAt(plainV, dest)).isEqualByComparingTo("12");
        // the plain line reports no lot breakdown
        assertThat(transfer.getLines().stream()
                .filter(l -> l.getVariantId().equals(plainV)).findFirst().orElseThrow().getBatches())
                .isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private StockTransferResponseDTO dispatchTransfer(UUID source, UUID dest, UUID variantId, int qty) {
        StockTransferResponseDTO t = createTransfer(source, dest, variantId, qty);
        t = act(t.getId(), "submit");
        return act(t.getId(), "dispatch");
    }

    private StockTransferResponseDTO createTransfer(UUID source, UUID dest, UUID variantId, int qty) {
        StockTransferRequestDTO dto = new StockTransferRequestDTO();
        dto.setSourceLocationId(source);
        dto.setDestLocationId(dest);
        dto.setLines(List.of(line(variantId, qty)));
        return postTransfer(dto);
    }

    private static StockTransferLineRequestDTO line(UUID variantId, int qty) {
        StockTransferLineRequestDTO l = new StockTransferLineRequestDTO();
        l.setVariantId(variantId);
        l.setQuantity(BigDecimal.valueOf(qty));
        return l;
    }

    private StockTransferResponseDTO postTransfer(StockTransferRequestDTO dto) {
        return client.post().uri("/stock/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(dto)
                .exchange().expectStatus().isOk()
                .expectBody(StockTransferResponseDTO.class).returnResult().getResponseBody();
    }

    private StockTransferResponseDTO act(UUID id, String action) {
        return client.post().uri("/stock/transfers/{id}/{action}", id, action)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBody(StockTransferResponseDTO.class).returnResult().getResponseBody();
    }

    private StockTransferResponseDTO get(UUID id) {
        return client.get().uri("/stock/transfers/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBody(StockTransferResponseDTO.class).returnResult().getResponseBody();
    }

    private StockTransferResponseDTO receive(UUID id, UUID lineId, int qty, boolean closeShort) {
        StockTransferReceiveLineDTO rl = new StockTransferReceiveLineDTO();
        rl.setLineId(lineId);
        rl.setQtyReceived(BigDecimal.valueOf(qty));
        StockTransferReceiveRequestDTO body = new StockTransferReceiveRequestDTO();
        body.setLines(List.of(rl));
        body.setCloseShort(closeShort);
        return client.post().uri("/stock/transfers/{id}/receive", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk()
                .expectBody(StockTransferResponseDTO.class).returnResult().getResponseBody();
    }

    private StockTransferResponseDTO receiveAll(StockTransferResponseDTO transfer) {
        StockTransferReceiveRequestDTO body = new StockTransferReceiveRequestDTO();
        body.setLines(transfer.getLines().stream().map(l -> {
            StockTransferReceiveLineDTO rl = new StockTransferReceiveLineDTO();
            rl.setLineId(l.getId());
            rl.setQtyReceived(l.getQtyDispatched());
            return rl;
        }).toList());
        return client.post().uri("/stock/transfers/{id}/receive", transfer.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk()
                .expectBody(StockTransferResponseDTO.class).returnResult().getResponseBody();
    }

    private void batchInbound(UUID variantId, UUID locationId, int qty, String batchCode, LocalDate expiry) {
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
                .exchange().expectStatus().isOk();
    }

    private void seedPlain(UUID variantId, UUID locationId, int qty) {
        StockMovementRequestDTO dto = new StockMovementRequestDTO();
        dto.setVariantId(variantId);
        dto.setLocationId(locationId);
        dto.setMovementType(MovementType.INBOUND);
        dto.setDelta(BigDecimal.valueOf(qty));
        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(dto)
                .exchange().expectStatus().isOk();
    }

    private void recall(UUID batchId) {
        RecallRequestDTO dto = new RecallRequestDTO();
        dto.setNote("recall for test");
        client.post().uri("/batches/{id}/recall", batchId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(dto)
                .exchange().expectStatus().isOk();
    }

    private List<BatchResponseDTO> listBatches(UUID variantId) {
        return client.get().uri("/batches?variantId={v}", variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBodyList(BatchResponseDTO.class).returnResult().getResponseBody();
    }

    private UUID batchId(UUID variantId, String code) {
        return listBatches(variantId).stream().filter(b -> b.getBatchCode().equals(code))
                .findFirst().orElseThrow().getId();
    }

    private BigDecimal batchQtyAt(UUID variantId, UUID locationId, String code) {
        BatchResponseDTO batch = listBatches(variantId).stream()
                .filter(b -> b.getBatchCode().equals(code)).findFirst().orElseThrow();
        return batch.getBalances().stream()
                .filter(bl -> bl.getLocationId().equals(locationId))
                .map(BatchLocationBalanceDTO::getQuantity)
                .findFirst().orElse(BigDecimal.ZERO);
    }

    private BigDecimal variantQtyAt(UUID variantId, UUID locationId) {
        List<StockBalanceResponseDTO> balances = client.get()
                .uri("/stock/balances?variantId={v}&locationId={l}", variantId, locationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBodyList(StockBalanceResponseDTO.class).returnResult().getResponseBody();
        return balances.isEmpty() ? BigDecimal.ZERO : balances.get(0).getQuantity();
    }

    private UUID batchVariant() {
        return variant(true);
    }

    private UUID plainVariant() {
        return variant(false);
    }

    private UUID variant(boolean tracksBatches) {
        ProductRequestDTO p = new ProductRequestDTO();
        p.setSku("BT-" + UUID.randomUUID());
        p.setName("Batch Transfer Product");
        p.setIsActive(true);
        p.setTracksBatches(tracksBatches);
        ProductResponseDTO product = client.post().uri("/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(p)
                .exchange().expectStatus().isOk()
                .expectBody(ProductResponseDTO.class).returnResult().getResponseBody();

        ProductVariantRequestDTO v = new ProductVariantRequestDTO();
        v.setOptionIds(List.of());
        return client.post().uri("/products/{p}/variants", product.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(v)
                .exchange().expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class).returnResult().getResponseBody().getId();
    }

    private UUID createLocation() {
        LocationRequestDTO dto = new LocationRequestDTO();
        dto.setName("Loc-" + UUID.randomUUID());
        dto.setCode("LC-" + UUID.randomUUID().toString().substring(0, 8));
        dto.setIsActive(true);
        return client.post().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(dto)
                .exchange().expectStatus().isOk()
                .expectBody(LocationResponseDTO.class).returnResult().getResponseBody().getId();
    }
}
