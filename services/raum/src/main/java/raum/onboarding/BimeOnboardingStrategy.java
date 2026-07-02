package raum.onboarding;

import common.dto.CredentialsDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import raum.clients.BimeClient;
import raum.clients.BimeClient.MetadataAssignmentItem;
import raum.dto.OnboardingRequestDTO;
import raum.openbao.OpenBaoService;
import raum.repository.CredentialsRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Order(2)
public class BimeOnboardingStrategy implements OnboardingStrategy {

    private static final String SCHEMA_RESOURCE = "bime-init.sql";

    private final BimeClient bimeClient;
    private final CredentialsRepository credentialsRepository;
    private final OpenBaoService openBaoService;

    @Value("${bime.service-id}")
    private UUID bimeServiceId;

    public BimeOnboardingStrategy(BimeClient bimeClient, CredentialsRepository credentialsRepository, OpenBaoService openBaoService) {
        this.bimeClient = bimeClient;
        this.credentialsRepository = credentialsRepository;
        this.openBaoService = openBaoService;
    }

    @Override
    public UUID getServiceId() {
        return bimeServiceId;
    }

    @Override
    public Map<String, String> extractConfig(OnboardingRequestDTO request) {
        return Map.of("preset", request.getBimePreset().name());
    }

    @Override
    public OnboardingRequestDTO buildRequestFromConfig(Map<String, String> config) {
        OnboardingRequestDTO dto = new OnboardingRequestDTO();
        dto.setBimePreset(BimePreset.valueOf(config.get("preset")));
        return dto;
    }

    @Override
    public Mono<Void> execute(UUID orgId, CredentialsDTO credentials, OnboardingRequestDTO request, OnboardingContext context) {
        String jwt = context.getJwt();
        return SchemaProvisioner.staticClientFor(credentialsRepository, openBaoService, orgId, bimeServiceId, credentials)
                .flatMap(schemaClient -> SchemaProvisioner.applySchema(schemaClient, SCHEMA_RESOURCE))
                .then(Mono.defer(() -> switch (request.getBimePreset()) {
                    case CLOTHING_STORE -> clothingStore(jwt);
                    case BOOK_STORE -> bookStore(jwt);
                    case REPAIR_SHOP -> repairShop(jwt);
                    case STORAGE_WAREHOUSE -> storageWarehouse(jwt);
                }));
    }

    // -------------------------------------------------------------------------
    // Presets
    // -------------------------------------------------------------------------

    private Mono<Void> clothingStore(String jwt) {
        // Stock is seeded into the first location created (Main Store); the others exist as
        // valid transfer/receiving destinations but start empty, matching a real store rollout.
        Mono<UUID> locations = Flux.just(
                new String[]{"Main Store", "WH-01"},
                new String[]{"Branch", "WH-02"},
                new String[]{"Stockroom", "WH-03"}
        ).concatMap(l -> bimeClient.createLocation(jwt, l[0], l[1])).collectList().map(ids -> ids.get(0));

        return locations.flatMap(stockLocationId -> Mono.zip(
                createMetadataWithOptions(jwt, "Size", List.of("S", "M", "L", "XL")),
                createMetadataWithOptions(jwt, "Colour", List.of("Black", "White", "Red"))
        ).flatMap(tuple -> {
            MetaResult size = tuple.getT1();
            MetaResult colour = tuple.getT2();
            List<MetadataAssignmentItem> assignments = List.of(
                    new MetadataAssignmentItem(size.metaId(), size.optionIds()),
                    new MetadataAssignmentItem(colour.metaId(), colour.optionIds())
            );
            // size options: S=0, M=1, L=2, XL=3 | colour options: Black=0, White=1, Red=2
            return Mono.when(
                    seedProduct(jwt, "TSHIRT-001", "T-Shirt", "Classic cotton t-shirt", assignments, List.of(
                            new Variant(List.of(size.optionIds().get(0), colour.optionIds().get(0)), "TSHIRT-001-S-BLK", 40),
                            new Variant(List.of(size.optionIds().get(1), colour.optionIds().get(1)), "TSHIRT-001-M-WHT", 35),
                            new Variant(List.of(size.optionIds().get(2), colour.optionIds().get(2)), "TSHIRT-001-L-RED", 25)
                    ), stockLocationId),
                    seedProduct(jwt, "JEANS-001", "Jeans", "Slim fit denim jeans", assignments, List.of(
                            new Variant(List.of(size.optionIds().get(0), colour.optionIds().get(0)), "JEANS-001-S-BLK", 20),
                            new Variant(List.of(size.optionIds().get(1), colour.optionIds().get(0)), "JEANS-001-M-BLK", 30),
                            new Variant(List.of(size.optionIds().get(2), colour.optionIds().get(1)), "JEANS-001-L-WHT", 15)
                    ), stockLocationId)
            );
        }));
    }

