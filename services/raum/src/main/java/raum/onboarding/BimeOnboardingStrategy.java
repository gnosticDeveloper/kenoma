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
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
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
        return Map.of(
                "preset", request.getBimePreset().name(),
                "locale", request.getLocale() == null ? "en" : request.getLocale()
        );
    }

    @Override
    public OnboardingRequestDTO buildRequestFromConfig(Map<String, String> config) {
        OnboardingRequestDTO dto = new OnboardingRequestDTO();
        dto.setBimePreset(BimePreset.valueOf(config.get("preset")));
        dto.setLocale(config.get("locale"));
        return dto;
    }

    @Override
    public Mono<Void> execute(UUID orgId, CredentialsDTO credentials, OnboardingRequestDTO request, OnboardingContext context) {
        String jwt = context.getJwt();
        ResourceBundle p = presetsFor(request.getLocale());
        return SchemaProvisioner.staticClientFor(credentialsRepository, openBaoService, orgId, bimeServiceId, credentials)
                .flatMap(schemaClient -> SchemaProvisioner.applySchema(schemaClient, SCHEMA_RESOURCE))
                .then(Mono.defer(() -> switch (request.getBimePreset()) {
                    case CLOTHING_STORE -> clothingStore(jwt, p);
                    case BOOK_STORE -> bookStore(jwt, p);
                    case REPAIR_SHOP -> repairShop(jwt, p);
                    case STORAGE_WAREHOUSE -> storageWarehouse(jwt, p);
                }));
    }

    private static ResourceBundle presetsFor(String locale) {
        Locale target = locale == null || locale.isBlank() ? Locale.ENGLISH : Locale.forLanguageTag(locale);
        try {
            return ResourceBundle.getBundle("presets", target);
        } catch (MissingResourceException e) {
            return ResourceBundle.getBundle("presets", Locale.ENGLISH);
        }
    }

    // -------------------------------------------------------------------------
    // Presets
    // -------------------------------------------------------------------------

    private Mono<Void> clothingStore(String jwt, ResourceBundle p) {
        // Stock is seeded into the first location created (Main Store); the others exist as
        // valid transfer/receiving destinations but start empty, matching a real store rollout.
        Mono<UUID> locations = Flux.just(
                new String[]{p.getString("clothing.location.mainStore"), "WH-01"},
                new String[]{p.getString("clothing.location.branch"), "WH-02"},
                new String[]{p.getString("clothing.location.stockroom"), "WH-03"}
        ).concatMap(l -> bimeClient.createLocation(jwt, l[0], l[1])).collectList().map(ids -> ids.get(0));

        return locations.flatMap(stockLocationId -> Mono.zip(
                createMetadataWithOptions(jwt, p.getString("clothing.meta.size"), List.of(
                        p.getString("clothing.option.size.s"), p.getString("clothing.option.size.m"),
                        p.getString("clothing.option.size.l"), p.getString("clothing.option.size.xl"))),
                createMetadataWithOptions(jwt, p.getString("clothing.meta.colour"), List.of(
                        p.getString("clothing.option.colour.black"), p.getString("clothing.option.colour.white"),
                        p.getString("clothing.option.colour.red")))
        ).flatMap(tuple -> {
            MetaResult size = tuple.getT1();
            MetaResult colour = tuple.getT2();
            List<MetadataAssignmentItem> assignments = List.of(
                    new MetadataAssignmentItem(size.metaId(), size.optionIds()),
                    new MetadataAssignmentItem(colour.metaId(), colour.optionIds())
            );
            // size options: S=0, M=1, L=2, XL=3 | colour options: Black=0, White=1, Red=2
            return Mono.when(
                    seedProduct(jwt, "TSHIRT-001", p.getString("clothing.product.tshirt.name"),
                            p.getString("clothing.product.tshirt.desc"), assignments, List.of(
                            new Variant(List.of(size.optionIds().get(0), colour.optionIds().get(0)), "TSHIRT-001-S-BLK", 40),
                            new Variant(List.of(size.optionIds().get(1), colour.optionIds().get(1)), "TSHIRT-001-M-WHT", 35),
                            new Variant(List.of(size.optionIds().get(2), colour.optionIds().get(2)), "TSHIRT-001-L-RED", 25)
                    ), stockLocationId),
                    seedProduct(jwt, "JEANS-001", p.getString("clothing.product.jeans.name"),
                            p.getString("clothing.product.jeans.desc"), assignments, List.of(
                            new Variant(List.of(size.optionIds().get(0), colour.optionIds().get(0)), "JEANS-001-S-BLK", 20),
                            new Variant(List.of(size.optionIds().get(1), colour.optionIds().get(0)), "JEANS-001-M-BLK", 30),
                            new Variant(List.of(size.optionIds().get(2), colour.optionIds().get(1)), "JEANS-001-L-WHT", 15)
                    ), stockLocationId)
            );
        }));
    }

    private Mono<Void> bookStore(String jwt, ResourceBundle p) {
        Mono<UUID> locations = bimeClient.createLocation(jwt, p.getString("book.location.store"), "BS-01");

        return locations.flatMap(stockLocationId -> Mono.zip(
                createMetadataWithOptions(jwt, p.getString("book.meta.format"), List.of(
                        p.getString("book.option.format.hardcover"), p.getString("book.option.format.paperback"),
                        p.getString("book.option.format.ebook"))),
                createMetadataWithOptions(jwt, p.getString("book.meta.genre"), List.of(
                        p.getString("book.option.genre.fiction"), p.getString("book.option.genre.nonfiction"),
                        p.getString("book.option.genre.science")))
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
                    seedProduct(jwt, "BOOK-001", p.getString("book.product.novel.name"),
                            p.getString("book.product.novel.desc"), novelAssignments, List.of(
                            new Variant(List.of(format.optionIds().get(0), genre.optionIds().get(0)), "BOOK-001-HC", 15),
                            new Variant(List.of(format.optionIds().get(1), genre.optionIds().get(0)), "BOOK-001-PB", 50)
                    ), stockLocationId),
                    seedProduct(jwt, "BOOK-002", p.getString("book.product.guide.name"),
                            p.getString("book.product.guide.desc"), guideAssignments, List.of(
                            new Variant(List.of(format.optionIds().get(1), genre.optionIds().get(1)), "BOOK-002-PB", 40),
                            new Variant(List.of(format.optionIds().get(2), genre.optionIds().get(1)), "BOOK-002-EB", 60)
                    ), stockLocationId)
            );
        }));
    }

    private Mono<Void> repairShop(String jwt, ResourceBundle p) {
        Mono<UUID> locations = Flux.just(
                new String[]{p.getString("repair.location.workshop"), "WS-01"},
                new String[]{p.getString("repair.location.partsStorage"), "PS-01"}
        ).concatMap(l -> bimeClient.createLocation(jwt, l[0], l[1])).collectList().map(ids -> ids.get(1));

        return locations.flatMap(stockLocationId -> createMetadataWithOptions(jwt, p.getString("repair.meta.condition"), List.of(
                        p.getString("repair.option.condition.new"), p.getString("repair.option.condition.used"),
                        p.getString("repair.option.condition.refurbished")))
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
                            seedProduct(jwt, "PART-001", p.getString("repair.product.screen.name"),
                                    p.getString("repair.product.screen.desc"), screenAssignments, List.of(
                                    new Variant(List.of(condition.optionIds().get(0)), "PART-001-NEW", 12),
                                    new Variant(List.of(condition.optionIds().get(2)), "PART-001-REF", 6)
                            ), stockLocationId),
                            seedProduct(jwt, "PART-002", p.getString("repair.product.battery.name"),
                                    p.getString("repair.product.battery.desc"), batteryAssignments, List.of(
                                    new Variant(List.of(condition.optionIds().get(0)), "PART-002-NEW", 25),
                                    new Variant(List.of(condition.optionIds().get(1)), "PART-002-USED", 8),
                                    new Variant(List.of(condition.optionIds().get(2)), "PART-002-REF", 10)
                            ), stockLocationId)
                    );
                }));
    }

    private Mono<Void> storageWarehouse(String jwt, ResourceBundle p) {
        Mono<UUID> locations = Flux.just(
                new String[]{p.getString("warehouse.location.receiving"), "RCV-01"},
                new String[]{p.getString("warehouse.location.zoneA"), "ZA-01"},
                new String[]{p.getString("warehouse.location.zoneB"), "ZB-01"},
                new String[]{p.getString("warehouse.location.dispatch"), "DSP-01"}
        ).concatMap(l -> bimeClient.createLocation(jwt, l[0], l[1])).collectList().map(ids -> ids.get(1));

        return locations.flatMap(stockLocationId -> createMetadataWithOptions(jwt, p.getString("warehouse.meta.category"), List.of(
                        p.getString("warehouse.option.category.electronics"), p.getString("warehouse.option.category.furniture"),
                        p.getString("warehouse.option.category.supplies")))
                .flatMap(category -> {
                    // category: Electronics=0, Furniture=1, Supplies=2
                    List<MetadataAssignmentItem> electronicsAssignments = List.of(
                            new MetadataAssignmentItem(category.metaId(), List.of(category.optionIds().get(0)))
                    );
                    List<MetadataAssignmentItem> suppliesAssignments = List.of(
                            new MetadataAssignmentItem(category.metaId(), List.of(category.optionIds().get(2)))
                    );
                    return Mono.when(
                            seedProduct(jwt, "ITEM-001", p.getString("warehouse.product.component.name"),
                                    p.getString("warehouse.product.component.desc"),
                                    electronicsAssignments, List.of(
                                            new Variant(List.of(category.optionIds().get(0)), "ITEM-001-ELEC", 200)
                                    ), stockLocationId),
                            seedProduct(jwt, "ITEM-002", p.getString("warehouse.product.box.name"),
                                    p.getString("warehouse.product.box.desc"),
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
