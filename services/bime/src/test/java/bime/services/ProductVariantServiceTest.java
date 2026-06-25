package bime.services;

import bime.db.BimeContextService;
import bime.db.BimeDbHandle;
import bime.dto.ProductVariantRequestDTO;
import bime.security.BimeAuthentication;
import common.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

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
        service = new ProductVariantService(bimeContextService);
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private BimeDbHandle mockHandleWith(DatabaseClient client) {
        org.springframework.transaction.reactive.TransactionalOperator tx =
                mock(org.springframework.transaction.reactive.TransactionalOperator.class);
        when(tx.transactional(any(reactor.core.publisher.Mono.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        return new BimeDbHandle(client, tx);
    }
}
