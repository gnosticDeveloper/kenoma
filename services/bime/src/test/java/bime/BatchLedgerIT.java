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
import bime.dto.StockBalanceResponseDTO;
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

class BatchLedgerIT extends BaseIT {

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
    void inbound_createsBatchAndBothBalances() {
        Fixture f = batchTrackedFixture();

        StockMovementResponseDTO m = record(inbound(f.variantId, f.locationId, 100, "LOT-A", LocalDate.of(2026, 12, 31)));
        assertThat(m.getBatchId()).isNotNull();

        List<BatchResponseDTO> batches = listBatches(f.variantId);
        assertThat(batches).hasSize(1);
        assertThat(batches.get(0).getBatchCode()).isEqualTo("LOT-A");
        assertThat(batches.get(0).getExpiryDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(batches.get(0).getTotalQuantity()).isEqualByComparingTo("100");
        assertThat(batches.get(0).getStatus()).isEqualTo(BatchStatus.ACTIVE);

        assertThat(balance(f.variantId)).isEqualByComparingTo("100");
    }

    @Test
    void inbound_withGs1_parsesLotAndExpiry() {
        Fixture f = batchTrackedFixture();

        StockMovementRequestDTO dto = base(f.variantId, f.locationId, MovementType.INBOUND, 40);
        dto.setGs1("0109506000134352" + "17270115" + "10GS1LOT");
        record(dto);

        List<BatchResponseDTO> batches = listBatches(f.variantId);
        assertThat(batches).hasSize(1);
        assertThat(batches.get(0).getBatchCode()).isEqualTo("GS1LOT");
        assertThat(batches.get(0).getExpiryDate()).isEqualTo(LocalDate.of(2027, 1, 15));
    }

    @Test
    void fefo_consumesEarliestExpiryFirst() {
        Fixture f = batchTrackedFixture();
        record(inbound(f.variantId, f.locationId, 100, "LOT-LATE", LocalDate.of(2027, 6, 30)));
        record(inbound(f.variantId, f.locationId, 50, "LOT-EARLY", LocalDate.of(2026, 6, 30)));

        StockMovementResponseDTO out = record(base(f.variantId, f.locationId, MovementType.OUTBOUND, -120));

        assertThat(out.getAllocations()).hasSize(2);
        assertThat(out.getDelta()).isEqualByComparingTo("-120");

        BatchResponseDTO early = findBatch(f.variantId, "LOT-EARLY");
        BatchResponseDTO late = findBatch(f.variantId, "LOT-LATE");
        assertThat(early.getTotalQuantity()).isEqualByComparingTo("0");
        assertThat(late.getTotalQuantity()).isEqualByComparingTo("30");
        assertThat(balance(f.variantId)).isEqualByComparingTo("30");
    }

    @Test
    void explicitBatchId_overridesFefo() {
        Fixture f = batchTrackedFixture();
        record(inbound(f.variantId, f.locationId, 10, "LOT-EARLY", LocalDate.of(2026, 6, 30)));
        record(inbound(f.variantId, f.locationId, 10, "LOT-LATE", LocalDate.of(2027, 6, 30)));
        UUID lateId = findBatch(f.variantId, "LOT-LATE").getId();

        StockMovementRequestDTO dto = base(f.variantId, f.locationId, MovementType.OUTBOUND, -4);
        dto.setBatchId(lateId);
        record(dto);

        assertThat(findBatch(f.variantId, "LOT-EARLY").getTotalQuantity()).isEqualByComparingTo("10");
        assertThat(findBatch(f.variantId, "LOT-LATE").getTotalQuantity()).isEqualByComparingTo("6");
    }

    @Test
    void outbound_exceedingActiveBatchTotal_isRejected() {
        Fixture f = batchTrackedFixture();
        record(inbound(f.variantId, f.locationId, 5, "LOT-A", LocalDate.of(2026, 12, 31)));

        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(base(f.variantId, f.locationId, MovementType.OUTBOUND, -10))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void recalledBatch_blocksOutbound_butAllowsDisposalAdjustment() {
        Fixture f = batchTrackedFixture();
        record(inbound(f.variantId, f.locationId, 20, "LOT-A", LocalDate.of(2026, 12, 31)));
        UUID batchId = findBatch(f.variantId, "LOT-A").getId();

        client.post().uri("/batches/{id}/recall", batchId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk();

        StockMovementRequestDTO out = base(f.variantId, f.locationId, MovementType.OUTBOUND, -5);
        out.setBatchId(batchId);
        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(out)
                .exchange()
                .expectStatus().isBadRequest();

        StockMovementRequestDTO disposal = base(f.variantId, f.locationId, MovementType.ADJUSTMENT, -5);
        disposal.setBatchId(batchId);
        record(disposal);
        assertThat(findBatch(f.variantId, "LOT-A").getTotalQuantity()).isEqualByComparingTo("15");
    }

    @Test
    void fefo_skipsRecalledBatch() {
        Fixture f = batchTrackedFixture();
        record(inbound(f.variantId, f.locationId, 10, "LOT-EARLY", LocalDate.of(2026, 6, 30)));
        record(inbound(f.variantId, f.locationId, 10, "LOT-LATE", LocalDate.of(2027, 6, 30)));
        UUID earlyId = findBatch(f.variantId, "LOT-EARLY").getId();
        client.post().uri("/batches/{id}/recall", earlyId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk();

        record(base(f.variantId, f.locationId, MovementType.OUTBOUND, -6));

        assertThat(findBatch(f.variantId, "LOT-EARLY").getTotalQuantity()).isEqualByComparingTo("10");
        assertThat(findBatch(f.variantId, "LOT-LATE").getTotalQuantity()).isEqualByComparingTo("4");
    }

    @Test
    void inbound_withoutAnyBatchIdentifier_isRejected() {
        Fixture f = batchTrackedFixture();
        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(base(f.variantId, f.locationId, MovementType.INBOUND, 10))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void nonBatchTrackedVariant_ignoresBatchFieldsAndBehavesAsBefore() {
        Fixture f = plainFixture();
        StockMovementRequestDTO dto = base(f.variantId, f.locationId, MovementType.INBOUND, 12);
        dto.setBatchCode("SHOULD-BE-IGNORED");
        StockMovementResponseDTO m = record(dto);

        assertThat(m.getBatchId()).isNull();
        assertThat(balance(f.variantId)).isEqualByComparingTo("12");
        assertThat(listBatches(f.variantId)).isEmpty();
    }

    @Test
    void batch_notVisibleToAnotherOrg() {
        Fixture f = batchTrackedFixture();
        record(inbound(f.variantId, f.locationId, 5, "LOT-A", LocalDate.of(2026, 12, 31)));
        UUID batchId = findBatch(f.variantId, "LOT-A").getId();

        mockAdminJwtForOrg(ORG_ID_B);
        client.get().uri("/batches/{id}", batchId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    // ---------------------------------------------------------------------------------------------

    private record Fixture(UUID productId, UUID variantId, UUID locationId) {}

    private Fixture batchTrackedFixture() {
        return fixture(true);
    }

    private Fixture plainFixture() {
        return fixture(false);
    }

    private Fixture fixture(boolean tracksBatches) {
        ProductRequestDTO p = new ProductRequestDTO();
        p.setSku("BATCH-" + UUID.randomUUID());
        p.setName("Batch Product");
        p.setIsActive(true);
        p.setTracksBatches(tracksBatches);
        ProductResponseDTO product = client.post().uri("/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(p)
                .exchange().expectStatus().isOk()
                .expectBody(ProductResponseDTO.class).returnResult().getResponseBody();
        assertThat(product).isNotNull();
        assertThat(product.getTracksBatches()).isEqualTo(tracksBatches);

        ProductVariantRequestDTO v = new ProductVariantRequestDTO();
        v.setOptionIds(List.of());
        ProductVariantResponseDTO variant = client.post().uri("/products/{p}/variants", product.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(v)
                .exchange().expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class).returnResult().getResponseBody();
        assertThat(variant).isNotNull();

        LocationRequestDTO l = new LocationRequestDTO();
        l.setName("WH-" + UUID.randomUUID());
        l.setCode("WH-" + UUID.randomUUID().toString().substring(0, 8));
        l.setIsActive(true);
        LocationResponseDTO location = client.post().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(l)
                .exchange().expectStatus().isOk()
                .expectBody(LocationResponseDTO.class).returnResult().getResponseBody();
        assertThat(location).isNotNull();

        return new Fixture(product.getId(), variant.getId(), location.getId());
    }

    private static StockMovementRequestDTO base(UUID variantId, UUID locationId, MovementType type, int delta) {
        StockMovementRequestDTO dto = new StockMovementRequestDTO();
        dto.setVariantId(variantId);
        dto.setLocationId(locationId);
        dto.setMovementType(type);
        dto.setDelta(BigDecimal.valueOf(delta));
        return dto;
    }

    private static StockMovementRequestDTO inbound(UUID variantId, UUID locationId, int delta, String batchCode, LocalDate expiry) {
        StockMovementRequestDTO dto = base(variantId, locationId, MovementType.INBOUND, delta);
        dto.setBatchCode(batchCode);
        dto.setExpiryDate(expiry);
        return dto;
    }

    private StockMovementResponseDTO record(StockMovementRequestDTO dto) {
        StockMovementResponseDTO m = client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange().expectStatus().isOk()
                .expectBody(StockMovementResponseDTO.class).returnResult().getResponseBody();
        assertThat(m).isNotNull();
        return m;
    }

    private List<BatchResponseDTO> listBatches(UUID variantId) {
        return client.get().uri("/batches?variantId={v}", variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBodyList(BatchResponseDTO.class).returnResult().getResponseBody();
    }

    private BatchResponseDTO findBatch(UUID variantId, String code) {
        return listBatches(variantId).stream()
                .filter(b -> b.getBatchCode().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private BigDecimal balance(UUID variantId) {
        List<StockBalanceResponseDTO> balances = client.get().uri("/stock/balances?variantId={v}", variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBodyList(StockBalanceResponseDTO.class).returnResult().getResponseBody();
        assertThat(balances).isNotNull();
        return balances.stream().map(StockBalanceResponseDTO::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
