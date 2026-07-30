package bime.services;

import bime.clients.RaumClient;
import bime.db.BimeContextService;
import bime.db.BimeDbHandle;
import bime.dto.OrgCurrencyDTO;
import bime.dto.ProductVariantRequestDTO;
import bime.dto.VariantBatchPriceRequestDTO;
import bime.dto.VariantPriceUpdateDTO;
import bime.openbao.OpenBaoService;
import bime.security.BimeAuthentication;
import common.exception.BadRequestException;
import common.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductVariantServiceTest {

    @Mock
    private BimeContextService bimeContextService;
    @Mock
    private RaumClient raumClient;
    @Mock
    private OpenBaoService openBaoService;

    private ProductVariantService service;

    private static final UUID ORG_ID     = UUID.randomUUID();
    private static final UUID USER_ID    = UUID.randomUUID();
    private static final UUID SERVICE_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();

    private static final UUID METADATA_ID = UUID.randomUUID();
    private static final UUID OPTION_ID   = UUID.randomUUID();

    private final BimeAuthentication testAuth = new BimeAuthentication(
            ORG_ID, USER_ID, Map.of(SERVICE_ID.toString(), List.of("BIME_ADMIN")), SERVICE_ID, "token");

    @BeforeEach
    void setUp() {
        service = new ProductVariantService(bimeContextService, raumClient, openBaoService);
    }

    @Test
    void createVariant_rejectsOptionNotInPalette() {
        UUID unknownOption = UUID.randomUUID();

        DatabaseClient client = mockClientForPaletteValidation(
                Map.of("id", PRODUCT_ID),
                List.of(Map.of("metadata_id", METADATA_ID, "option_id", OPTION_ID))
        );
        BimeDbHandle handle = mockHandleWith(client);
        stubContextWith(handle);

        ProductVariantRequestDTO dto = new ProductVariantRequestDTO();
        dto.setOptionIds(List.of(unknownOption));

        StepVerifier.create(service.createVariant(PRODUCT_ID, dto))
                .expectErrorMatches(e -> e instanceof BadRequestException
                        && e.getMessage().contains("not available for this product"))
                .verify();
    }

    @Test
    void createVariant_rejectsDuplicateOptionsForSameMeta() {
        UUID option2 = UUID.randomUUID();

        DatabaseClient client = mockClientForPaletteValidation(
                Map.of("id", PRODUCT_ID),
                List.of(
                        Map.of("metadata_id", METADATA_ID, "option_id", OPTION_ID),
                        Map.of("metadata_id", METADATA_ID, "option_id", option2)
                )
        );
        BimeDbHandle handle = mockHandleWith(client);
        stubContextWith(handle);

        ProductVariantRequestDTO dto = new ProductVariantRequestDTO();
        dto.setOptionIds(List.of(OPTION_ID, option2));

        StepVerifier.create(service.createVariant(PRODUCT_ID, dto))
                .expectErrorMatches(e -> e instanceof BadRequestException
                        && e.getMessage().contains("Multiple options provided"))
                .verify();
    }

    @Test
    void createVariant_rejectsMissingMetadataKey() {
        UUID meta2 = UUID.randomUUID();
        UUID opt2  = UUID.randomUUID();

        DatabaseClient client = mockClientForPaletteValidation(
                Map.of("id", PRODUCT_ID),
                List.of(
                        Map.of("metadata_id", METADATA_ID, "option_id", OPTION_ID),
                        Map.of("metadata_id", meta2, "option_id", opt2)
                )
        );
        BimeDbHandle handle = mockHandleWith(client);
        stubContextWith(handle);

        ProductVariantRequestDTO dto = new ProductVariantRequestDTO();
        dto.setOptionIds(List.of(OPTION_ID));

        StepVerifier.create(service.createVariant(PRODUCT_ID, dto))
                .expectErrorMatches(e -> e instanceof BadRequestException
                        && e.getMessage().contains("no option provided for metadata key"))
                .verify();
    }

    @Test
    void batchUpdatePrices_emptyItems_errorsBadRequest() {
        VariantBatchPriceRequestDTO dto = new VariantBatchPriceRequestDTO();
        dto.setItems(List.of());

        StepVerifier.create(service.batchUpdatePrices(dto))
                .expectErrorMatches(e -> e instanceof BadRequestException
                        && e.getMessage().contains("items must not be empty"))
                .verify();
    }

    @Test
    void batchUpdatePrices_missingPrice_errorsBadRequest() {
        VariantPriceUpdateDTO item = new VariantPriceUpdateDTO();
        item.setVariantId(UUID.randomUUID());
        VariantBatchPriceRequestDTO dto = new VariantBatchPriceRequestDTO();
        dto.setItems(List.of(item));

        StepVerifier.create(service.batchUpdatePrices(dto))
                .expectErrorMatches(e -> e instanceof BadRequestException
                        && e.getMessage().contains("variantId and price are required"))
                .verify();
    }

    @Test
    void batchUpdatePrices_orgHasNoCurrency_errorsBadRequest() {
        UUID variantId = UUID.randomUUID();
        stubContextWith(handleWithNoTx(mock(DatabaseClient.class)));
        when(openBaoService.getToken()).thenReturn("vault-token");
        when(raumClient.getOrgCurrency(ORG_ID, "vault-token"))
                .thenReturn(Mono.just(new OrgCurrencyDTO("USD", "MANUAL", null)));

        StepVerifier.create(service.batchUpdatePrices(batchOf(variantId, "10.00")))
                .expectErrorMatches(e -> e instanceof BadRequestException
                        && e.getMessage().contains("no product pricing currency"))
                .verify();
    }

    @Test
    void batchUpdatePrices_allFound_returnsUpdatedIds() {
        UUID variantId1 = UUID.randomUUID();
        UUID variantId2 = UUID.randomUUID();

        DatabaseClient client = mockClientForBatchUpdate(List.of(variantId1, variantId2));
        stubContextWith(handleWithNoTx(client));
        when(openBaoService.getToken()).thenReturn("vault-token");
        when(raumClient.getOrgCurrency(ORG_ID, "vault-token"))
                .thenReturn(Mono.just(new OrgCurrencyDTO("ARS", "MANUAL", "USD")));

        VariantBatchPriceRequestDTO dto = new VariantBatchPriceRequestDTO();
        dto.setItems(List.of(
                priceUpdate(variantId1, "10.00"),
                priceUpdate(variantId2, "20.00")));

        StepVerifier.create(service.batchUpdatePrices(dto))
                .assertNext(updatedIds -> org.assertj.core.api.Assertions.assertThat(updatedIds)
                        .containsExactlyInAnyOrder(variantId1, variantId2))
                .verifyComplete();
    }

    @Test
    void batchUpdatePrices_variantNotInOrg_errorsNotFound() {
        UUID variantId1 = UUID.randomUUID();
        UUID variantId2 = UUID.randomUUID();

        // Only variantId1 comes back from the UPDATE ... RETURNING - variantId2 doesn't belong to this org
        DatabaseClient client = mockClientForBatchUpdate(List.of(variantId1));
        stubContextWith(handleWithNoTx(client));
        when(openBaoService.getToken()).thenReturn("vault-token");
        when(raumClient.getOrgCurrency(ORG_ID, "vault-token"))
                .thenReturn(Mono.just(new OrgCurrencyDTO("ARS", "MANUAL", "USD")));

        StepVerifier.create(service.batchUpdatePrices(batchOf(variantId1, "10.00", variantId2, "20.00")))
                .expectErrorMatches(e -> e instanceof NotFoundException
                        && e.getMessage().contains(variantId2.toString()))
                .verify();
    }

    private static VariantPriceUpdateDTO priceUpdate(UUID variantId, String price) {
        VariantPriceUpdateDTO item = new VariantPriceUpdateDTO();
        item.setVariantId(variantId);
        item.setPrice(new BigDecimal(price));
        return item;
    }

    private static VariantBatchPriceRequestDTO batchOf(UUID variantId, String price) {
        VariantBatchPriceRequestDTO dto = new VariantBatchPriceRequestDTO();
        dto.setItems(List.of(priceUpdate(variantId, price)));
        return dto;
    }

    private static VariantBatchPriceRequestDTO batchOf(UUID variantId1, String price1, UUID variantId2, String price2) {
        VariantBatchPriceRequestDTO dto = new VariantBatchPriceRequestDTO();
        dto.setItems(List.of(priceUpdate(variantId1, price1), priceUpdate(variantId2, price2)));
        return dto;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private DatabaseClient mockClientForBatchUpdate(List<UUID> returnedIds) {
        DatabaseClient client = mock(DatabaseClient.class);

        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec fetch = mock(FetchSpec.class);
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetch);
        when(fetch.all()).thenReturn(Flux.fromIterable(returnedIds)
                .map(id -> (Map<String, Object>) Map.<String, Object>of("id", id)));

        when(client.sql(anyString())).thenReturn(spec);

        return client;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private DatabaseClient mockClientForPaletteValidation(
            Map<String, Object> productRow,
            List<Map<String, Object>> paletteRows) {

        DatabaseClient client = mock(DatabaseClient.class);

        DatabaseClient.GenericExecuteSpec productSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec productFetch = mock(FetchSpec.class);
        when(productSpec.bind(anyString(), any())).thenReturn(productSpec);
        when(productSpec.fetch()).thenReturn(productFetch);
        when(productFetch.one()).thenReturn(Mono.just(productRow));

        DatabaseClient.GenericExecuteSpec paletteSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec paletteFetch = mock(FetchSpec.class);
        when(paletteSpec.bind(anyString(), any())).thenReturn(paletteSpec);
        when(paletteSpec.fetch()).thenReturn(paletteFetch);
        when(paletteFetch.all()).thenReturn(
                reactor.core.publisher.Flux.fromIterable(paletteRows));

        when(client.sql(anyString()))
                .thenReturn(productSpec)
                .thenReturn(paletteSpec);

        return client;
    }

    @SuppressWarnings("unchecked")
    private void stubContextWith(BimeDbHandle handle) {
        when(bimeContextService.withHandle(any())).thenAnswer(inv -> {
            BiFunction<BimeAuthentication, BimeDbHandle, Mono<?>> fn = inv.getArgument(0);
            return fn.apply(testAuth, handle);
        });
    }

    /** For flows (like batchUpdatePrices) that don't use handle.tx() - no transactional() stub needed. */
    private BimeDbHandle handleWithNoTx(DatabaseClient client) {
        return new BimeDbHandle(client, mock(org.springframework.transaction.reactive.TransactionalOperator.class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private BimeDbHandle mockHandleWith(DatabaseClient client) {
        org.springframework.transaction.reactive.TransactionalOperator tx =
                mock(org.springframework.transaction.reactive.TransactionalOperator.class);
        when(tx.transactional(any(reactor.core.publisher.Mono.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        return new BimeDbHandle(client, tx);
    }
}
