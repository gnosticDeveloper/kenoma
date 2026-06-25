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

        StockMovementResponseDTO movement = recordMovement(f.variantId, f.locationId, "RECEIPT", 10);

        assertThat(movement).isNotNull();
        assertThat(movement.getId()).isNotNull();
        assertThat(movement.getVariantId()).isEqualTo(f.variantId);
        assertThat(movement.getLocationId()).isEqualTo(f.locationId);
        assertThat(movement.getDelta()).isEqualTo(10);
        assertThat(movement.getMovementType()).isEqualTo("RECEIPT");

        List<StockBalanceResponseDTO> balances = client.get()
                .uri("/stock/balances?variantId={v}", f.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockBalanceResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(balances).hasSize(1);
        assertThat(balances.get(0).getQuantity()).isEqualTo(10);
    }

    @Test
    void getMovementById_returnsMovement() {
        StockFixture f = buildStockFixture();
        StockMovementResponseDTO created = recordMovement(f.variantId, f.locationId, "RECEIPT", 5);

        StockMovementResponseDTO response = client.get()
                .uri("/stock/movements/{id}", created.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(StockMovementResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(created.getId());
        assertThat(response.getDelta()).isEqualTo(5);
    }

    @Test
    void getMovements_filtersByVariantId() {
        StockFixture f1 = buildStockFixture();
        StockFixture f2 = buildStockFixture();

        recordMovement(f1.variantId, f1.locationId, "RECEIPT", 3);
        recordMovement(f2.variantId, f2.locationId, "RECEIPT", 7);

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

        recordMovement(f1.variantId, f1.locationId, "RECEIPT", 20);
        recordMovement(f2.variantId, f2.locationId, "RECEIPT", 30);

        List<StockBalanceResponseDTO> balances = client.get()
                .uri("/stock/balances?variantId={v}", f2.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockBalanceResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(balances).hasSize(1);
        assertThat(balances.get(0).getQuantity()).isEqualTo(30);
    }

    @Test
    void recordMovement_rejectsNegativeBalance() {
        StockFixture f = buildStockFixture();
        recordMovement(f.variantId, f.locationId, "RECEIPT", 10);

        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(movementRequest(f.variantId, f.locationId, "ISSUE", -20))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void recordMovement_secondIncrement_accumulatesBalance() {
        StockFixture f = buildStockFixture();
        recordMovement(f.variantId, f.locationId, "RECEIPT", 10);
        recordMovement(f.variantId, f.locationId, "RECEIPT", 5);

        List<StockBalanceResponseDTO> balances = client.get()
                .uri("/stock/balances?variantId={v}", f.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockBalanceResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(balances).hasSize(1);
        assertThat(balances.get(0).getQuantity()).isEqualTo(15);
    }

    @Test
    void recordMovement_returns404_forUnknownVariant() {
        StockFixture f = buildStockFixture();

        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(movementRequest(UUID.randomUUID(), f.locationId(), "RECEIPT", 5))
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
    void recordTransferPair_movesStockBetweenLocations() {
        StockFixture source = buildStockFixture();
        UUID destination = createLocation();

        recordMovement(source.variantId, source.locationId, "RECEIPT", 20);

        StockMovementResponseDTO transferOut = recordMovement(source.variantId, source.locationId, "TRANSFER_OUT", -8);
        assertThat(transferOut.getMovementType()).isEqualTo("TRANSFER_OUT");
        assertThat(transferOut.getReferenceId()).isNull();

        StockMovementRequestDTO transferInDto = movementRequest(source.variantId, destination, "TRANSFER_IN", 8);
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
        assertThat(transferIn.getMovementType()).isEqualTo("TRANSFER_IN");
        assertThat(transferIn.getReferenceId()).isEqualTo(transferOut.getId());

        List<StockBalanceResponseDTO> sourceBalances = client.get()
                .uri("/stock/balances?variantId={v}&locationId={l}", source.variantId, source.locationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockBalanceResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(sourceBalances).hasSize(1);
        assertThat(sourceBalances.get(0).getQuantity()).isEqualTo(12);

        List<StockBalanceResponseDTO> destBalances = client.get()
                .uri("/stock/balances?variantId={v}&locationId={l}", source.variantId, destination)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StockBalanceResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(destBalances).hasSize(1);
        assertThat(destBalances.get(0).getQuantity()).isEqualTo(8);
    }

    @Test
    void transferOut_cannotExceedAvailableBalance() {
        StockFixture f = buildStockFixture();
        recordMovement(f.variantId, f.locationId, "RECEIPT", 10);

        client.post().uri("/stock/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(movementRequest(f.variantId, f.locationId, "TRANSFER_OUT", -11))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void getMovements_filtersByLocation() {
        StockFixture f = buildStockFixture();
        UUID otherLocation = createLocation();

        recordMovement(f.variantId, f.locationId, "RECEIPT", 10);
        recordMovement(f.variantId, otherLocation, "RECEIPT", 5);

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

    private record StockFixture(UUID productId, UUID variantId, UUID locationId) {}

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

        return new StockFixture(product.getId(), variant.getId(), location.getId());
    }

    private StockMovementResponseDTO recordMovement(UUID variantId, UUID locationId, String type, int delta) {
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

    private static StockMovementRequestDTO movementRequest(UUID variantId, UUID locationId, String type, int delta) {
        StockMovementRequestDTO dto = new StockMovementRequestDTO();
        dto.setVariantId(variantId);
        dto.setLocationId(locationId);
        dto.setMovementType(type);
        dto.setDelta(delta);
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