    private Mono<Void> bookStore(String jwt) {
        Mono<UUID> locations = bimeClient.createLocation(jwt, "Store", "BS-01");

        return locations.flatMap(stockLocationId -> Mono.zip(
                createMetadataWithOptions(jwt, "Format", List.of("Hardcover", "Paperback", "eBook")),
                createMetadataWithOptions(jwt, "Genre", List.of("Fiction", "Non-Fiction", "Science"))
        ).flatMap(tuple -> {
            MetaResult format = tuple.getT1();
            MetaResult genre = tuple.getT2();
            // format: Hardcover=0, Paperback=1, eBook=2 | genre: Fiction=0, Non-Fiction=1, Science=2
            List<MetadataAssignmentItem> novelAssignments = List.of(
                    new MetadataAssignmentItem(format.metaId(), List.of(format.optionIds().get(0), format.optionIds().get(1))),
                    new MetadataAssignmentItem(genre.metaId(), List.of(genre.optionIds().get(0)))
            );
            List<MetadataAssignmentItem> guideAssignments = List.of(
                    new MetadataAssignmentItem(format.metaId(), List.of(format.optionIds().get(1), format.optionIds().get(2))),
                    new MetadataAssignmentItem(genre.metaId(), List.of(genre.optionIds().get(1)))
            );
            return Mono.when(
                    seedProduct(jwt, "BOOK-001", "Sample Novel", "A demo fiction novel", novelAssignments, List.of(
                            new Variant(List.of(format.optionIds().get(0), genre.optionIds().get(0)), "BOOK-001-HC", 15),
                            new Variant(List.of(format.optionIds().get(1), genre.optionIds().get(0)), "BOOK-001-PB", 50)
                    ), stockLocationId),
                    seedProduct(jwt, "BOOK-002", "Sample Guide", "A demo non-fiction guide", guideAssignments, List.of(
                            new Variant(List.of(format.optionIds().get(1), genre.optionIds().get(1)), "BOOK-002-PB", 40),
                            new Variant(List.of(format.optionIds().get(2), genre.optionIds().get(1)), "BOOK-002-EB", 60)
                    ), stockLocationId)
            );
        }));
    }

