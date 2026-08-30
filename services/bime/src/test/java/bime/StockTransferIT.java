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

class StockTransferIT extends BaseIT {

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

    // ------------------------------------------------------------------ happy paths

    @Test
    void adminFullLifecycle_autoApprovesAndMovesStock() {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(variantId, source, 20);

        StockTransferResponseDTO transfer = createTransfer(source, dest, variantId, 8);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.DRAFT);

        transfer = act(transfer.getId(), "submit");
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.APPROVED); // admin holds BIME_TRANSFER_APPROVE

        transfer = act(transfer.getId(), "dispatch");
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.IN_TRANSIT);
        assertThat(balanceAt(variantId, source)).isEqualByComparingTo("12");
        assertThat(balanceAt(variantId, dest)).isEqualByComparingTo("0");
        assertThat(transfer.getLines().get(0).getQtyInTransit()).isEqualByComparingTo("8");

        UUID lineId = transfer.getLines().get(0).getId();
        transfer = receive(transfer.getId(), lineId, 8, false);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(balanceAt(variantId, source)).isEqualByComparingTo("12");
        assertThat(balanceAt(variantId, dest)).isEqualByComparingTo("8");
        assertThat(inTransitQty(variantId, dest)).isEqualByComparingTo("0");
    }

    @Test
    void submitWithoutApproveAuthority_waitsForApprover() {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(variantId, source, 10);
        StockTransferResponseDTO transfer = createTransfer(source, dest, variantId, 5);

        mockStockOperatorJwt();
        transfer = act(transfer.getId(), "submit");
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING_APPROVAL);

        // operator cannot approve their own transfer
        client.post().uri("/stock/transfers/{id}/approve", transfer.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isForbidden();

        mockTransferApproverJwt();
        transfer = act(transfer.getId(), "approve");
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.APPROVED);

        mockAdminJwt();
        transfer = act(transfer.getId(), "dispatch");
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.IN_TRANSIT);
    }

    @Test
    void partialReceiptsAccumulateThenComplete() {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(variantId, source, 10);
        StockTransferResponseDTO transfer = dispatchTransfer(source, dest, variantId, 10);
        UUID lineId = transfer.getLines().get(0).getId();

        transfer = receive(transfer.getId(), lineId, 4, false);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PARTIALLY_RECEIVED);
        assertThat(balanceAt(variantId, dest)).isEqualByComparingTo("4");
        assertThat(inTransitQty(variantId, dest)).isEqualByComparingTo("6");

        transfer = receive(transfer.getId(), lineId, 6, false);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(balanceAt(variantId, dest)).isEqualByComparingTo("10");
        assertThat(inTransitQty(variantId, dest)).isEqualByComparingTo("0");
    }

    @Test
    void closeShortWritesOffWhatNeverArrived() {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(variantId, source, 10);
        StockTransferResponseDTO transfer = dispatchTransfer(source, dest, variantId, 10);
        UUID lineId = transfer.getLines().get(0).getId();

        transfer = receive(transfer.getId(), lineId, 7, true);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(balanceAt(variantId, dest)).isEqualByComparingTo("7");
        assertThat(balanceAt(variantId, source)).isEqualByComparingTo("0");
        assertThat(inTransitQty(variantId, dest)).isEqualByComparingTo("0");
        StockTransferLineResponseDTO line = transfer.getLines().get(0);
        assertThat(line.getQtyDispatched()).isEqualByComparingTo("10");
        assertThat(line.getQtyReceived()).isEqualByComparingTo("7");
    }

    // ------------------------------------------------------------------ guards

    @Test
    void receiveMoreThanInTransit_rejected() {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(variantId, source, 10);
        StockTransferResponseDTO transfer = dispatchTransfer(source, dest, variantId, 10);
        UUID lineId = transfer.getLines().get(0).getId();

        StockTransferReceiveRequestDTO body = new StockTransferReceiveRequestDTO();
        StockTransferReceiveLineDTO rl = new StockTransferReceiveLineDTO();
        rl.setLineId(lineId);
        rl.setQtyReceived(BigDecimal.valueOf(12));
        body.setLines(List.of(rl));
        client.post().uri("/stock/transfers/{id}/receive", transfer.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void dispatchWithInsufficientStock_rejectedAndRolledBack() {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(variantId, source, 5);
        StockTransferResponseDTO transfer = createTransfer(source, dest, variantId, 10);
        act(transfer.getId(), "submit");

        client.post().uri("/stock/transfers/{id}/dispatch", transfer.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isBadRequest();

        StockTransferResponseDTO after = get(transfer.getId());
        assertThat(after.getStatus()).isEqualTo(TransferStatus.APPROVED);
        assertThat(balanceAt(variantId, source)).isEqualByComparingTo("5");
        assertThat(inTransitQty(variantId, dest)).isEqualByComparingTo("0");
    }

    @Test
    void cancelAllowedBeforeDispatch_blockedAfter() {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(variantId, source, 20);

        StockTransferResponseDTO a = createTransfer(source, dest, variantId, 5);
        act(a.getId(), "submit");
        StockTransferResponseDTO cancelled = act(a.getId(), "cancel");
        assertThat(cancelled.getStatus()).isEqualTo(TransferStatus.CANCELLED);

        StockTransferResponseDTO b = dispatchTransfer(source, dest, variantId, 5);
        client.post().uri("/stock/transfers/{id}/cancel", b.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void editingDraftReplacesLines_butNotAfterSubmit() {
        UUID v1 = createVariant();
        UUID v2 = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();

        StockTransferResponseDTO transfer = createTransfer(source, dest, v1, 3);
        assertThat(transfer.getLines()).hasSize(1);

        StockTransferRequestDTO patch = transferRequest(source, dest, v1, 3);
        StockTransferLineRequestDTO extra = new StockTransferLineRequestDTO();
        extra.setVariantId(v2);
        extra.setQuantity(BigDecimal.valueOf(4));
        patch.setLines(List.of(patch.getLines().get(0), extra));
        StockTransferResponseDTO patched = client.patch().uri("/stock/transfers/{id}", transfer.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(patch)
                .exchange().expectStatus().isOk()
                .expectBody(StockTransferResponseDTO.class).returnResult().getResponseBody();
        assertThat(patched.getLines()).hasSize(2);

        act(transfer.getId(), "submit");
        client.patch().uri("/stock/transfers/{id}", transfer.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(patch)
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void validationRejectsBadTransfers() {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();

        // same source and destination
        expectCreateBadRequest(transferRequest(source, source, variantId, 5));

        // no lines
        StockTransferRequestDTO noLines = transferRequest(source, dest, variantId, 5);
        noLines.setLines(List.of());
        expectCreateBadRequest(noLines);

        // duplicate variant
        StockTransferRequestDTO dup = transferRequest(source, dest, variantId, 5);
        StockTransferLineRequestDTO again = new StockTransferLineRequestDTO();
        again.setVariantId(variantId);
        again.setQuantity(BigDecimal.ONE);
        dup.setLines(List.of(dup.getLines().get(0), again));
        expectCreateBadRequest(dup);
    }

    @Test
    void unknownVariant_returns404() {
        UUID source = createLocation();
        UUID dest = createLocation();
        client.post().uri("/stock/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(transferRequest(source, dest, UUID.randomUUID(), 5))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void viewerCannotMutate() {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        mockViewerJwt();
        client.post().uri("/stock/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(transferRequest(source, dest, variantId, 5))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void transferIsScopedToItsOrg() {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(variantId, source, 10);
        StockTransferResponseDTO transfer = createTransfer(source, dest, variantId, 5);

        mockAdminJwtForOrg(UUID.randomUUID());
        client.get().uri("/stock/transfers/{id}", transfer.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isNotFound();
    }

    // ------------------------------------------------------------------ helpers

    private StockTransferResponseDTO dispatchTransfer(UUID source, UUID dest, UUID variantId, int qty) {
        StockTransferResponseDTO t = createTransfer(source, dest, variantId, qty);
        act(t.getId(), "submit");
        return act(t.getId(), "dispatch");
    }

    private StockTransferResponseDTO createTransfer(UUID source, UUID dest, UUID variantId, int qty) {
        return client.post().uri("/stock/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(transferRequest(source, dest, variantId, qty))
                .exchange().expectStatus().isOk()
                .expectBody(StockTransferResponseDTO.class).returnResult().getResponseBody();
    }

    private static StockTransferRequestDTO transferRequest(UUID source, UUID dest, UUID variantId, int qty) {
        StockTransferRequestDTO dto = new StockTransferRequestDTO();
        dto.setSourceLocationId(source);
        dto.setDestLocationId(dest);
        StockTransferLineRequestDTO line = new StockTransferLineRequestDTO();
        line.setVariantId(variantId);
        line.setQuantity(BigDecimal.valueOf(qty));
        dto.setLines(List.of(line));
        return dto;
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
        StockTransferReceiveRequestDTO body = new StockTransferReceiveRequestDTO();
        StockTransferReceiveLineDTO rl = new StockTransferReceiveLineDTO();
        rl.setLineId(lineId);
        rl.setQtyReceived(BigDecimal.valueOf(qty));
        body.setLines(List.of(rl));
        body.setCloseShort(closeShort);
        return client.post().uri("/stock/transfers/{id}/receive", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isOk()
                .expectBody(StockTransferResponseDTO.class).returnResult().getResponseBody();
    }

    private void expectCreateBadRequest(StockTransferRequestDTO dto) {
        client.post().uri("/stock/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange().expectStatus().isBadRequest();
    }

    private BigDecimal balanceAt(UUID variantId, UUID locationId) {
        List<StockBalanceResponseDTO> balances = client.get()
                .uri("/stock/balances?variantId={v}&locationId={l}", variantId, locationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBodyList(StockBalanceResponseDTO.class).returnResult().getResponseBody();
        return balances.isEmpty() ? BigDecimal.ZERO : balances.get(0).getQuantity();
    }

    private BigDecimal inTransitQty(UUID variantId, UUID destLocationId) {
        List<InTransitStockDTO> rows = client.get().uri("/stock/transfers/in-transit")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBodyList(InTransitStockDTO.class).returnResult().getResponseBody();
        return rows.stream()
                .filter(r -> r.getVariantId().equals(variantId) && r.getDestLocationId().equals(destLocationId))
                .map(InTransitStockDTO::getQuantity)
                .findFirst().orElse(BigDecimal.ZERO);
    }

    private void seedStock(UUID variantId, UUID locationId, int qty) {
        StockMovementRequestDTO dto = new StockMovementRequestDTO();
        dto.setVariantId(variantId);
        dto.setLocationId(locationId);
        dto.setMovementType(MovementType.INBOUND);
        dto.setDelta(BigDecimal.valueOf(qty));
        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange().expectStatus().isOk();
    }

    private UUID createLocation() {
        LocationRequestDTO dto = new LocationRequestDTO();
        dto.setName("Loc-" + UUID.randomUUID());
        dto.setCode("LC-" + UUID.randomUUID().toString().substring(0, 8));
        dto.setIsActive(true);
        return client.post().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange().expectStatus().isOk()
                .expectBody(LocationResponseDTO.class).returnResult().getResponseBody().getId();
    }

    private UUID createVariant() {
        ProductRequestDTO prodDto = new ProductRequestDTO();
        prodDto.setSku("TR-SKU-" + UUID.randomUUID());
        prodDto.setName("Transfer Product");
        prodDto.setIsActive(true);
        ProductResponseDTO product = client.post().uri("/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(prodDto)
                .exchange().expectStatus().isOk()
                .expectBody(ProductResponseDTO.class).returnResult().getResponseBody();

        ProductMetadataRequestDTO metaDto = new ProductMetadataRequestDTO();
        metaDto.setName("Unit-" + UUID.randomUUID());
        ProductMetadataResponseDTO meta = client.post().uri("/metadata")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(metaDto)
                .exchange().expectStatus().isOk()
                .expectBody(ProductMetadataResponseDTO.class).returnResult().getResponseBody();

        MetadataOptionRequestDTO optDto = new MetadataOptionRequestDTO();
        optDto.setValue("Each-" + UUID.randomUUID().toString().substring(0, 6));
        MetadataOptionResponseDTO option = client.post().uri("/metadata/{id}/options", meta.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(optDto)
                .exchange().expectStatus().isOk()
                .expectBody(MetadataOptionResponseDTO.class).returnResult().getResponseBody();

        ProductMetadataAssignmentItemDTO item = new ProductMetadataAssignmentItemDTO();
        item.setMetadataId(meta.getId());
        item.setOptionIds(List.of(option.getId()));
        client.put().uri("/products/{id}/metadata", product.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(List.of(item))
                .exchange().expectStatus().isNoContent();

        ProductVariantRequestDTO varDto = new ProductVariantRequestDTO();
        varDto.setOptionIds(List.of(option.getId()));
        return client.post().uri("/products/{productId}/variants", product.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(varDto)
                .exchange().expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class).returnResult().getResponseBody().getId();
    }
}
