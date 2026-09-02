package bime;

import bime.dto.BarcodeSymbology;
import bime.dto.BatchResponseDTO;
import bime.dto.LocationRequestDTO;
import bime.dto.LocationResponseDTO;
import bime.dto.MovementType;
import bime.dto.OrgUnitRequestDTO;
import bime.dto.ProductRequestDTO;
import bime.dto.ProductResponseDTO;
import bime.dto.ProductVariantRequestDTO;
import bime.dto.ProductVariantResponseDTO;
import bime.dto.SaleLineRequestDTO;
import bime.dto.SaleRequestDTO;
import bime.dto.SaleResponseDTO;
import bime.dto.StockBalanceResponseDTO;
import bime.dto.StockMovementRequestDTO;
import bime.dto.StockMovementResponseDTO;
import bime.dto.UomConversionRequestDTO;
import bime.dto.VariantBarcodeRequestDTO;
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

class SalesIT extends BaseIT {

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
    void sale_byVariantId_depletesStockAndTotalsLines() {
        Fixture f = fixture(false, "5.00");
        stockIn(f.variantId, f.locationId, 10, null, null);

        SaleResponseDTO sale = ringUp(sale(f.locationId, line(f.variantId, "3")));

        assertThat(sale.getStatus().name()).isEqualTo("COMPLETED");
        assertThat(sale.getSubtotal()).isEqualByComparingTo("15.00");
        assertThat(sale.getLines()).hasSize(1);
        assertThat(sale.getLines().get(0).getVariantId()).isEqualTo(f.variantId);
        assertThat(sale.getLines().get(0).getQtyBase()).isEqualByComparingTo("3");
        assertThat(sale.getLines().get(0).getUnitPrice()).isEqualByComparingTo("5.00");
        assertThat(sale.getLines().get(0).getLineTotal()).isEqualByComparingTo("15.00");
        assertThat(balance(f.variantId)).isEqualByComparingTo("7");

        List<StockMovementResponseDTO> movements = movements(f.variantId);
        assertThat(movements).anyMatch(m -> m.getMovementType() == MovementType.SALE
                && m.getDelta().compareTo(BigDecimal.valueOf(-3)) == 0);
    }

    @Test
    void sale_byBarcode_resolvesVariantAndUsesVariantPrice() {
        Fixture f = fixture(false, "2.50");
        stockIn(f.variantId, f.locationId, 8, null, null);
        linkBarcode(f.productId, f.variantId, "9780201379624");

        SaleLineRequestDTO l = new SaleLineRequestDTO();
        l.setBarcode("9780201379624");
        l.setQuantity(new BigDecimal("2"));
        SaleResponseDTO sale = ringUp(sale(f.locationId, l));

        assertThat(sale.getLines().get(0).getVariantId()).isEqualTo(f.variantId);
        assertThat(sale.getLines().get(0).getBarcode()).isEqualTo("9780201379624");
        assertThat(sale.getSubtotal()).isEqualByComparingTo("5.00");
        assertThat(balance(f.variantId)).isEqualByComparingTo("6");
    }

    @Test
    void sale_unitPriceOverride_isCharged() {
        Fixture f = fixture(false, "5.00");
        stockIn(f.variantId, f.locationId, 5, null, null);

        SaleLineRequestDTO l = line(f.variantId, "2");
        l.setUnitPrice(new BigDecimal("3.25"));
        SaleResponseDTO sale = ringUp(sale(f.locationId, l));

        assertThat(sale.getLines().get(0).getUnitPrice()).isEqualByComparingTo("3.25");
        assertThat(sale.getSubtotal()).isEqualByComparingTo("6.50");
    }

    @Test
    void sale_batchTracked_consumesEarliestExpiryFirst() {
        Fixture f = fixture(true, "1.00");
        stockIn(f.variantId, f.locationId, 10, "LOT-LATE", LocalDate.of(2027, 6, 30));
        stockIn(f.variantId, f.locationId, 5, "LOT-EARLY", LocalDate.of(2026, 6, 30));

        ringUp(sale(f.locationId, line(f.variantId, "7")));

        assertThat(findBatch(f.variantId, "LOT-EARLY").getTotalQuantity()).isEqualByComparingTo("0");
        assertThat(findBatch(f.variantId, "LOT-LATE").getTotalQuantity()).isEqualByComparingTo("8");
        assertThat(balance(f.variantId)).isEqualByComparingTo("8");
    }

