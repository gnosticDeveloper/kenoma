package bime;

import bime.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

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

    @Test
    void getUnknownTransfer_returns404() {
        client.get().uri("/stock/transfers/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isNotFound();
    }

    // ------------------------------------------------------------------ multi-line + units

    @Test
    void multiLineTransfer_dispatchesAndReceivesEachLineIndependently() {
        UUID vA = createVariant();
        UUID vB = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(vA, source, 10);
        seedStock(vB, source, 6);

        StockTransferResponseDTO t = createTransfer(source, dest, List.of(vA, vB), List.of(10, 6));
        assertThat(t.getLines()).hasSize(2);
        act(t.getId(), "submit");
        t = act(t.getId(), "dispatch");
        assertThat(t.getStatus()).isEqualTo(TransferStatus.IN_TRANSIT);
        assertThat(balanceAt(vA, source)).isEqualByComparingTo("0");
        assertThat(balanceAt(vB, source)).isEqualByComparingTo("0");

        UUID lineA = lineFor(t, vA);
        UUID lineB = lineFor(t, vB);

        StockTransferReceiveRequestDTO body = new StockTransferReceiveRequestDTO();
        body.setLines(List.of(receiveLine(lineA, "10"), receiveLine(lineB, "4")));
        t = postReceive(t.getId(), body);
        assertThat(t.getStatus()).isEqualTo(TransferStatus.PARTIALLY_RECEIVED);
        assertThat(balanceAt(vA, dest)).isEqualByComparingTo("10");
        assertThat(balanceAt(vB, dest)).isEqualByComparingTo("4");
        assertThat(inTransitQty(vA, dest)).isEqualByComparingTo("0");
        assertThat(inTransitQty(vB, dest)).isEqualByComparingTo("2");

        body = new StockTransferReceiveRequestDTO();
        body.setLines(List.of(receiveLine(lineB, "2")));
        t = postReceive(t.getId(), body);
        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(balanceAt(vB, dest)).isEqualByComparingTo("6");
    }

    @Test
    void transferAndReceiveInAlternateUnit_convertToBaseUnits() {
        UUID variantId = createVariant();
        createUnit("case");
        addUomConversion(variantId, "case", "12");
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(variantId, source, 100);

        StockTransferResponseDTO t = createTransferQty(source, dest, variantId, "3", "case");
        StockTransferLineResponseDTO line = t.getLines().get(0);
        assertThat(line.getQtyRequested()).isEqualByComparingTo("36"); // 3 cases * 12
        assertThat(line.getUom()).isEqualTo("case");
        assertThat(line.getUomQuantity()).isEqualByComparingTo("3");

        act(t.getId(), "submit");
        t = act(t.getId(), "dispatch");
        assertThat(balanceAt(variantId, source)).isEqualByComparingTo("64");
        assertThat(inTransitQty(variantId, dest)).isEqualByComparingTo("36");

        UUID lineId = t.getLines().get(0).getId();
        StockTransferReceiveRequestDTO body = new StockTransferReceiveRequestDTO();
        body.setLines(List.of(receiveLine(lineId, "2", "case"))); // 24 base
        t = postReceive(t.getId(), body);
        assertThat(t.getStatus()).isEqualTo(TransferStatus.PARTIALLY_RECEIVED);
        assertThat(balanceAt(variantId, dest)).isEqualByComparingTo("24");
        assertThat(inTransitQty(variantId, dest)).isEqualByComparingTo("12");

        body = new StockTransferReceiveRequestDTO();
        body.setLines(List.of(receiveLine(lineId, "1", "case")));
        t = postReceive(t.getId(), body);
        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(balanceAt(variantId, dest)).isEqualByComparingTo("36");
    }

    @Test
    void decimalQuantitiesTransferWithoutRounding() {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(variantId, source, "10.5");

        StockTransferResponseDTO t = createTransferQty(source, dest, variantId, "2.25", null);
        act(t.getId(), "submit");
        t = act(t.getId(), "dispatch");
        assertThat(balanceAt(variantId, source)).isEqualByComparingTo("8.25");
        assertThat(inTransitQty(variantId, dest)).isEqualByComparingTo("2.25");

        t = receive(t.getId(), t.getLines().get(0).getId(), new BigDecimal("2.25"), null, false);
        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(balanceAt(variantId, dest)).isEqualByComparingTo("2.25");
    }

    // ------------------------------------------------------------------ delete / list / state machine

    @Test
    void deleteDraft_ok_butNotAfterSubmit_andMissing404() {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(variantId, source, 20);

        StockTransferResponseDTO draft = createTransfer(source, dest, variantId, 5);
        client.delete().uri("/stock/transfers/{id}", draft.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isEqualTo(204);
        client.get().uri("/stock/transfers/{id}", draft.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isNotFound();

        StockTransferResponseDTO submitted = createTransfer(source, dest, variantId, 5);
        act(submitted.getId(), "submit");
        client.delete().uri("/stock/transfers/{id}", submitted.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isEqualTo(409);

        client.delete().uri("/stock/transfers/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void listFiltersByStatusSourceDestAndVariant() {
        UUID vX = createVariant();
        UUID vY = createVariant();
        UUID s1 = createLocation();
        UUID s2 = createLocation();
        UUID d1 = createLocation();
        UUID d2 = createLocation();
        seedStock(vX, s1, 50);
        seedStock(vY, s2, 50);

        StockTransferResponseDTO draft = createTransfer(s1, d1, vX, 5);        // DRAFT, s1->d1, vX
        StockTransferResponseDTO moving = dispatchTransfer(s2, d2, vY, 5);     // IN_TRANSIT, s2->d2, vY

        assertThat(idsOf(listTransfers("?status=DRAFT"))).containsExactly(draft.getId());
        assertThat(idsOf(listTransfers("?status=IN_TRANSIT"))).containsExactly(moving.getId());
        assertThat(idsOf(listTransfers("?sourceLocationId=" + s1))).containsExactly(draft.getId());
        assertThat(idsOf(listTransfers("?destLocationId=" + d2))).containsExactly(moving.getId());
        assertThat(idsOf(listTransfers("?variantId=" + vX))).containsExactly(draft.getId());
        assertThat(idsOf(listTransfers(""))).containsExactlyInAnyOrder(draft.getId(), moving.getId());
    }

    @Test
    void invalidStateTransitionsReturn409() {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(variantId, source, 20);

        StockTransferResponseDTO t = createTransfer(source, dest, variantId, 5); // DRAFT
        expectActConflict(t.getId(), "dispatch");
        expectActConflict(t.getId(), "approve");
        expectReceiveConflict(t.getId());

        act(t.getId(), "submit"); // -> APPROVED (admin auto-approves)
        expectActConflict(t.getId(), "submit");
        expectActConflict(t.getId(), "approve");
        expectReceiveConflict(t.getId());

        act(t.getId(), "dispatch"); // -> IN_TRANSIT
        expectActConflict(t.getId(), "dispatch");
        expectActConflict(t.getId(), "submit");

        receive(t.getId(), t.getLines().get(0).getId(), 5, false); // -> COMPLETED
        expectActConflict(t.getId(), "cancel");
        expectActConflict(t.getId(), "dispatch");
        expectReceiveConflict(t.getId());
    }

    @Test
    void rejectCancelsPendingTransfer_andRequiresApproveAuthority() {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(variantId, source, 20);

        StockTransferResponseDTO pending = createTransfer(source, dest, variantId, 5);
        mockStockOperatorJwt();
        pending = act(pending.getId(), "submit");
        assertThat(pending.getStatus()).isEqualTo(TransferStatus.PENDING_APPROVAL);
        expectActForbidden(pending.getId(), "reject"); // operator lacks BIME_TRANSFER_APPROVE

        mockAdminJwt();
        StockTransferResponseDTO rejected = act(pending.getId(), "reject");
        assertThat(rejected.getStatus()).isEqualTo(TransferStatus.CANCELLED);

        StockTransferResponseDTO draft = createTransfer(source, dest, variantId, 5);
        expectActConflict(draft.getId(), "reject"); // DRAFT is not awaiting approval
    }

    @Test
    void receiveRejectsBadPayloads() {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(variantId, source, 10);
        StockTransferResponseDTO t = dispatchTransfer(source, dest, variantId, 10);
        UUID lineId = t.getLines().get(0).getId();

        StockTransferReceiveRequestDTO nullQty = new StockTransferReceiveRequestDTO();
        StockTransferReceiveLineDTO l = new StockTransferReceiveLineDTO();
        l.setLineId(lineId);
        nullQty.setLines(List.of(l));
        expectReceiveStatus(t.getId(), nullQty, 400);

        StockTransferReceiveRequestDTO negative = new StockTransferReceiveRequestDTO();
        negative.setLines(List.of(receiveLine(lineId, "-3")));
        expectReceiveStatus(t.getId(), negative, 400);

        StockTransferReceiveRequestDTO unknownLine = new StockTransferReceiveRequestDTO();
        unknownLine.setLines(List.of(receiveLine(UUID.randomUUID(), "1")));
        expectReceiveStatus(t.getId(), unknownLine, 404);
    }

    @Test
    void closeShortWithNothingReceived_completesAndWritesOffEverything() {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(variantId, source, 10);
        StockTransferResponseDTO t = dispatchTransfer(source, dest, variantId, 10);

        StockTransferReceiveRequestDTO body = new StockTransferReceiveRequestDTO();
        body.setLines(List.of());
        body.setCloseShort(true);
        t = postReceive(t.getId(), body);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(balanceAt(variantId, source)).isEqualByComparingTo("0");
        assertThat(balanceAt(variantId, dest)).isEqualByComparingTo("0");
        assertThat(inTransitQty(variantId, dest)).isEqualByComparingTo("0");
        assertThat(t.getLines().get(0).getQtyReceived()).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ adversarial

    @Test
    void crossTenantCannotOperateAnotherOrgsTransfer() {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(variantId, source, 50);
        StockTransferResponseDTO t = createTransfer(source, dest, variantId, 10);
        UUID id = t.getId();
        UUID lineId = t.getLines().get(0).getId();

        mockAdminJwtForOrg(UUID.randomUUID());

        for (String action : List.of("submit", "approve", "reject", "dispatch", "cancel")) {
            client.post().uri("/stock/transfers/{id}/{a}", id, action)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                    .exchange().expectStatus().isNotFound();
        }
        client.patch().uri("/stock/transfers/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(transferRequest(source, dest, variantId, 5))
                .exchange().expectStatus().isNotFound();
        client.delete().uri("/stock/transfers/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isNotFound();
        StockTransferReceiveRequestDTO body = new StockTransferReceiveRequestDTO();
        body.setLines(List.of(receiveLine(lineId, "1")));
        expectReceiveStatus(id, body, 404);
        client.get().uri("/stock/transfers/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isNotFound();

        // owner's transfer is untouched
        mockAdminJwt();
        assertThat(get(id).getStatus()).isEqualTo(TransferStatus.DRAFT);
        assertThat(balanceAt(variantId, source)).isEqualByComparingTo("50");
    }

    @Test
    void createRejectsForeignVariantOrLocation_andRollsBackTheHeader() {
        mockAdminJwtForOrg(ORG_ID_B);
        UUID foreignVariant = createVariant();
        UUID foreignLocation = createLocation();

        mockAdminJwt();
        UUID myVariant = createVariant();
        UUID mySource = createLocation();
        UUID myDest = createLocation();

        client.post().uri("/stock/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(transferRequest(mySource, myDest, foreignVariant, 5))
                .exchange().expectStatus().isNotFound();

        client.post().uri("/stock/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(transferRequest(foreignLocation, myDest, myVariant, 5))
                .exchange().expectStatus().isNotFound();

        // neither attempt left an orphaned stock_transfers row
        assertThat(listTransfers("")).isEmpty();
    }

    @Test
    void subPrecisionQuantityRoundingToZeroIsRejected() {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();

        StockTransferRequestDTO dto = new StockTransferRequestDTO();
        dto.setSourceLocationId(source);
        dto.setDestLocationId(dest);
        StockTransferLineRequestDTO line = new StockTransferLineRequestDTO();
        line.setVariantId(variantId);
        line.setQuantity(new BigDecimal("0.0004")); // below numeric(14,3) precision -> 0.000
        dto.setLines(List.of(line));

        client.post().uri("/stock/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange().expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.message").value(m -> assertThat((String) m)
                        .contains("rounds to zero")
                        .doesNotContain("appear at most once"));
        assertThat(listTransfers("")).isEmpty();

        // boundary: 0.001 base is the smallest recordable quantity and must succeed
        StockTransferResponseDTO ok = createTransferQty(source, dest, variantId, "0.001", null);
        assertThat(ok.getLines().get(0).getQtyRequested()).isEqualByComparingTo("0.001");
    }

    @Test
    void duplicateLineIdInOneReceivePayloadCannotDoubleCredit() {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(variantId, source, 20);
        StockTransferResponseDTO t = dispatchTransfer(source, dest, variantId, 10);
        UUID lineId = t.getLines().get(0).getId();

        StockTransferReceiveRequestDTO body = new StockTransferReceiveRequestDTO();
        body.setLines(List.of(receiveLine(lineId, "10"), receiveLine(lineId, "10")));
        expectReceiveStatus(t.getId(), body, 400); // second entry hits "already fully received"

        // the whole receive transaction rolled back: still fully in transit, nothing credited
        assertThat(get(t.getId()).getStatus()).isEqualTo(TransferStatus.IN_TRANSIT);
        assertThat(balanceAt(variantId, dest)).isEqualByComparingTo("0");
        assertThat(inTransitQty(variantId, dest)).isEqualByComparingTo("10");
    }

    @Test
    void readOnlyRolesCannotDriveAnyTransition() {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(variantId, source, 20);
        StockTransferResponseDTO t = createTransfer(source, dest, variantId, 5);
        act(t.getId(), "submit"); // APPROVED, so dispatch/receive are state-valid for the RBAC check
        UUID id = t.getId();

        StockTransferReceiveRequestDTO receiveBody = new StockTransferReceiveRequestDTO();
        receiveBody.setLines(List.of(receiveLine(t.getLines().get(0).getId(), "1")));

        for (Runnable useReadOnlyRole : List.of(
                (Runnable) this::mockViewerJwt,
                (Runnable) this::mockUserJwt)) {
            useReadOnlyRole.run();
            for (String action : List.of("submit", "approve", "reject", "dispatch", "cancel")) {
                client.post().uri("/stock/transfers/{id}/{a}", id, action)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                        .exchange().expectStatus().isForbidden();
            }
            expectReceiveStatus(id, receiveBody, 403);
            client.patch().uri("/stock/transfers/{id}", id)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(transferRequest(source, dest, variantId, 5))
                    .exchange().expectStatus().isForbidden();
            client.delete().uri("/stock/transfers/{id}", id)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                    .exchange().expectStatus().isForbidden();
        }
    }

    @Test
    void transferApproverCannotCreateDispatchOrReceive() {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(variantId, source, 20);
        StockTransferResponseDTO t = createTransfer(source, dest, variantId, 5);
        mockStockOperatorJwt();
        act(t.getId(), "submit"); // PENDING_APPROVAL

        mockTransferApproverJwt();
        // allowed: approve
        StockTransferResponseDTO approved = act(t.getId(), "approve");
        assertThat(approved.getStatus()).isEqualTo(TransferStatus.APPROVED);
        // not allowed: everything requiring BIME_MANAGE
        expectActForbidden(t.getId(), "dispatch");
        client.post().uri("/stock/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(transferRequest(source, dest, variantId, 5))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void operatorCannotSelfApproveByResubmitting() {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(variantId, source, 20);
        StockTransferResponseDTO t = createTransfer(source, dest, variantId, 5);

        mockStockOperatorJwt();
        t = act(t.getId(), "submit");
        assertThat(t.getStatus()).isEqualTo(TransferStatus.PENDING_APPROVAL);
        expectActConflict(t.getId(), "submit");   // can't re-run the auto-approve check
        expectActForbidden(t.getId(), "approve"); // lacks BIME_TRANSFER_APPROVE
        expectActConflict(t.getId(), "dispatch"); // has BIME_MANAGE, but state machine blocks it (not APPROVED)
        assertThat(get(t.getId()).getStatus()).isEqualTo(TransferStatus.PENDING_APPROVAL);
    }

    @Test
    void concurrentDispatch_deductsSourceStockExactlyOnce() throws Exception {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(variantId, source, 100);
        StockTransferResponseDTO t = createTransfer(source, dest, variantId, 30);
        act(t.getId(), "submit");
        UUID id = t.getId();

        Tally tally = fireConcurrently(20, () -> WebClient.builder().baseUrl("http://localhost:" + port).build()
                .post().uri("/stock/transfers/{id}/dispatch", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .retrieve().bodyToMono(StockTransferResponseDTO.class).block());

        assertThat(tally.ok).isEqualTo(1);
        assertThat(tally.failed).isEqualTo(19);
        assertThat(balanceAt(variantId, source)).isEqualByComparingTo("70"); // 100 - 30, once
        assertThat(get(id).getStatus()).isEqualTo(TransferStatus.IN_TRANSIT);
    }

    @Test
    void concurrentReceive_neverCreditsMoreThanWasDispatched() throws Exception {
        UUID variantId = createVariant();
        UUID source = createLocation();
        UUID dest = createLocation();
        seedStock(variantId, source, 100);
        StockTransferResponseDTO t = dispatchTransfer(source, dest, variantId, 40);
        UUID id = t.getId();
        UUID lineId = t.getLines().get(0).getId();

        StockTransferReceiveRequestDTO body = new StockTransferReceiveRequestDTO();
        body.setLines(List.of(receiveLine(lineId, "40")));

        Tally tally = fireConcurrently(15, () -> WebClient.builder().baseUrl("http://localhost:" + port).build()
                .post().uri("/stock/transfers/{id}/receive", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve().bodyToMono(StockTransferResponseDTO.class).block());

        assertThat(tally.ok).isEqualTo(1);
        assertThat(tally.failed).isEqualTo(14);
        assertThat(balanceAt(variantId, dest)).isEqualByComparingTo("40"); // never 40 * k
        assertThat(inTransitQty(variantId, dest)).isEqualByComparingTo("0");
        assertThat(get(id).getStatus()).isEqualTo(TransferStatus.COMPLETED);
    }

    // ------------------------------------------------------------------ helpers

    private record Tally(int ok, int failed) {}

    private Tally fireConcurrently(int n, Runnable call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        try {
            IntStream.range(0, n).forEach(i -> pool.execute(() -> {
                ready.countDown();
                try {
                    go.await();
                    call.run();
                    ok.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                }
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        return new Tally(ok.get(), failed.get());
    }

    private StockTransferResponseDTO dispatchTransfer(UUID source, UUID dest, UUID variantId, int qty) {
        StockTransferResponseDTO t = createTransfer(source, dest, variantId, qty);
        act(t.getId(), "submit");
        return act(t.getId(), "dispatch");
    }

    private StockTransferResponseDTO createTransfer(UUID source, UUID dest, UUID variantId, int qty) {
        return postTransfer(transferRequest(source, dest, variantId, qty));
    }

    private StockTransferResponseDTO createTransfer(UUID source, UUID dest, List<UUID> variantIds, List<Integer> qtys) {
        StockTransferRequestDTO dto = new StockTransferRequestDTO();
        dto.setSourceLocationId(source);
        dto.setDestLocationId(dest);
        List<StockTransferLineRequestDTO> lines = new ArrayList<>();
        for (int i = 0; i < variantIds.size(); i++) {
            StockTransferLineRequestDTO line = new StockTransferLineRequestDTO();
            line.setVariantId(variantIds.get(i));
            line.setQuantity(BigDecimal.valueOf(qtys.get(i)));
            lines.add(line);
        }
        dto.setLines(lines);
        return postTransfer(dto);
    }

    private StockTransferResponseDTO createTransferQty(UUID source, UUID dest, UUID variantId, String qty, String uom) {
        StockTransferRequestDTO dto = new StockTransferRequestDTO();
        dto.setSourceLocationId(source);
        dto.setDestLocationId(dest);
        StockTransferLineRequestDTO line = new StockTransferLineRequestDTO();
        line.setVariantId(variantId);
        line.setQuantity(new BigDecimal(qty));
        line.setUom(uom);
        dto.setLines(List.of(line));
        return postTransfer(dto);
    }

    private StockTransferResponseDTO postTransfer(StockTransferRequestDTO dto) {
        return client.post().uri("/stock/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange().expectStatus().isOk()
                .expectBody(StockTransferResponseDTO.class).returnResult().getResponseBody();
    }

    @Test
    void batchTrackedVariant_transfersAndCarriesItsLot() {
        ProductRequestDTO prodDto = new ProductRequestDTO();
        prodDto.setSku("TR-BATCH-" + UUID.randomUUID());
        prodDto.setName("Batch Transfer Product");
        prodDto.setIsActive(true);
        prodDto.setTracksBatches(true);
        ProductResponseDTO product = client.post().uri("/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(prodDto)
                .exchange().expectStatus().isOk()
                .expectBody(ProductResponseDTO.class).returnResult().getResponseBody();

        ProductVariantRequestDTO varDto = new ProductVariantRequestDTO();
        varDto.setOptionIds(List.of());
        ProductVariantResponseDTO variant = client.post().uri("/products/{p}/variants", product.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(varDto)
                .exchange().expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class).returnResult().getResponseBody();

        UUID source = createLocation();
        UUID dest = createLocation();

        StockMovementRequestDTO inbound = new StockMovementRequestDTO();
        inbound.setVariantId(variant.getId());
        inbound.setLocationId(source);
        inbound.setMovementType(MovementType.INBOUND);
        inbound.setDelta(BigDecimal.valueOf(20));
        inbound.setBatchCode("TR-LOT-1");
        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(inbound)
                .exchange().expectStatus().isOk();

        StockTransferResponseDTO transfer = createTransfer(source, dest, variant.getId(), 8);
        transfer = act(transfer.getId(), "submit");
        transfer = act(transfer.getId(), "dispatch");
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.IN_TRANSIT);
        assertThat(transfer.getLines().get(0).getBatches())
                .extracting(StockTransferLineBatchDTO::getBatchCode).containsExactly("TR-LOT-1");
        assertThat(balanceAt(variant.getId(), source)).isEqualByComparingTo("12");

        UUID lineId = transfer.getLines().get(0).getId();
        transfer = receive(transfer.getId(), lineId, 8, false);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(balanceAt(variant.getId(), dest)).isEqualByComparingTo("8");
        assertThat(transfer.getLines().get(0).getBatches().get(0).getQtyReceived())
                .isEqualByComparingTo("8");
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

    private void createUnit(String name) {
        OrgUnitRequestDTO dto = new OrgUnitRequestDTO();
        dto.setName(name);
        client.post().uri("/units")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange().expectStatus().isOk();
    }

    private void addUomConversion(UUID variantId, String uomName, String factor) {
        UomConversionRequestDTO dto = new UomConversionRequestDTO();
        dto.setUomName(uomName);
        dto.setFactor(new BigDecimal(factor));
        client.put().uri("/variants/{v}/uom-conversions", variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange().expectStatus().isOk();
    }

    private static UUID lineFor(StockTransferResponseDTO transfer, UUID variantId) {
        return transfer.getLines().stream()
                .filter(l -> l.getVariantId().equals(variantId))
                .map(StockTransferLineResponseDTO::getId)
                .findFirst().orElseThrow();
    }

    private static StockTransferReceiveLineDTO receiveLine(UUID lineId, String qty) {
        return receiveLine(lineId, qty, null);
    }

    private static StockTransferReceiveLineDTO receiveLine(UUID lineId, String qty, String uom) {
        StockTransferReceiveLineDTO l = new StockTransferReceiveLineDTO();
        l.setLineId(lineId);
        l.setQtyReceived(new BigDecimal(qty));
        l.setUom(uom);
        return l;
    }

    private StockTransferResponseDTO postReceive(UUID id, StockTransferReceiveRequestDTO body) {
        return client.post().uri("/stock/transfers/{id}/receive", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isOk()
                .expectBody(StockTransferResponseDTO.class).returnResult().getResponseBody();
    }

    private List<StockTransferResponseDTO> listTransfers(String query) {
        return client.get().uri("/stock/transfers" + query)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBodyList(StockTransferResponseDTO.class).returnResult().getResponseBody();
    }

    private static List<UUID> idsOf(List<StockTransferResponseDTO> transfers) {
        return transfers.stream().map(StockTransferResponseDTO::getId).toList();
    }

    private void expectActConflict(UUID id, String action) {
        client.post().uri("/stock/transfers/{id}/{action}", id, action)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isEqualTo(409);
    }

    private void expectActForbidden(UUID id, String action) {
        client.post().uri("/stock/transfers/{id}/{action}", id, action)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isForbidden();
    }

    private void expectReceiveConflict(UUID id) {
        StockTransferResponseDTO t = get(id);
        StockTransferReceiveRequestDTO body = new StockTransferReceiveRequestDTO();
        body.setLines(List.of(receiveLine(t.getLines().get(0).getId(), "1")));
        expectReceiveStatus(id, body, 409);
    }

    private void expectReceiveStatus(UUID id, StockTransferReceiveRequestDTO body, int status) {
        client.post().uri("/stock/transfers/{id}/receive", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isEqualTo(status);
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
        return receive(id, lineId, BigDecimal.valueOf(qty), null, closeShort);
    }

    private StockTransferResponseDTO receive(UUID id, UUID lineId, BigDecimal qty, String uom, boolean closeShort) {
        StockTransferReceiveRequestDTO body = new StockTransferReceiveRequestDTO();
        StockTransferReceiveLineDTO rl = new StockTransferReceiveLineDTO();
        rl.setLineId(lineId);
        rl.setQtyReceived(qty);
        rl.setUom(uom);
        body.setLines(List.of(rl));
        body.setCloseShort(closeShort);
        return postReceive(id, body);
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
        seedStock(variantId, locationId, BigDecimal.valueOf(qty));
    }

    private void seedStock(UUID variantId, UUID locationId, String qty) {
        seedStock(variantId, locationId, new BigDecimal(qty));
    }

    private void seedStock(UUID variantId, UUID locationId, BigDecimal qty) {
        StockMovementRequestDTO dto = new StockMovementRequestDTO();
        dto.setVariantId(variantId);
        dto.setLocationId(locationId);
        dto.setMovementType(MovementType.INBOUND);
        dto.setDelta(qty);
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
