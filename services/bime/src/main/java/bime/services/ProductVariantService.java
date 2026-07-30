package bime.services;

import bime.clients.RaumClient;
import bime.db.BimeContextService;
import bime.db.BimeDbHandle;
import bime.dto.MetadataOptionResponseDTO;
import bime.openbao.OpenBaoService;
import org.springframework.r2dbc.core.DatabaseClient;
import bime.dto.ProductVariantRequestDTO;
import bime.dto.ProductVariantResponseDTO;
import bime.dto.VariantBatchPriceRequestDTO;
import bime.dto.VariantPriceUpdateDTO;
import bime.dto.VariantStockDTO;
import bime.security.BimeAuthentication;
import common.exception.BadRequestException;
import common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductVariantService {

    private final BimeContextService ctx;
    private final RaumClient raumClient;
    private final OpenBaoService openBaoService;

    private record Palette(Set<UUID> assignedMetadataIds, Map<UUID, UUID> optionToMetadata) {}

    public Mono<ProductVariantResponseDTO> createVariant(UUID productId, ProductVariantRequestDTO dto) {
        Mono<ProductVariantResponseDTO> priceError = priceValidationError(dto);
        if (priceError != null) {
            return priceError;
        }
        return ctx.withHandle((caller, handle) -> Mono.from(handle.tx().transactional(
                verifyProductExists(handle, productId, caller.getOrgId())
                        .then(loadPalette(handle, productId))
                        .flatMap(palette -> validateOptions(palette, dto.getOptionIds()))
                        .flatMap(validatedOptionIds ->
                                insertVariant(handle, productId, caller.getOrgId(), dto)
                                        .flatMap(variantId ->
                                                insertVariantOptions(handle, variantId, validatedOptionIds)
                                                        .thenReturn(variantId))
                        )
                        .flatMap(variantId -> fetchVariantById(handle, variantId, productId, caller.getOrgId()))
        )));
    }

    public Flux<ProductVariantResponseDTO> getVariantsForProduct(UUID productId, String targetCurrency) {
        return ctx.withHandleMany((caller, handle) ->
                verifyProductExists(handle, productId, caller.getOrgId())
                        .thenMany(loadVariantsForProduct(handle, productId, caller.getOrgId()))
                        .collectList()
                        .flatMapMany(variants -> applyCurrencyConversion(variants, targetCurrency))
        );
    }

    public Mono<ProductVariantResponseDTO> getVariantById(UUID productId, UUID variantId, String targetCurrency) {
        return ctx.withHandle((caller, handle) ->
                fetchVariantById(handle, variantId, productId, caller.getOrgId())
                        .flatMap(variant -> applyCurrencyConversion(List.of(variant), targetCurrency)
                                .next())
        );
    }

    /** Converts each variant's price to targetCurrency, fetching each distinct source currency's rate once. */
    private Flux<ProductVariantResponseDTO> applyCurrencyConversion(List<ProductVariantResponseDTO> variants,
                                                                      String targetCurrency) {
        if (targetCurrency == null || targetCurrency.isBlank()) {
            return Flux.fromIterable(variants);
        }
        String target = targetCurrency.toUpperCase();
        Set<String> sourceCurrencies = variants.stream()
                .map(ProductVariantResponseDTO::getPriceCurrency)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (sourceCurrencies.isEmpty()) {
            return Flux.fromIterable(variants);
        }
        return Flux.fromIterable(sourceCurrencies)
                .flatMap(source -> raumClient.getRate(source, target, openBaoService.getToken())
                        .map(rate -> Map.entry(source, rate)))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .flatMapMany(rates -> Flux.fromIterable(variants)
                        .map(variant -> {
                            if (variant.getPrice() == null || variant.getPriceCurrency() == null) {
                                return variant;
                            }
                            BigDecimal rate = rates.get(variant.getPriceCurrency());
                            variant.setPrice(variant.getPrice().multiply(rate));
                            variant.setPriceCurrency(target);
                            return variant;
                        }));
    }

    public Mono<ProductVariantResponseDTO> patchVariant(UUID productId, UUID variantId, ProductVariantRequestDTO dto) {
        Mono<ProductVariantResponseDTO> priceError = priceValidationError(dto);
        if (priceError != null) {
            return priceError;
        }
        return ctx.withHandle((caller, handle) -> {
            DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                    UPDATE product_variants
                    SET sku            = COALESCE(:sku, sku),
                        is_active      = COALESCE(:isActive, is_active),
                        price          = COALESCE(:price, price),
                        price_currency = COALESCE(:priceCurrency, price_currency)
                    WHERE id = :variantId AND product_id = :productId AND org_id = :orgId
                    RETURNING id
                    """);
            if (dto.getSku() != null) {
                spec = spec.bind("sku", dto.getSku());
            } else {
                spec = spec.bindNull("sku", String.class);
            }
            if (dto.getIsActive() != null) {
                spec = spec.bind("isActive", dto.getIsActive());
            } else {
                spec = spec.bindNull("isActive", Boolean.class);
            }
            if (dto.getPrice() != null) {
                spec = spec.bind("price", dto.getPrice()).bind("priceCurrency", dto.getPriceCurrency().toUpperCase());
            } else {
                spec = spec.bindNull("price", BigDecimal.class).bindNull("priceCurrency", String.class);
            }
            return spec.bind("variantId", variantId)
                    .bind("productId", productId)
                    .bind("orgId", caller.getOrgId())
                    .fetch()
                    .one()
                    .switchIfEmpty(Mono.error(new NotFoundException("Variant not found")))
                    .flatMap(row -> fetchVariantById(handle, variantId, productId, caller.getOrgId()));
        });
    }

    private static Mono<ProductVariantResponseDTO> priceValidationError(ProductVariantRequestDTO dto) {
        if ((dto.getPrice() != null) != (dto.getPriceCurrency() != null)) {
            return Mono.error(new BadRequestException("price and priceCurrency must be provided together"));
        }
        return null;
    }

    public Mono<Void> deactivateVariant(UUID productId, UUID variantId) {
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                UPDATE product_variants SET is_active = false
                WHERE id = :variantId AND product_id = :productId AND org_id = :orgId
                """)
                .bind("variantId", variantId)
                .bind("productId", productId)
                .bind("orgId", caller.getOrgId())
                .fetch()
                .rowsUpdated()
                .flatMap(rows -> rows == 0
                        ? Mono.error(new NotFoundException("Variant not found"))
                        : Mono.empty())
        ).then();
    }

    /**
     * Reprices many variants (possibly across products) in one call. All prices are stamped
     * with the org's product pricing currency - not its billing currency, and not the requesting
     * client's choice - so a listing always has an unambiguous priceCurrency to convert from.
     * (Product pricing currency is independent of billing currency: an org can be invoiced by
     * Kenoma in ARS while pricing its own catalog in USD, e.g. to match USD-denominated supply costs.)
     */
    public Mono<List<UUID>> batchUpdatePrices(VariantBatchPriceRequestDTO dto) {
        if (dto.getItems() == null || dto.getItems().isEmpty()) {
            return Mono.error(new BadRequestException("items must not be empty"));
        }
        for (VariantPriceUpdateDTO item : dto.getItems()) {
            if (item.getVariantId() == null || item.getPrice() == null) {
                return Mono.error(new BadRequestException("variantId and price are required for each item"));
            }
        }
        return ctx.withHandle((caller, handle) ->
                raumClient.getOrgCurrency(caller.getOrgId(), openBaoService.getToken())
                        .flatMap(orgCurrency -> {
                            if (orgCurrency.getProductPricingCurrency() == null
                                    || orgCurrency.getProductPricingCurrency().isBlank()) {
                                return Mono.error(new BadRequestException(
                                        "Organization has no product pricing currency configured"));
                            }
                            return runBatchPriceUpdate(handle, caller.getOrgId(),
                                    orgCurrency.getProductPricingCurrency().toUpperCase(), dto.getItems());
                        })
        );
    }

    private Mono<List<UUID>> runBatchPriceUpdate(BimeDbHandle handle, UUID orgId, String baseCurrency,
                                                  List<VariantPriceUpdateDTO> items) {
        StringBuilder valuesClause = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) valuesClause.append(", ");
            valuesClause.append("(:id").append(i).append("::uuid, :price").append(i).append("::numeric)");
        }
        String sql = """
                UPDATE product_variants AS pv
                SET price = v.price,
                    price_currency = :baseCurrency
                FROM (VALUES %s) AS v(id, price)
                WHERE pv.id = v.id AND pv.org_id = :orgId
                RETURNING pv.id
                """.formatted(valuesClause);

        DatabaseClient.GenericExecuteSpec spec = handle.client().sql(sql)
                .bind("baseCurrency", baseCurrency)
                .bind("orgId", orgId);
        for (int i = 0; i < items.size(); i++) {
            spec = spec.bind("id" + i, items.get(i).getVariantId())
                    .bind("price" + i, items.get(i).getPrice());
        }

        return spec.fetch().all()
                .map(row -> (UUID) row.get("id"))
                .collectList()
                .flatMap(updatedIds -> {
                    Set<UUID> requested = items.stream().map(VariantPriceUpdateDTO::getVariantId)
                            .collect(Collectors.toSet());
                    Set<UUID> updated = new HashSet<>(updatedIds);
                    if (!updated.containsAll(requested)) {
                        requested.removeAll(updated);
                        return Mono.error(new NotFoundException(
                                "Variants not found in this org: " + requested));
                    }
                    return Mono.just(updatedIds);
                });
    }

    // Called by ProductService to embed variants in the product detail response
    public Flux<ProductVariantResponseDTO> loadVariantsForProduct(BimeDbHandle handle, UUID productId, UUID orgId) {
        return loadVariantRows(handle, productId, orgId)
                .collectList()
                .flatMapMany(variants -> {
                    if (variants.isEmpty()) return Flux.empty();
                    return loadOptionsForVariants(handle, productId, orgId)
                            .collectList()
                            .flatMap(optionRows -> {
                                List<UUID> variantIds = variants.stream().map(ProductVariantResponseDTO::getId).toList();
                                return loadStockForVariants(handle, orgId, variantIds)
                                        .collectList()
                                        .map(stockRows -> mergeVariantData(variants, optionRows, stockRows));
                            })
                            .flatMapMany(Flux::fromIterable);
                });
    }

    private Flux<ProductVariantResponseDTO> loadVariantRows(BimeDbHandle handle, UUID productId, UUID orgId) {
        return handle.client().sql("""
                SELECT id, product_id, org_id, sku, is_active, created_at, price, price_currency
                FROM product_variants
                WHERE product_id = :productId AND org_id = :orgId
                ORDER BY created_at
                """)
                .bind("productId", productId)
                .bind("orgId", orgId)
                .fetch()
                .all()
                .map(row -> ProductVariantResponseDTO.builder()
                        .id((UUID) row.get("id"))
                        .productId((UUID) row.get("product_id"))
                        .orgId((UUID) row.get("org_id"))
                        .sku((String) row.get("sku"))
                        .isActive((Boolean) row.get("is_active"))
                        .createdAt((LocalDateTime) row.get("created_at"))
                        .price((BigDecimal) row.get("price"))
                        .priceCurrency((String) row.get("price_currency"))
                        .options(new ArrayList<>())
                        .stock(new ArrayList<>())
                        .build()
                );
    }

    private Flux<Map<String, Object>> loadOptionsForVariants(BimeDbHandle handle, UUID productId, UUID orgId) {
        return handle.client().sql("""
                SELECT pvo.variant_id, pmo.id AS option_id, pmo.metadata_id, pmo.value, pmo.created_at
                FROM product_variant_options pvo
                JOIN product_metadata_option pmo ON pmo.id = pvo.option_id
                WHERE pvo.variant_id IN (
                    SELECT id FROM product_variants WHERE product_id = :productId AND org_id = :orgId
                )
                ORDER BY pmo.value
                """)
                .bind("productId", productId)
                .bind("orgId", orgId)
                .fetch()
                .all();
    }

    private Flux<Map<String, Object>> loadStockForVariants(BimeDbHandle handle, UUID orgId, List<UUID> variantIds) {
        return handle.client().sql("""
                SELECT variant_id, location_id, quantity, modified_at
                FROM variant_stock_balances
                WHERE org_id = :orgId AND variant_id = ANY(:variantIds)
                """)
                .bind("orgId", orgId)
                .bind("variantIds", variantIds.toArray(new UUID[0]))
                .fetch()
                .all();
    }

    private List<ProductVariantResponseDTO> mergeVariantData(
            List<ProductVariantResponseDTO> variants,
            List<Map<String, Object>> optionRows,
            List<Map<String, Object>> stockRows) {

        Map<UUID, ProductVariantResponseDTO> byId = new LinkedHashMap<>();
        for (ProductVariantResponseDTO v : variants) byId.put(v.getId(), v);

        for (Map<String, Object> row : optionRows) {
            ProductVariantResponseDTO variant = byId.get((UUID) row.get("variant_id"));
            if (variant != null) {
                variant.getOptions().add(MetadataOptionResponseDTO.builder()
                        .id((UUID) row.get("option_id"))
                        .metadataId((UUID) row.get("metadata_id"))
                        .value((String) row.get("value"))
                        .createdAt((LocalDateTime) row.get("created_at"))
                        .build()
                );
            }
        }

        for (Map<String, Object> row : stockRows) {
            ProductVariantResponseDTO variant = byId.get((UUID) row.get("variant_id"));
            if (variant != null) {
                variant.getStock().add(VariantStockDTO.builder()
                        .locationId((UUID) row.get("location_id"))
                        .quantity((Integer) row.get("quantity"))
                        .modifiedAt((LocalDateTime) row.get("modified_at"))
                        .build()
                );
            }
        }

        return new ArrayList<>(byId.values());
    }

    private Mono<ProductVariantResponseDTO> fetchVariantById(BimeDbHandle handle, UUID variantId, UUID productId, UUID orgId) {
        return handle.client().sql("""
                SELECT id, product_id, org_id, sku, is_active, created_at, price, price_currency
                FROM product_variants
                WHERE id = :variantId AND product_id = :productId AND org_id = :orgId
                """)
                .bind("variantId", variantId)
                .bind("productId", productId)
                .bind("orgId", orgId)
                .fetch()
                .one()
                .switchIfEmpty(Mono.error(new NotFoundException("Variant not found")))
                .map(row -> ProductVariantResponseDTO.builder()
                        .id((UUID) row.get("id"))
                        .productId((UUID) row.get("product_id"))
                        .orgId((UUID) row.get("org_id"))
                        .sku((String) row.get("sku"))
                        .isActive((Boolean) row.get("is_active"))
                        .createdAt((LocalDateTime) row.get("created_at"))
                        .price((BigDecimal) row.get("price"))
                        .priceCurrency((String) row.get("price_currency"))
                        .options(new ArrayList<>())
                        .stock(new ArrayList<>())
                        .build()
                )
                .flatMap(variant ->
                        loadVariantOptions(handle, variantId)
                                .doOnNext(opt -> variant.getOptions().add(opt))
                                .then()
                                .then(loadVariantStock(handle, orgId, variantId)
                                        .doOnNext(s -> variant.getStock().add(s))
                                        .then()
                                )
                                .thenReturn(variant)
                );
    }

    private Flux<MetadataOptionResponseDTO> loadVariantOptions(BimeDbHandle handle, UUID variantId) {
        return handle.client().sql("""
                SELECT pmo.id, pmo.metadata_id, pmo.value, pmo.created_at
                FROM product_variant_options pvo
                JOIN product_metadata_option pmo ON pmo.id = pvo.option_id
                WHERE pvo.variant_id = :variantId
                ORDER BY pmo.value
                """)
                .bind("variantId", variantId)
                .fetch()
                .all()
                .map(row -> MetadataOptionResponseDTO.builder()
                        .id((UUID) row.get("id"))
                        .metadataId((UUID) row.get("metadata_id"))
                        .value((String) row.get("value"))
                        .createdAt((LocalDateTime) row.get("created_at"))
                        .build()
                );
    }

    private Flux<VariantStockDTO> loadVariantStock(BimeDbHandle handle, UUID orgId, UUID variantId) {
        return handle.client().sql("""
                SELECT location_id, quantity, modified_at
                FROM variant_stock_balances
                WHERE org_id = :orgId AND variant_id = :variantId
                """)
                .bind("orgId", orgId)
                .bind("variantId", variantId)
                .fetch()
                .all()
                .map(row -> VariantStockDTO.builder()
                        .locationId((UUID) row.get("location_id"))
                        .quantity((Integer) row.get("quantity"))
                        .modifiedAt((LocalDateTime) row.get("modified_at"))
                        .build()
                );
    }

    private Mono<Void> verifyProductExists(BimeDbHandle handle, UUID productId, UUID orgId) {
        return handle.client().sql("""
                SELECT 1 FROM products WHERE id = :productId AND org_id = :orgId
                """)
                .bind("productId", productId)
                .bind("orgId", orgId)
                .fetch()
                .one()
                .switchIfEmpty(Mono.error(new NotFoundException("Product not found")))
                .then();
    }

    private Mono<Palette> loadPalette(BimeDbHandle handle, UUID productId) {
        return handle.client().sql("""
                SELECT pma.metadata_id, pos.option_id
                FROM product_metadata_assignments pma
                LEFT JOIN product_option_selections pos ON pos.assignment_id = pma.id
                WHERE pma.product_id = :productId
                """)
                .bind("productId", productId)
                .fetch()
                .all()
                .collectList()
                .map(rows -> {
                    Set<UUID> assignedMetadataIds = new LinkedHashSet<>();
                    Map<UUID, UUID> optionToMetadata = new LinkedHashMap<>();
                    for (Map<String, Object> row : rows) {
                        UUID metadataId = (UUID) row.get("metadata_id");
                        UUID optionId = (UUID) row.get("option_id");
                        assignedMetadataIds.add(metadataId);
                        if (optionId != null) {
                            optionToMetadata.put(optionId, metadataId);
                        }
                    }
                    return new Palette(assignedMetadataIds, optionToMetadata);
                });
    }

    private Mono<List<UUID>> validateOptions(Palette palette, List<UUID> optionIds) {
        if (optionIds == null) optionIds = List.of();

        Set<UUID> coveredMetadataIds = new LinkedHashSet<>();
        for (UUID optionId : optionIds) {
            UUID metadataId = palette.optionToMetadata().get(optionId);
            if (metadataId == null) {
                return Mono.error(new BadRequestException(
                        "Option " + optionId + " is not available for this product"));
            }
            if (!coveredMetadataIds.add(metadataId)) {
                return Mono.error(new BadRequestException(
                        "Multiple options provided for the same metadata key"));
            }
        }

        for (UUID metadataId : palette.assignedMetadataIds()) {
            if (!coveredMetadataIds.contains(metadataId)) {
                return Mono.error(new BadRequestException(
                        "Variant is incomplete: no option provided for metadata key " + metadataId));
            }
        }

        return Mono.just(new ArrayList<>(optionIds));
    }

    private Mono<UUID> insertVariant(BimeDbHandle handle, UUID productId, UUID orgId, ProductVariantRequestDTO dto) {
        DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                INSERT INTO product_variants (product_id, org_id, sku, price, price_currency)
                VALUES (:productId, :orgId, :sku, :price, :priceCurrency)
                RETURNING id
                """)
                .bind("productId", productId)
                .bind("orgId", orgId);

        if (dto.getSku() != null) {
            spec = spec.bind("sku", dto.getSku());
        } else {
            spec = spec.bindNull("sku", String.class);
        }
        if (dto.getPrice() != null) {
            spec = spec.bind("price", dto.getPrice()).bind("priceCurrency", dto.getPriceCurrency().toUpperCase());
        } else {
            spec = spec.bindNull("price", BigDecimal.class).bindNull("priceCurrency", String.class);
        }

        return spec.fetch().one().map(row -> (UUID) row.get("id"));
    }

    private Mono<Void> insertVariantOptions(BimeDbHandle handle, UUID variantId, List<UUID> optionIds) {
        if (optionIds.isEmpty()) return Mono.empty();
        return Flux.fromIterable(optionIds)
                .concatMap(optionId -> handle.client().sql("""
                        INSERT INTO product_variant_options (variant_id, option_id)
                        VALUES (:variantId, :optionId)
                        """)
                        .bind("variantId", variantId)
                        .bind("optionId", optionId)
                        .fetch()
                        .rowsUpdated()
                )
                .then();
    }
}