    @Test
    void sale_batchTracked_skipsRecalledBatch() {
        Fixture f = fixture(true, "1.00");
        stockIn(f.variantId, f.locationId, 10, "LOT-EARLY", LocalDate.of(2026, 6, 30));
        stockIn(f.variantId, f.locationId, 10, "LOT-LATE", LocalDate.of(2027, 6, 30));
        UUID earlyId = findBatch(f.variantId, "LOT-EARLY").getId();
        client.post().uri("/batches/{id}/recall", earlyId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk();

        ringUp(sale(f.locationId, line(f.variantId, "6")));

        assertThat(findBatch(f.variantId, "LOT-EARLY").getTotalQuantity()).isEqualByComparingTo("10");
        assertThat(findBatch(f.variantId, "LOT-LATE").getTotalQuantity()).isEqualByComparingTo("4");
    }

    @Test
    void sale_insufficientStock_returns400() {
        Fixture f = fixture(false, "5.00");
        stockIn(f.variantId, f.locationId, 2, null, null);

        client.post().uri("/sales")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(sale(f.locationId, line(f.variantId, "5")))
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(balance(f.variantId)).isEqualByComparingTo("2");
    }

    @Test
    void sale_unknownBarcode_returns404() {
        Fixture f = fixture(false, "5.00");
        SaleLineRequestDTO l = new SaleLineRequestDTO();
        l.setBarcode("9999999999994");
        l.setQuantity(BigDecimal.ONE);

        client.post().uri("/sales")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(sale(f.locationId, l))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void sale_noPriceOnFileAndNoOverride_returns400() {
        Fixture f = fixture(false, null);
        stockIn(f.variantId, f.locationId, 5, null, null);

        client.post().uri("/sales")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(sale(f.locationId, line(f.variantId, "1")))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void sale_stockOperatorCanRingUp_viewerCannot() {
        Fixture f = fixture(false, "5.00");
        stockIn(f.variantId, f.locationId, 5, null, null);

        mockStockOperatorJwt();
        client.post().uri("/sales")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(sale(f.locationId, line(f.variantId, "1")))
                .exchange()
                .expectStatus().isOk();

        mockViewerJwt();
        client.post().uri("/sales")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(sale(f.locationId, line(f.variantId, "1")))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void listAndGet_returnSaleWithLines_andAreOrgScoped() {
        Fixture f = fixture(false, "4.00");
        stockIn(f.variantId, f.locationId, 10, null, null);
        SaleResponseDTO created = ringUp(sale(f.locationId, line(f.variantId, "2")));

        SaleResponseDTO fetched = client.get().uri("/sales/{id}", created.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBody(SaleResponseDTO.class).returnResult().getResponseBody();
        assertThat(fetched).isNotNull();
        assertThat(fetched.getLines()).hasSize(1);
        assertThat(fetched.getSubtotal()).isEqualByComparingTo("8.00");

        List<SaleResponseDTO> list = client.get().uri("/sales?locationId={l}", f.locationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBodyList(SaleResponseDTO.class).returnResult().getResponseBody();
        assertThat(list).extracting(SaleResponseDTO::getId).contains(created.getId());

        mockAdminJwtForOrg(ORG_ID_B);
        client.get().uri("/sales/{id}", created.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isNotFound();
    }

    // ── validation / not-found ───────────────────────────────────────────────────────────────────

    @Test
    void create_noLines_returns400() {
        Fixture f = fixture(false, "5.00");
        SaleRequestDTO dto = new SaleRequestDTO();
        dto.setLocationId(f.locationId);
        dto.setLines(List.of());
        badRequest(dto);
    }

    @Test
    void create_missingLocation_returns400() {
        Fixture f = fixture(false, "5.00");
        SaleRequestDTO dto = new SaleRequestDTO();
        dto.setLines(List.of(line(f.variantId, "1")));
        badRequest(dto);
    }

    @Test
    void create_lineWithNeitherBarcodeNorVariant_returns400() {
        Fixture f = fixture(false, "5.00");
        SaleLineRequestDTO l = new SaleLineRequestDTO();
        l.setQuantity(BigDecimal.ONE);
        badRequest(sale(f.locationId, l));
    }

    @Test
    void create_zeroOrNegativeQuantity_returns400() {
        Fixture f = fixture(false, "5.00");
        stockIn(f.variantId, f.locationId, 5, null, null);
        badRequest(sale(f.locationId, line(f.variantId, "0")));
        badRequest(sale(f.locationId, line(f.variantId, "-2")));
        assertThat(balance(f.variantId)).isEqualByComparingTo("5");
    }

    @Test
    void create_negativeUnitPrice_returns400() {
        Fixture f = fixture(false, "5.00");
        stockIn(f.variantId, f.locationId, 5, null, null);
        SaleLineRequestDTO l = line(f.variantId, "1");
        l.setUnitPrice(new BigDecimal("-1.00"));
        badRequest(sale(f.locationId, l));
    }

    @Test
    void create_unknownVariant_returns404() {
        Fixture f = fixture(false, "5.00");
        notFound(sale(f.locationId, line(UUID.randomUUID(), "1")));
    }

    @Test
    void create_unknownLocation_returns404() {
        Fixture f = fixture(false, "5.00");
        stockIn(f.variantId, f.locationId, 5, null, null);
        notFound(sale(UUID.randomUUID(), line(f.variantId, "1")));
    }

    @Test
    void create_atForeignOrgLocation_returns404() {
        Fixture f = fixture(false, "5.00");
        stockIn(f.variantId, f.locationId, 5, null, null);

        mockAdminJwtForOrg(ORG_ID_B);
        client.post().uri("/sales")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(sale(f.locationId, line(f.variantId, "1")))
                .exchange()
                .expectStatus().isNotFound();

        mockAdminJwt();
        assertThat(balance(f.variantId)).isEqualByComparingTo("5");
    }

    @Test
    void getById_unknown_returns404() {
        client.get().uri("/sales/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void directSaleMovement_viaStockEndpoint_isRejected() {
        Fixture f = fixture(false, "5.00");
        stockIn(f.variantId, f.locationId, 5, null, null);

        StockMovementRequestDTO dto = new StockMovementRequestDTO();
        dto.setVariantId(f.variantId);
        dto.setLocationId(f.locationId);
        dto.setMovementType(MovementType.SALE);
        dto.setDelta(new BigDecimal("-1"));
        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(balance(f.variantId)).isEqualByComparingTo("5");
    }

    // ── multi-line / batch movement rows ─────────────────────────────────────────────────────────

    @Test
    void multiLineSale_depletesEachVariant_andSumsSubtotal() {
        Fixture a = fixture(false, "3.00");
        UUID bVariant = newProductVariantWithBarcode("4.50", "9780201379624");
        stockIn(a.variantId, a.locationId, 10, null, null);
        stockIn(bVariant, a.locationId, 10, null, null);

        SaleLineRequestDTO byBarcode = new SaleLineRequestDTO();
        byBarcode.setBarcode("9780201379624");
        byBarcode.setQuantity(new BigDecimal("2"));

        SaleResponseDTO sale = ringUp(sale(a.locationId, line(a.variantId, "4"), byBarcode));

        assertThat(sale.getLines()).hasSize(2);
        assertThat(sale.getSubtotal()).isEqualByComparingTo("21.00"); // 4*3 + 2*4.5
        assertThat(balance(a.variantId)).isEqualByComparingTo("6");
        assertThat(balance(bVariant)).isEqualByComparingTo("8");
        assertThat(movements(a.variantId)).anyMatch(m -> m.getMovementType() == MovementType.SALE
                && m.getDelta().compareTo(new BigDecimal("-4")) == 0);
        assertThat(movements(bVariant)).anyMatch(m -> m.getMovementType() == MovementType.SALE
                && m.getDelta().compareTo(new BigDecimal("-2")) == 0);
    }

    @Test
    void sameVariantOnTwoLines_bothDeplete() {
        Fixture f = fixture(false, "2.00");
        stockIn(f.variantId, f.locationId, 10, null, null);

        SaleResponseDTO sale = ringUp(sale(f.locationId, line(f.variantId, "3"), line(f.variantId, "2")));

        assertThat(sale.getLines()).hasSize(2);
        assertThat(sale.getSubtotal()).isEqualByComparingTo("10.00");
        assertThat(balance(f.variantId)).isEqualByComparingTo("5");
    }

    @Test
    void batchTracked_fefoAcrossTwoBatches_writesOneMovementPerBatch() {
        Fixture f = fixture(true, "1.00");
        stockIn(f.variantId, f.locationId, 4, "LOT-EARLY", LocalDate.of(2026, 6, 30));
        stockIn(f.variantId, f.locationId, 10, "LOT-LATE", LocalDate.of(2027, 6, 30));

        ringUp(sale(f.locationId, line(f.variantId, "6")));

        List<StockMovementResponseDTO> saleRows = movements(f.variantId).stream()
                .filter(m -> m.getMovementType() == MovementType.SALE)
                .toList();
        assertThat(saleRows).hasSize(2);
        assertThat(saleRows).allSatisfy(m -> assertThat(m.getBatchId()).isNotNull());
        assertThat(saleRows.stream()
                .map(StockMovementResponseDTO::getDelta)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("-6");
        assertThat(findBatch(f.variantId, "LOT-EARLY").getTotalQuantity()).isEqualByComparingTo("0");
        assertThat(findBatch(f.variantId, "LOT-LATE").getTotalQuantity()).isEqualByComparingTo("8");
    }

    @Test
    void batchTracked_insufficientAfterRecall_returns400_andNoDepletion() {
        Fixture f = fixture(true, "1.00");
        stockIn(f.variantId, f.locationId, 5, "LOT-A", LocalDate.of(2026, 6, 30));
        stockIn(f.variantId, f.locationId, 5, "LOT-B", LocalDate.of(2027, 6, 30));
        UUID bId = findBatch(f.variantId, "LOT-B").getId();
        client.post().uri("/batches/{id}/recall", bId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk();

        client.post().uri("/sales")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(sale(f.locationId, line(f.variantId, "7")))
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(balance(f.variantId)).isEqualByComparingTo("10");
        assertThat(findBatch(f.variantId, "LOT-A").getTotalQuantity()).isEqualByComparingTo("5");
    }

    // ── pack-unit barcode ────────────────────────────────────────────────────────────────────────

    @Test
    void barcodePackUnit_ringsUpCaseAndDepletesBaseUnits() {
        UUID[] pv = newCaseVariant("1.00", 12);
        UUID productId = pv[0];
        UUID variantId = pv[1];
        LocationResponseDTO location = newLocation();
        stockIn(variantId, location.getId(), 50, null, null);

        VariantBarcodeRequestDTO bc = new VariantBarcodeRequestDTO();
        bc.setBarcode("9780201379624");
        bc.setSymbology(BarcodeSymbology.EAN13);
        bc.setUom("case");
        client.post().uri("/products/{p}/variants/{v}/barcodes", productId, variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(bc)
                .exchange().expectStatus().isOk();

        SaleLineRequestDTO l = new SaleLineRequestDTO();
        l.setBarcode("9780201379624");
        l.setQuantity(new BigDecimal("2")); // two cases
        SaleResponseDTO sale = ringUp(sale(location.getId(), l));

        assertThat(sale.getLines().get(0).getUom()).isEqualTo("case");
        assertThat(sale.getLines().get(0).getUomQuantity()).isEqualByComparingTo("2");
        assertThat(sale.getLines().get(0).getQtyBase()).isEqualByComparingTo("24");
        assertThat(sale.getLines().get(0).getUnitPrice()).isEqualByComparingTo("12.00"); // factor * unit price
        assertThat(sale.getSubtotal()).isEqualByComparingTo("24.00");

        List<StockBalanceResponseDTO> balances = client.get().uri("/stock/balances?variantId={v}", variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBodyList(StockBalanceResponseDTO.class).returnResult().getResponseBody();
        assertThat(balances.stream().map(StockBalanceResponseDTO::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("26");
    }

    // ── date-range filter ────────────────────────────────────────────────────────────────────────

    @Test
    void list_filtersBySoldAtDateRange() {
        Fixture f = fixture(false, "1.00");
        stockIn(f.variantId, f.locationId, 10, null, null);
        SaleResponseDTO created = ringUp(sale(f.locationId, line(f.variantId, "1")));
        LocalDate today = LocalDate.now();

        assertThat(listSales("locationId=" + f.locationId + "&from=" + today))
                .extracting(SaleResponseDTO::getId).contains(created.getId());
        assertThat(listSales("locationId=" + f.locationId + "&from=" + today.plusDays(1)))
                .extracting(SaleResponseDTO::getId).doesNotContain(created.getId());
        assertThat(listSales("locationId=" + f.locationId + "&to=" + today.minusDays(1)))
                .extracting(SaleResponseDTO::getId).doesNotContain(created.getId());
        assertThat(listSales("locationId=" + f.locationId + "&to=" + today))
                .extracting(SaleResponseDTO::getId).contains(created.getId());
    }

    // ---------------------------------------------------------------------------------------------

    private record Fixture(UUID productId, UUID variantId, UUID locationId) {}

    private Fixture fixture(boolean tracksBatches, String price) {
        ProductRequestDTO p = new ProductRequestDTO();
        p.setSku("SALE-" + UUID.randomUUID());
        p.setName("Sale Product");
        p.setIsActive(true);
        p.setTracksBatches(tracksBatches);
        ProductResponseDTO product = client.post().uri("/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(p)
                .exchange().expectStatus().isOk()
                .expectBody(ProductResponseDTO.class).returnResult().getResponseBody();
        assertThat(product).isNotNull();

        ProductVariantRequestDTO v = new ProductVariantRequestDTO();
        v.setOptionIds(List.of());
        if (price != null) {
            v.setPrice(new BigDecimal(price));
            v.setPriceCurrency("USD");
        }
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

    private void linkBarcode(UUID productId, UUID variantId, String barcode) {
        VariantBarcodeRequestDTO dto = new VariantBarcodeRequestDTO();
        dto.setBarcode(barcode);
        dto.setSymbology(BarcodeSymbology.EAN13);
        client.post().uri("/products/{p}/variants/{v}/barcodes", productId, variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange().expectStatus().isOk();
    }

    private void stockIn(UUID variantId, UUID locationId, int qty, String batchCode, LocalDate expiry) {
        StockMovementRequestDTO dto = new StockMovementRequestDTO();
        dto.setVariantId(variantId);
        dto.setLocationId(locationId);
        dto.setMovementType(MovementType.INBOUND);
        dto.setDelta(BigDecimal.valueOf(qty));
        dto.setBatchCode(batchCode);
        dto.setExpiryDate(expiry);
        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange().expectStatus().isOk();
    }

    private static SaleLineRequestDTO line(UUID variantId, String qty) {
        SaleLineRequestDTO l = new SaleLineRequestDTO();
        l.setVariantId(variantId);
        l.setQuantity(new BigDecimal(qty));
        return l;
    }

    private static SaleRequestDTO sale(UUID locationId, SaleLineRequestDTO... lines) {
        SaleRequestDTO dto = new SaleRequestDTO();
        dto.setLocationId(locationId);
        dto.setLines(List.of(lines));
        return dto;
    }

    private SaleResponseDTO ringUp(SaleRequestDTO dto) {
        SaleResponseDTO sale = client.post().uri("/sales")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange().expectStatus().isOk()
                .expectBody(SaleResponseDTO.class).returnResult().getResponseBody();
        assertThat(sale).isNotNull();
        return sale;
    }

    private void badRequest(SaleRequestDTO dto) {
        client.post().uri("/sales")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange().expectStatus().isBadRequest();
    }

    private void notFound(SaleRequestDTO dto) {
        client.post().uri("/sales")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange().expectStatus().isNotFound();
    }

    private List<SaleResponseDTO> listSales(String query) {
        return client.get().uri("/sales?" + query)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBodyList(SaleResponseDTO.class).returnResult().getResponseBody();
    }

    private LocationResponseDTO newLocation() {
        LocationRequestDTO l = new LocationRequestDTO();
        l.setName("WH-" + UUID.randomUUID());
        l.setCode("WH-" + UUID.randomUUID().toString().substring(0, 8));
        l.setIsActive(true);
        return client.post().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(l)
                .exchange().expectStatus().isOk()
                .expectBody(LocationResponseDTO.class).returnResult().getResponseBody();
    }

    private UUID newProductVariantWithBarcode(String price, String barcode) {
        Fixture f = fixture(false, price);
        linkBarcode(f.productId, f.variantId, barcode);
        return f.variantId;
    }

    /** A priced variant on its own product with a "case" pack conversion, set the proven way
      * (create the variant, then PUT the conversion). Returns [productId, variantId]. */
    private UUID[] newCaseVariant(String price, int caseFactor) {
        Fixture f = fixture(false, price);
        OrgUnitRequestDTO unit = new OrgUnitRequestDTO();
        unit.setName("case");
        client.post().uri("/units")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(unit)
                .exchange().expectStatus().isOk();

        UomConversionRequestDTO conv = new UomConversionRequestDTO();
        conv.setUomName("case");
        conv.setFactor(BigDecimal.valueOf(caseFactor));
        client.put().uri("/variants/{v}/uom-conversions", f.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(conv)
                .exchange().expectStatus().isOk();
        return new UUID[]{f.productId, f.variantId};
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

    private List<StockMovementResponseDTO> movements(UUID variantId) {
        return client.get().uri("/stock/movements?variantId={v}", variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBodyList(StockMovementResponseDTO.class).returnResult().getResponseBody();
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
