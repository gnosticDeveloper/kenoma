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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class StockLedgerIT extends BaseIT {

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
    void recordMovement_createsMovementAndUpdatesBalance() {
        StockFixture f = buildStockFixture();

        StockMovementResponseDTO movement = recordMovement(f.variantId, f.locationId, MovementType.INBOUND, 10);

        assertThat(movement).isNotNull();
        assertThat(movement.getId()).isNotNull();
        assertThat(movement.getVariantId()).isEqualTo(f.variantId);
        assertThat(movement.getLocationId()).isEqualTo(f.locationId);
        assertThat(movement.getDelta()).isEqualByComparingTo(BigDecimal.valueOf(10));
        assertThat(movement.getMovementType()).isEqualTo(MovementType.INBOUND);

        List<StockBalanceResponseDTO> balances = client.get()
                .uri("/stock/balances?variantId={v}", f.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockBalanceResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(balances).hasSize(1);
        assertThat(balances.get(0).getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(10));
    }

    @Test
    void getMovementById_returnsMovement() {
        StockFixture f = buildStockFixture();
        StockMovementResponseDTO created = recordMovement(f.variantId, f.locationId, MovementType.INBOUND, 5);

        StockMovementResponseDTO response = client.get()
                .uri("/stock/movements/{id}", created.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(StockMovementResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(created.getId());
        assertThat(response.getDelta()).isEqualByComparingTo(BigDecimal.valueOf(5));
    }

    @Test
    void getMovements_filtersByVariantId() {
        StockFixture f1 = buildStockFixture();
        StockFixture f2 = buildStockFixture();

        recordMovement(f1.variantId, f1.locationId, MovementType.INBOUND, 3);
        recordMovement(f2.variantId, f2.locationId, MovementType.INBOUND, 7);

        List<StockMovementResponseDTO> movements = client.get()
                .uri("/stock/movements?variantId={v}", f1.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockMovementResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).getVariantId()).isEqualTo(f1.variantId);
    }

    @Test
    void getBalances_filtersByVariantId() {
        StockFixture f1 = buildStockFixture();
        StockFixture f2 = buildStockFixture();

        recordMovement(f1.variantId, f1.locationId, MovementType.INBOUND, 20);
        recordMovement(f2.variantId, f2.locationId, MovementType.INBOUND, 30);

        List<StockBalanceResponseDTO> balances = client.get()
                .uri("/stock/balances?variantId={v}", f2.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockBalanceResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(balances).hasSize(1);
        assertThat(balances.get(0).getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(30));
    }

    @Test
    void recordMovement_rejectsNegativeBalance() {
        StockFixture f = buildStockFixture();
        recordMovement(f.variantId, f.locationId, MovementType.INBOUND, 10);

        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(movementRequest(f.variantId, f.locationId, MovementType.OUTBOUND, -20))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void recordMovement_concurrentOutbound_neverOversellsOrLosesUpdates() throws Exception {
        // upsertBalance does the arithmetic in a single `UPDATE ... SET quantity = quantity +
        // :delta` statement rather than a separate read-then-write from application code, so
        // Postgres's row-level locking should serialize concurrent movements on the same
        // variant+location correctly. Verify that directly: seed 10 units, fire 20 concurrent
        // OUTBOUND movements of 1 unit each - exactly 10 must succeed, the other 10 must be
        // cleanly rejected (not silently lost or allowed to oversell), and the final balance
        // must be exactly 0, never negative.
        StockFixture f = buildStockFixture();
        recordMovement(f.variantId, f.locationId, MovementType.INBOUND, 10);

        int concurrency = 20;
        WebClient webClient = WebClient.builder().baseUrl("http://localhost:" + port).build();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();

        try {
            List<Runnable> tasks = IntStream.range(0, concurrency)
                    .<Runnable>mapToObj(i -> () -> {
                        ready.countDown();
                        try {
                            go.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        try {
                            webClient.post().uri("/stock/movements")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .bodyValue(movementRequest(f.variantId, f.locationId, MovementType.OUTBOUND, -1))
                                    .retrieve()
                                    .bodyToMono(StockMovementResponseDTO.class)
                                    .block();
                            successCount.incrementAndGet();
                        } catch (Exception e) {
                            rejectedCount.incrementAndGet();
                        }
                    })
                    .collect(java.util.stream.Collectors.toList());
            tasks.forEach(pool::execute);

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(successCount.get()).isEqualTo(10);
        assertThat(rejectedCount.get()).isEqualTo(10);

        List<StockBalanceResponseDTO> balances = client.get()
                .uri("/stock/balances?variantId={v}", f.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockBalanceResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(balances).hasSize(1);
        assertThat(balances.get(0).getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(0));
    }

    @Test
    void recordMovement_secondIncrement_accumulatesBalance() {
        StockFixture f = buildStockFixture();
        recordMovement(f.variantId, f.locationId, MovementType.INBOUND, 10);
        recordMovement(f.variantId, f.locationId, MovementType.INBOUND, 5);

        List<StockBalanceResponseDTO> balances = client.get()
                .uri("/stock/balances?variantId={v}", f.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockBalanceResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(balances).hasSize(1);
        assertThat(balances.get(0).getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(15));
    }

    @Test
    void recordMovement_returns404_forUnknownVariant() {
        StockFixture f = buildStockFixture();

        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(movementRequest(UUID.randomUUID(), f.locationId(), MovementType.INBOUND, 5))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void getMovementById_returns404_forUnknownId() {
        client.get().uri("/stock/movements/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void recordMovement_rejectsNonPositiveDeltaForInbound() {
        StockFixture f = buildStockFixture();

        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(movementRequest(f.variantId, f.locationId, MovementType.INBOUND, -5))
                .exchange()
                .expectStatus().isBadRequest();

        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(movementRequest(f.variantId, f.locationId, MovementType.INBOUND, 0))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void recordMovement_rejectsNonNegativeDeltaForOutbound() {
        StockFixture f = buildStockFixture();
        recordMovement(f.variantId, f.locationId, MovementType.INBOUND, 10);

        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(movementRequest(f.variantId, f.locationId, MovementType.OUTBOUND, 5))
                .exchange()
                .expectStatus().isBadRequest();

        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(movementRequest(f.variantId, f.locationId, MovementType.OUTBOUND, 0))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void recordTransferPair_movesStockBetweenLocations() {
        StockFixture source = buildStockFixture();
        UUID destination = createLocation();

        recordMovement(source.variantId, source.locationId, MovementType.INBOUND, 20);

        StockMovementResponseDTO transferOut = recordMovement(source.variantId, source.locationId, MovementType.OUTBOUND, -8);
        assertThat(transferOut.getMovementType()).isEqualTo(MovementType.OUTBOUND);
        assertThat(transferOut.getReferenceId()).isNull();

        StockMovementRequestDTO transferInDto = movementRequest(source.variantId, destination, MovementType.INBOUND, 8);
        transferInDto.setReferenceId(transferOut.getId());
        StockMovementResponseDTO transferIn = client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(transferInDto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(StockMovementResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(transferIn).isNotNull();
        assertThat(transferIn.getMovementType()).isEqualTo(MovementType.INBOUND);
        assertThat(transferIn.getReferenceId()).isEqualTo(transferOut.getId());

        List<StockBalanceResponseDTO> sourceBalances = client.get()
                .uri("/stock/balances?variantId={v}&locationId={l}", source.variantId, source.locationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockBalanceResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(sourceBalances).hasSize(1);
        assertThat(sourceBalances.get(0).getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(12));

        List<StockBalanceResponseDTO> destBalances = client.get()
                .uri("/stock/balances?variantId={v}&locationId={l}", source.variantId, destination)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockBalanceResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(destBalances).hasSize(1);
        assertThat(destBalances.get(0).getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(8));
    }

    @Test
    void transferOut_cannotExceedAvailableBalance() {
        StockFixture f = buildStockFixture();
        recordMovement(f.variantId, f.locationId, MovementType.INBOUND, 10);

        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(movementRequest(f.variantId, f.locationId, MovementType.OUTBOUND, -11))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void getMovements_filtersByLocation() {
        StockFixture f = buildStockFixture();
        UUID otherLocation = createLocation();

        recordMovement(f.variantId, f.locationId, MovementType.INBOUND, 10);
        recordMovement(f.variantId, otherLocation, MovementType.INBOUND, 5);

        List<StockMovementResponseDTO> movements = client.get()
                .uri("/stock/movements?locationId={l}", otherLocation)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockMovementResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).getLocationId()).isEqualTo(otherLocation);
    }

    @Test
    void getMovements_filtersByOptionIds() {
        StockFixture f1 = buildStockFixture();
        StockFixture f2 = buildStockFixture();
        recordMovement(f1.variantId, f1.locationId, MovementType.INBOUND, 3);
        recordMovement(f2.variantId, f2.locationId, MovementType.INBOUND, 7);

        List<StockMovementResponseDTO> movements = client.get().uri(uriBuilder -> uriBuilder
                        .path("/stock/movements")
                        .queryParam("optionIds", f1.optionId)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockMovementResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).getVariantId()).isEqualTo(f1.variantId);
    }

    @Test
    void getMovements_unknownOptionId_returnsEmptyNotError() {
        StockFixture f = buildStockFixture();
        recordMovement(f.variantId, f.locationId, MovementType.INBOUND, 5);

        client.get().uri(uriBuilder -> uriBuilder
                        .path("/stock/movements")
                        .queryParam("optionIds", UUID.randomUUID())
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockMovementResponseDTO.class)
                .hasSize(0);
    }

    @Test
    void getBalances_filtersByOptionIds() {
        StockFixture f1 = buildStockFixture();
        StockFixture f2 = buildStockFixture();
        recordMovement(f1.variantId, f1.locationId, MovementType.INBOUND, 20);
        recordMovement(f2.variantId, f2.locationId, MovementType.INBOUND, 30);

        List<StockBalanceResponseDTO> balances = client.get().uri(uriBuilder -> uriBuilder
                        .path("/stock/balances")
                        .queryParam("optionIds", f2.optionId)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockBalanceResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(balances).hasSize(1);
        assertThat(balances.get(0).getVariantId()).isEqualTo(f2.variantId);
        assertThat(balances.get(0).getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(30));
    }

    @Test
    void getBalances_matchAllTrue_requiresEveryOption_matchAllFalseMatchesAny() {
        TwoOptionFixture two = buildTwoOptionVariantFixtures();
        recordMovement(two.bothVariantId, two.locationId, MovementType.INBOUND, 15);
        recordMovement(two.colorOnlyVariantId, two.locationId, MovementType.INBOUND, 9);

        List<StockBalanceResponseDTO> anyMatch = client.get().uri(uriBuilder -> uriBuilder
                        .path("/stock/balances")
                        .queryParam("optionIds", two.colorOptionId)
                        .queryParam("optionIds", two.sizeOptionId)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockBalanceResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(anyMatch).isNotNull();
        assertThat(anyMatch).extracting(StockBalanceResponseDTO::getVariantId)
                .contains(two.bothVariantId, two.colorOnlyVariantId);

        List<StockBalanceResponseDTO> allMatch = client.get().uri(uriBuilder -> uriBuilder
                        .path("/stock/balances")
                        .queryParam("optionIds", two.colorOptionId)
                        .queryParam("optionIds", two.sizeOptionId)
                        .queryParam("matchAll", "true")
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockBalanceResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(allMatch).isNotNull();
        assertThat(allMatch).extracting(StockBalanceResponseDTO::getVariantId).containsExactly(two.bothVariantId);
    }

    private record StockFixture(UUID productId, UUID variantId, UUID locationId, UUID optionId) {}

    private record TwoOptionFixture(UUID bothVariantId, UUID colorOnlyVariantId, UUID locationId,
                                     UUID colorOptionId, UUID sizeOptionId) {}

    // Two products sharing the same Color/Size metadata definitions: one variant selects both
    // options, the other only Color - lets matchAll (AND) be distinguished from the OR default.
    private TwoOptionFixture buildTwoOptionVariantFixtures() {
        LocationRequestDTO locDto = new LocationRequestDTO();
        locDto.setName("Warehouse-" + UUID.randomUUID());
        locDto.setCode("WH-" + UUID.randomUUID().toString().substring(0, 8));
        locDto.setIsActive(true);
        LocationResponseDTO location = client.post().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(locDto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(LocationResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(location).isNotNull();

        ProductMetadataResponseDTO colorMeta = createMetadata("Color-" + UUID.randomUUID());
        MetadataOptionResponseDTO colorOption = addOption(colorMeta.getId(), "Red");
        ProductMetadataResponseDTO sizeMeta = createMetadata("Size-" + UUID.randomUUID());
        MetadataOptionResponseDTO sizeOption = addOption(sizeMeta.getId(), "Large");

        UUID bothProductId = createProduct("STOCK-BOTH-" + UUID.randomUUID());
        assignMetadata(bothProductId, colorMeta.getId(), colorOption.getId());
        assignMetadata(bothProductId, sizeMeta.getId(), sizeOption.getId());
        UUID bothVariantId = createVariant(bothProductId, List.of(colorOption.getId(), sizeOption.getId()));

        UUID colorOnlyProductId = createProduct("STOCK-COLORONLY-" + UUID.randomUUID());
        assignMetadata(colorOnlyProductId, colorMeta.getId(), colorOption.getId());
        UUID colorOnlyVariantId = createVariant(colorOnlyProductId, List.of(colorOption.getId()));

        return new TwoOptionFixture(bothVariantId, colorOnlyVariantId, location.getId(), colorOption.getId(), sizeOption.getId());
    }

    private ProductMetadataResponseDTO createMetadata(String name) {
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

    private MetadataOptionResponseDTO addOption(UUID metadataId, String value) {
        MetadataOptionRequestDTO dto = new MetadataOptionRequestDTO();
        dto.setValue(value);
        MetadataOptionResponseDTO response = client.post().uri("/metadata/{id}/options", metadataId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(MetadataOptionResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private UUID createProduct(String sku) {
        ProductRequestDTO dto = new ProductRequestDTO();
        dto.setSku(sku);
        dto.setName("Stock Product " + sku);
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
        return response.getId();
    }

    private void assignMetadata(UUID productId, UUID metadataId, UUID optionId) {
        // Fetches any existing assignments first so a second call (assigning a different
        // metadata dimension) doesn't wipe out the first, since PUT replaces the full set.
        ProductResponseDTO existing = client.get().uri("/products/{id}", productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(existing).isNotNull();

        List<ProductMetadataAssignmentItemDTO> items = new java.util.ArrayList<>();
        if (existing.getMetadata() != null) {
            existing.getMetadata().forEach(m -> {
                ProductMetadataAssignmentItemDTO item = new ProductMetadataAssignmentItemDTO();
                item.setMetadataId(m.getMetadataId());
                item.setOptionIds(m.getSelectedOptions().stream().map(MetadataOptionResponseDTO::getId).toList());
                items.add(item);
            });
        }
        ProductMetadataAssignmentItemDTO newItem = new ProductMetadataAssignmentItemDTO();
        newItem.setMetadataId(metadataId);
        newItem.setOptionIds(List.of(optionId));
        items.add(newItem);

        client.put().uri("/products/{id}/metadata", productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(items)
                .exchange()
                .expectStatus().isNoContent();
    }

    private UUID createVariant(UUID productId, List<UUID> optionIds) {
        ProductVariantRequestDTO dto = new ProductVariantRequestDTO();
        dto.setOptionIds(optionIds);
        ProductVariantResponseDTO response = client.post()
                .uri("/products/{productId}/variants", productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response.getId();
    }

    private StockFixture buildStockFixture() {
        LocationRequestDTO locDto = new LocationRequestDTO();
        locDto.setName("Warehouse-" + UUID.randomUUID());
        locDto.setCode("WH-" + UUID.randomUUID().toString().substring(0, 8));
        locDto.setIsActive(true);
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
        prodDto.setSku("STOCK-SKU-" + UUID.randomUUID());
        prodDto.setName("Stock Product");
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

        return new StockFixture(product.getId(), variant.getId(), location.getId(), option.getId());
    }

    @Test
    void pendingMovement_doesNotAffectBalanceUntilPosted() {
        StockFixture f = buildStockFixture();

        StockMovementRequestDTO dto = movementRequest(f.variantId, f.locationId, MovementType.INBOUND, 15);
        dto.setStatus(MovementStatus.PENDING);
        StockMovementResponseDTO pending = client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange().expectStatus().isOk()
                .expectBody(StockMovementResponseDTO.class).returnResult().getResponseBody();
        assertThat(pending.getStatus()).isEqualTo(MovementStatus.PENDING);
        assertThat(balanceQuantity(f.variantId)).isEqualByComparingTo(BigDecimal.ZERO);

        StockMovementResponseDTO posted = client.post().uri("/stock/movements/{id}/post", pending.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBody(StockMovementResponseDTO.class).returnResult().getResponseBody();
        assertThat(posted.getStatus()).isEqualTo(MovementStatus.POSTED);
        assertThat(balanceQuantity(f.variantId)).isEqualByComparingTo(BigDecimal.valueOf(15));
    }

    @Test
    void pendingMovement_cancelled_neverAffectsBalance() {
        StockFixture f = buildStockFixture();

        StockMovementRequestDTO dto = movementRequest(f.variantId, f.locationId, MovementType.INBOUND, 15);
        dto.setStatus(MovementStatus.PENDING);
        StockMovementResponseDTO pending = client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange().expectStatus().isOk()
                .expectBody(StockMovementResponseDTO.class).returnResult().getResponseBody();

        StockMovementResponseDTO cancelled = client.post().uri("/stock/movements/{id}/cancel", pending.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBody(StockMovementResponseDTO.class).returnResult().getResponseBody();
        assertThat(cancelled.getStatus()).isEqualTo(MovementStatus.CANCELLED);
        assertThat(balanceQuantity(f.variantId)).isEqualByComparingTo(BigDecimal.ZERO);

        // a cancelled movement can no longer be posted
        client.post().uri("/stock/movements/{id}/post", pending.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void recordMovement_rejectsTransferTypesOnManualEndpoint() {
        StockFixture f = buildStockFixture();
        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(movementRequest(f.variantId, f.locationId, MovementType.TRANSFER_OUT, -3))
                .exchange().expectStatus().isBadRequest();
    }

    private BigDecimal balanceQuantity(UUID variantId) {
        List<StockBalanceResponseDTO> balances = client.get()
                .uri("/stock/balances?variantId={v}", variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBodyList(StockBalanceResponseDTO.class).returnResult().getResponseBody();
        return balances.isEmpty() ? BigDecimal.ZERO : balances.get(0).getQuantity();
    }

    private StockMovementResponseDTO recordMovement(UUID variantId, UUID locationId, MovementType type, int delta) {
        StockMovementResponseDTO response = client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(movementRequest(variantId, locationId, type, delta))
                .exchange()
                .expectStatus().isOk()
                .expectBody(StockMovementResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private static StockMovementRequestDTO movementRequest(UUID variantId, UUID locationId, MovementType type, int delta) {
        StockMovementRequestDTO dto = new StockMovementRequestDTO();
        dto.setVariantId(variantId);
        dto.setLocationId(locationId);
        dto.setMovementType(type);
        dto.setDelta(BigDecimal.valueOf(delta));
        return dto;
    }

    private UUID createLocation() {
        LocationRequestDTO dto = new LocationRequestDTO();
        dto.setName("Loc-" + UUID.randomUUID());
        dto.setCode("LC-" + UUID.randomUUID().toString().substring(0, 8));
        dto.setIsActive(true);
        LocationResponseDTO location = client.post().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(LocationResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(location).isNotNull();
        return location.getId();
    }
}
