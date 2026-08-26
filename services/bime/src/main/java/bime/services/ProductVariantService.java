package bime.services;

import bime.clients.RaumClient;
import bime.db.BimeContextService;
import bime.db.BimeDbHandle;
import bime.db.SkuSearchSql;
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
import common.exception.ConflictException;
import common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
                                checkDuplicateOptionCombination(handle, productId, caller.getOrgId(), validatedOptionIds)
                                        .then(generateVariantSku(handle, productId, caller.getOrgId(), validatedOptionIds))
                                        .flatMap(sku -> insertVariant(handle, productId, caller.getOrgId(), dto, sku))
                                        .flatMap(variantId ->
                                                insertVariantOptions(handle, variantId, validatedOptionIds)
                                                        .thenReturn(variantId))
                        )
                        .flatMap(variantId -> fetchVariantById(handle, variantId, productId, caller.getOrgId()))
        ))).onErrorMap(DataIntegrityViolationException.class, e ->
                new ConflictException("A variant with the generated SKU already exists"));
    }

    public Flux<ProductVariantResponseDTO> getVariantsForProduct(UUID productId, String targetCurrency, List<UUID> optionIds, boolean matchAll, String sku) {
        return ctx.withHandleMany((caller, handle) ->
                verifyProductExists(handle, productId, caller.getOrgId())
                        .thenMany(loadVariantsForProduct(handle, productId, caller.getOrgId(), optionIds, matchAll, sku))
                        .collectList()
                        .flatMapMany(variants -> applyCurrencyConversion(variants, targetCurrency))
        );
    }

    /** Finds variants across every product in the org matching the given option values (at least one by
      * default, or all when matchAll) and/or SKU search tokens (every token must appear in the SKU,
      * any order). At least one of optionIds/sku must be given. */
    public Flux<ProductVariantResponseDTO> searchVariantsByOptions(List<UUID> optionIds, String targetCurrency, boolean matchAll, String sku) {
        if ((optionIds == null || optionIds.isEmpty()) && (sku == null || sku.isBlank())) {
            return Flux.error(new BadRequestException("optionIds or sku must be provided"));
        }
        return ctx.withHandleMany((caller, handle) ->
                loadVariantsByOptions(handle, caller.getOrgId(), optionIds, matchAll, sku)
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
        // Currencies already matching the target need no rate lookup — besides being wasted
        // work, a lookup for X->X isn't guaranteed to have a stored identity rate.
        Set<String> sourceCurrencies = variants.stream()
                .map(ProductVariantResponseDTO::getPriceCurrency)
                .filter(Objects::nonNull)
                .filter(currency -> !currency.equalsIgnoreCase(target))
                .collect(Collectors.toSet());
        if (sourceCurrencies.isEmpty()) {
            return Flux.fromIterable(variants)
                    .map(variant -> {
                        if (variant.getPriceCurrency() != null) {
                            variant.setPriceCurrency(target);
                        }
                        return variant;
                    });
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
                            if (variant.getPriceCurrency().equalsIgnoreCase(target)) {
                                variant.setPriceCurrency(target);
                                return variant;
                            }
                            BigDecimal rate = rates.get(variant.getPriceCurrency());
                            if (rate == null) {
                                throw new NotFoundException(
                                        "No exchange rate available from " + variant.getPriceCurrency() + " to " + target);
                            }
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
                    SET is_active      = COALESCE(:isActive, is_active),
                        price          = COALESCE(:price, price),
                        price_currency = COALESCE(:priceCurrency, price_currency)
                    WHERE id = :variantId AND product_id = :productId AND org_id = :orgId
                    RETURNING id
                    """);
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
        if (dto.getPrice() != null && dto.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return Mono.error(new BadRequestException("price must be a positive number"));
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
                        // A deactivated variant shouldn't keep tripping (or holding) stock alerts —
                        // without this, the scheduler happily keeps emailing about inventory that no
                        // longer exists as far as the catalog is concerned.
                        : clearStockAlertsForVariant(handle, caller.getOrgId(), variantId))
        ).then();
    }

    private Mono<Void> clearStockAlertsForVariant(BimeDbHandle handle, UUID orgId, UUID variantId) {
        return handle.client().sql("""
                DELETE FROM variant_stock_alerts WHERE org_id = :orgId AND variant_id = :variantId
                """)
                .bind("orgId", orgId)
                .bind("variantId", variantId)
                .fetch()
                .rowsUpdated()
                .then(handle.client().sql("""
                        DELETE FROM variant_stock_alert_thresholds WHERE org_id = :orgId AND variant_id = :variantId
                        """)
                        .bind("orgId", orgId)
                        .bind("variantId", variantId)
                        .fetch()
                        .rowsUpdated())
                .then();
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
            if (item.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
                return Mono.error(new BadRequestException("price must be a positive number for every item"));
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
        return loadVariantsForProduct(handle, productId, orgId, null, false, null);
    }

    public Flux<ProductVariantResponseDTO> loadVariantsForProduct(BimeDbHandle handle, UUID productId, UUID orgId, List<UUID> optionIds, boolean matchAll, String sku) {
        return loadVariantRows(handle, productId, orgId, optionIds, matchAll, sku)
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

    private Flux<ProductVariantResponseDTO> loadVariantsByOptions(BimeDbHandle handle, UUID orgId, List<UUID> optionIds, boolean matchAll, String sku) {
        boolean hasOptionFilter = optionIds != null && !optionIds.isEmpty();
        DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                SELECT pv.id, pv.product_id, pv.org_id, pv.sku, pv.is_active, pv.created_at, pv.price, pv.price_currency
                FROM product_variants pv
                WHERE pv.org_id = :orgId
                  AND (:hasOptionFilter = false OR pv.id IN (
                      SELECT variant_id FROM product_variant_options
                      WHERE option_id = ANY(:optionIds)
                      GROUP BY variant_id
                      HAVING COUNT(DISTINCT option_id) >= CASE WHEN :matchAll THEN cardinality(:optionIds) ELSE 1 END
                  ))
                  AND %s
                ORDER BY pv.created_at
                """.formatted(SkuSearchSql.fragment("pv.sku")))
                .bind("orgId", orgId)
                .bind("hasOptionFilter", hasOptionFilter)
                .bind("matchAll", matchAll)
                .bind("optionIds", (hasOptionFilter ? optionIds : List.<UUID>of()).toArray(new UUID[0]));
        spec = SkuSearchSql.bind(spec, sku);
        return spec.fetch()
                .all()
                .map(this::toVariantResponseDTO)
                .collectList()
                .flatMapMany(variants -> {
                    if (variants.isEmpty()) return Flux.empty();
                    List<UUID> variantIds = variants.stream().map(ProductVariantResponseDTO::getId).toList();
                    return loadOptionsForVariantIds(handle, variantIds)
                            .collectList()
                            .flatMap(optionRows -> loadStockForVariants(handle, orgId, variantIds)
                                    .collectList()
                                    .map(stockRows -> mergeVariantData(variants, optionRows, stockRows)))
                            .flatMapMany(Flux::fromIterable);
                });
    }

    private Mono<String> fetchProductSku(BimeDbHandle handle, UUID productId, UUID orgId) {
        return handle.client().sql("""
                SELECT sku FROM products WHERE id = :productId AND org_id = :orgId
                """)
                .bind("productId", productId)
                .bind("orgId", orgId)
                .fetch()
                .one()
                .map(row -> (String) row.get("sku"));
    }

    private Flux<ProductVariantResponseDTO> loadVariantRows(BimeDbHandle handle, UUID productId, UUID orgId, List<UUID> optionIds, boolean matchAll, String sku) {
        boolean hasFilter = optionIds != null && !optionIds.isEmpty();
        DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                SELECT id, product_id, org_id, sku, is_active, created_at, price, price_currency
                FROM product_variants
                WHERE product_id = :productId AND org_id = :orgId
                  AND (:hasFilter = false OR id IN (
                      SELECT variant_id FROM product_variant_options
                      WHERE option_id = ANY(:optionIds)
                      GROUP BY variant_id
                      HAVING COUNT(DISTINCT option_id) >= CASE WHEN :matchAll THEN cardinality(:optionIds) ELSE 1 END
                  ))
                  AND %s
                ORDER BY created_at
                """.formatted(SkuSearchSql.fragment("sku")))
                .bind("productId", productId)
                .bind("orgId", orgId)
                .bind("hasFilter", hasFilter)
                .bind("matchAll", matchAll)
                .bind("optionIds", (hasFilter ? optionIds : List.<UUID>of()).toArray(new UUID[0]));
        spec = SkuSearchSql.bind(spec, sku);
        return spec.fetch()
                .all()
                .map(this::toVariantResponseDTO);
    }

    private ProductVariantResponseDTO toVariantResponseDTO(Map<String, Object> row) {
        return ProductVariantResponseDTO.builder()
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
                .build();
    }

    private Flux<Map<String, Object>> loadOptionsForVariants(BimeDbHandle handle, UUID productId, UUID orgId) {
        return handle.client().sql("""
                SELECT pvo.variant_id, pmo.id AS option_id, pmo.metadata_id, pmo.value, pmo.code, pmo.created_at
                FROM product_variant_options pvo
                JOIN product_metadata_option pmo ON pmo.id = pvo.option_id
                JOIN product_metadata pm ON pm.id = pmo.metadata_id
                WHERE pvo.variant_id IN (
                    SELECT id FROM product_variants WHERE product_id = :productId AND org_id = :orgId
                )
                ORDER BY pm.name, pmo.code
                """)
                .bind("productId", productId)
                .bind("orgId", orgId)
                .fetch()
                .all();
    }

    private Flux<Map<String, Object>> loadOptionsForVariantIds(BimeDbHandle handle, List<UUID> variantIds) {
        return handle.client().sql("""
                SELECT pvo.variant_id, pmo.id AS option_id, pmo.metadata_id, pmo.value, pmo.code, pmo.created_at
                FROM product_variant_options pvo
                JOIN product_metadata_option pmo ON pmo.id = pvo.option_id
                JOIN product_metadata pm ON pm.id = pmo.metadata_id
                WHERE pvo.variant_id = ANY(:variantIds)
                ORDER BY pm.name, pmo.code
                """)
                .bind("variantIds", variantIds.toArray(new UUID[0]))
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
                        .code((String) row.get("code"))
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
                .map(this::toVariantResponseDTO)
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
                SELECT pmo.id, pmo.metadata_id, pmo.value, pmo.code, pmo.created_at
                FROM product_variant_options pvo
                JOIN product_metadata_option pmo ON pmo.id = pvo.option_id
                JOIN product_metadata pm ON pm.id = pmo.metadata_id
                WHERE pvo.variant_id = :variantId
                ORDER BY pm.name, pmo.code
                """)
                .bind("variantId", variantId)
                .fetch()
                .all()
                .map(row -> MetadataOptionResponseDTO.builder()
                        .id((UUID) row.get("id"))
                        .metadataId((UUID) row.get("metadata_id"))
                        .value((String) row.get("value"))
                        .code((String) row.get("code"))
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

    // Two variants of the same product with the exact same option set are almost certainly a
    // mistake (e.g. a double-submit), not a deliberate second SKU for identical Color/Size. Scoped
    // to active variants only, so recreating a combination whose earlier variant was deactivated
    // is still allowed.
    private Mono<Void> checkDuplicateOptionCombination(BimeDbHandle handle, UUID productId, UUID orgId,
                                                         List<UUID> optionIds) {
        Set<UUID> newCombo = new HashSet<>(optionIds);
        BadRequestException duplicateError =
                new BadRequestException("A variant with this exact option combination already exists");
        if (newCombo.isEmpty()) {
            return handle.client().sql("""
                    SELECT pv.id FROM product_variants pv
                    WHERE pv.product_id = :productId AND pv.org_id = :orgId AND pv.is_active = true
                      AND NOT EXISTS (SELECT 1 FROM product_variant_options pvo WHERE pvo.variant_id = pv.id)
                    """)
                    .bind("productId", productId)
                    .bind("orgId", orgId)
                    .fetch()
                    .first()
                    .flatMap(row -> Mono.<Void>error(duplicateError))
                    .switchIfEmpty(Mono.empty());
        }
        return handle.client().sql("""
                SELECT pvo.variant_id, pvo.option_id
                FROM product_variant_options pvo
                JOIN product_variants pv ON pv.id = pvo.variant_id
                WHERE pv.product_id = :productId AND pv.org_id = :orgId AND pv.is_active = true
                """)
                .bind("productId", productId)
                .bind("orgId", orgId)
                .fetch()
                .all()
                .collectMultimap(row -> (UUID) row.get("variant_id"), row -> (UUID) row.get("option_id"))
                .flatMap(byVariant -> {
                    boolean duplicate = byVariant.values().stream()
                            .anyMatch(options -> new HashSet<>(options).equals(newCombo));
                    return duplicate ? Mono.error(duplicateError) : Mono.empty();
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

    private Mono<String> generateVariantSku(BimeDbHandle handle, UUID productId, UUID orgId, List<UUID> optionIds) {
        Mono<String> productSku = fetchProductSku(handle, productId, orgId);
        if (optionIds.isEmpty()) {
            return productSku;
        }
        Mono<List<String>> codes = handle.client().sql("""
                SELECT pmo.code
                FROM product_metadata_option pmo
                JOIN product_metadata pm ON pm.id = pmo.metadata_id
                WHERE pmo.id = ANY(:optionIds)
                ORDER BY pm.name, pmo.code
                """)
                .bind("optionIds", optionIds.toArray(new UUID[0]))
                .fetch()
                .all()
                .map(row -> (String) row.get("code"))
                .collectList();
        return Mono.zip(productSku, codes)
                .map(tuple -> tuple.getT1() + "-" + String.join("-", tuple.getT2()));
    }

    private Mono<UUID> insertVariant(BimeDbHandle handle, UUID productId, UUID orgId, ProductVariantRequestDTO dto, String sku) {
        DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                INSERT INTO product_variants (product_id, org_id, sku, price, price_currency)
                VALUES (:productId, :orgId, :sku, :price, :priceCurrency)
                RETURNING id
                """)
                .bind("productId", productId)
                .bind("orgId", orgId)
                .bind("sku", sku);

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