    private Mono<Void> repairShop(String jwt) {
        Mono<UUID> locations = Flux.just(
                new String[]{"Workshop", "WS-01"},
                new String[]{"Parts Storage", "PS-01"}
        ).concatMap(l -> bimeClient.createLocation(jwt, l[0], l[1])).collectList().map(ids -> ids.get(1));

        return locations.flatMap(stockLocationId -> createMetadataWithOptions(jwt, "Condition", List.of("New", "Used", "Refurbished"))
                .flatMap(condition -> {
                    List<MetadataAssignmentItem> screenAssignments = List.of(
                            new MetadataAssignmentItem(condition.metaId(),
                                    List.of(condition.optionIds().get(0), condition.optionIds().get(2)))
                    );
                    List<MetadataAssignmentItem> batteryAssignments = List.of(
                            new MetadataAssignmentItem(condition.metaId(), condition.optionIds())
                    );
                    // condition: New=0, Used=1, Refurbished=2
                    return Mono.when(
                            seedProduct(jwt, "PART-001", "Replacement Screen", "Display panel replacement", screenAssignments, List.of(
                                    new Variant(List.of(condition.optionIds().get(0)), "PART-001-NEW", 12),
                                    new Variant(List.of(condition.optionIds().get(2)), "PART-001-REF", 6)
                            ), stockLocationId),
                            seedProduct(jwt, "PART-002", "Battery", "Standard battery pack", batteryAssignments, List.of(
                                    new Variant(List.of(condition.optionIds().get(0)), "PART-002-NEW", 25),
                                    new Variant(List.of(condition.optionIds().get(1)), "PART-002-USED", 8),
                                    new Variant(List.of(condition.optionIds().get(2)), "PART-002-REF", 10)
                            ), stockLocationId)
                    );
                }));
    }

    private Mono<Void> storageWarehouse(String jwt) {
        Mono<UUID> locations = Flux.just(
                new String[]{"Receiving", "RCV-01"},
                new String[]{"Zone A", "ZA-01"},
                new String[]{"Zone B", "ZB-01"},
                new String[]{"Dispatch", "DSP-01"}
        ).concatMap(l -> bimeClient.createLocation(jwt, l[0], l[1])).collectList().map(ids -> ids.get(1));

        return locations.flatMap(stockLocationId -> createMetadataWithOptions(jwt, "Category", List.of("Electronics", "Furniture", "Supplies"))
                .flatMap(category -> {
                    // category: Electronics=0, Furniture=1, Supplies=2
                    List<MetadataAssignmentItem> electronicsAssignments = List.of(
                            new MetadataAssignmentItem(category.metaId(), List.of(category.optionIds().get(0)))
                    );
                    List<MetadataAssignmentItem> suppliesAssignments = List.of(
                            new MetadataAssignmentItem(category.metaId(), List.of(category.optionIds().get(2)))
                    );
                    return Mono.when(
                            seedProduct(jwt, "ITEM-001", "Electronic Component", "General electronic component",
                                    electronicsAssignments, List.of(
                                            new Variant(List.of(category.optionIds().get(0)), "ITEM-001-ELEC", 200)
                                    ), stockLocationId),
                            seedProduct(jwt, "ITEM-002", "Storage Box", "Standard storage box",
                                    suppliesAssignments, List.of(
                                            new Variant(List.of(category.optionIds().get(2)), "ITEM-002-SUPP", 500)
                                    ), stockLocationId)
                    );
                }));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Mono<MetaResult> createMetadataWithOptions(String jwt, String name, List<String> values) {
        return bimeClient.createMetadata(jwt, name)
                .flatMap(metaId -> Flux.fromIterable(values)
                        .concatMap(value -> bimeClient.addOption(jwt, metaId, value))
                        .collectList()
                        .map(optionIds -> new MetaResult(metaId, optionIds))
                );
    }

    private Mono<Void> seedProduct(String jwt, String sku, String name, String description,
                                   List<MetadataAssignmentItem> assignments, List<Variant> variants, UUID stockLocationId) {
        return bimeClient.createProduct(jwt, sku, name, description)
                .flatMap(productId -> bimeClient.assignMetadata(jwt, productId, assignments)
                        .thenMany(Flux.fromIterable(variants)
                                .concatMap(v -> bimeClient.createVariant(jwt, productId, v.optionIds(), v.sku())
                                        .flatMap(variantId -> bimeClient.recordStockMovement(jwt, variantId, stockLocationId, v.quantity()))))
                        .then()
                );
    }

    private record MetaResult(UUID metaId, List<UUID> optionIds) {}
    private record Variant(List<UUID> optionIds, String sku, int quantity) {}
}
