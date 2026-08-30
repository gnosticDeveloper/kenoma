package bime.services;

import bime.db.BimeContextService;
import bime.db.BimeDbHandle;
import bime.dto.BarcodeLookupResponseDTO;
import bime.dto.BarcodeSource;
import bime.dto.BarcodeSymbology;
import bime.dto.MetadataOptionResponseDTO;
import bime.dto.OrgBarcodeSettingsRequestDTO;
import bime.dto.OrgBarcodeSettingsResponseDTO;
import bime.dto.ProductVariantResponseDTO;
import bime.dto.VariantBarcodeIssueRequestDTO;
import bime.dto.VariantBarcodeRequestDTO;
import bime.dto.VariantBarcodeResponseDTO;
import bime.services.BarcodeLabelDocumentService.LabelItem;
import bime.services.BarcodeLabelDocumentService.LabelOptions;
import common.exception.BadRequestException;
import common.exception.ConflictException;
import common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BarcodeService {

    private final BimeContextService ctx;
    private final ProductVariantService productVariantService;
    private final BarcodeLabelDocumentService labelDocumentService;

    /** Shared projection: barcode columns plus its resolved unit name and quantity factor
      * (1 for the base unit, or the pack size's conversion factor, or null if that conversion was
      * later removed). Needs {@code vb} aliased to variant_barcodes. */
    private static final String BARCODE_SELECT = """
            SELECT vb.id, vb.org_id, vb.variant_id, vb.barcode, vb.symbology, vb.source, vb.is_primary, vb.created_at,
                   ou.name AS uom,
                   CASE WHEN vb.uom_id = pv.base_uom_id THEN 1 ELSE vuc.factor END AS factor
            FROM variant_barcodes vb
            JOIN product_variants pv ON pv.id = vb.variant_id
            JOIN org_units ou ON ou.id = vb.uom_id
            LEFT JOIN variant_uom_conversions vuc ON vuc.variant_id = vb.variant_id AND vuc.uom_id = vb.uom_id
            """;

    public Flux<VariantBarcodeResponseDTO> list(UUID productId, UUID variantId) {
        return ctx.withHandleMany((caller, handle) ->
                verifyVariant(handle, productId, variantId, caller.getOrgId())
                        .thenMany(selectBarcodes(handle, caller.getOrgId(), variantId)));
    }

    /** Links an existing (provider) barcode. UPC-A is validated as a 12-digit GTIN and then stored
      * as the equivalent 13-digit EAN-13 (a leading zero, same check digit), so lookups match
      * whichever way a scanner reports the same product. */
    public Mono<VariantBarcodeResponseDTO> link(UUID productId, UUID variantId, VariantBarcodeRequestDTO dto) {
        BarcodeSymbology symbology = dto.getSymbology();
        String value;
        BarcodeSymbology storedSymbology;
        try {
            String normalized = Barcodes.normalize(symbology, dto.getBarcode());
            Barcodes.validate(symbology, normalized);
            if (symbology == BarcodeSymbology.UPC_A) {
                value = "0" + normalized;
                storedSymbology = BarcodeSymbology.EAN13;
            } else {
                value = normalized;
                storedSymbology = symbology;
            }
        } catch (BadRequestException e) {
            return Mono.error(e);
        }
        boolean makePrimary = Boolean.TRUE.equals(dto.getIsPrimary());
        return ctx.withHandle((caller, handle) -> Mono.from(handle.tx().transactional(
                verifyVariant(handle, productId, variantId, caller.getOrgId())
                        .then(resolveBarcodeUomId(handle, caller.getOrgId(), variantId, dto.getUom()))
                        .flatMap(uomId -> (makePrimary ? clearPrimary(handle, caller.getOrgId(), variantId, uomId) : Mono.<Void>empty())
                                .then(insertBarcode(handle, caller.getOrgId(), variantId, value, storedSymbology,
                                        BarcodeSource.PROVIDER, makePrimary, uomId)))
        ))).onErrorMap(DataIntegrityViolationException.class, e ->
                new ConflictException("Barcode \"" + value + "\" is already linked to a variant in this organization"));
    }

    /** Issues a new internal EAN-13 from the org's barcode settings, consuming one item-reference
      * number from the running sequence. */
    public Mono<VariantBarcodeResponseDTO> issue(UUID productId, UUID variantId, VariantBarcodeIssueRequestDTO dto) {
        boolean makePrimary = dto != null && Boolean.TRUE.equals(dto.getIsPrimary());
        String rawUom = dto == null ? null : dto.getUom();
        return ctx.withHandle((caller, handle) -> Mono.from(handle.tx().transactional(
                verifyVariant(handle, productId, variantId, caller.getOrgId())
                        .then(resolveBarcodeUomId(handle, caller.getOrgId(), variantId, rawUom))
                        .flatMap(uomId -> nextIssuedValue(handle, caller.getOrgId())
                                .flatMap(value -> (makePrimary ? clearPrimary(handle, caller.getOrgId(), variantId, uomId) : Mono.<Void>empty())
                                        .then(insertBarcode(handle, caller.getOrgId(), variantId, value, BarcodeSymbology.EAN13,
                                                BarcodeSource.ISSUED, makePrimary, uomId))))
        ))).onErrorMap(DataIntegrityViolationException.class, e ->
                new ConflictException("The issued barcode collided with an existing one; try issuing again"));
    }

    /** Reads the org's settings row (creating it with defaults on first use), builds the next
      * EAN-13, and advances the sequence in the same statement. */
    private Mono<String> nextIssuedValue(BimeDbHandle handle, UUID orgId) {
        return handle.client().sql("""
                INSERT INTO org_barcode_settings (org_id, next_sequence) VALUES (:orgId, 2)
                ON CONFLICT (org_id) DO UPDATE SET next_sequence = org_barcode_settings.next_sequence + 1,
                                                  modified_at = current_timestamp
                RETURNING gs1_prefix,
                          CASE WHEN xmax = 0 THEN 1 ELSE next_sequence - 1 END AS used_sequence
                """)
                .bind("orgId", orgId)
                .fetch()
                .one()
                .map(row -> {
                    String prefix = (String) row.get("gs1_prefix");
                    long sequence = ((Number) row.get("used_sequence")).longValue();
                    String bodyPrefix = (prefix == null || prefix.isBlank()) ? Barcodes.RESTRICTED_PREFIX : prefix;
                    return Barcodes.issueEan13(bodyPrefix, sequence);
                });
    }

    /** Changes which of a variant's barcodes is primary, scoped to that barcode's unit of measure.
      * {@code primary=true} promotes it and demotes any current primary for the same unit in the same
      * transaction; {@code primary=false} just demotes it. */
    public Mono<VariantBarcodeResponseDTO> setPrimary(UUID productId, UUID variantId, String barcode, boolean primary) {
        return ctx.withHandle((caller, handle) -> Mono.from(handle.tx().transactional(
                verifyVariant(handle, productId, variantId, caller.getOrgId())
                        .then(handle.client().sql("""
                                SELECT id, uom_id FROM variant_barcodes
                                WHERE org_id = :orgId AND variant_id = :variantId AND barcode = ANY(:candidates)
                                """)
                                .bind("orgId", caller.getOrgId())
                                .bind("variantId", variantId)
                                .bind("candidates", lookupCandidates(barcode).toArray(new String[0]))
                                .fetch()
                                .one()
                                .switchIfEmpty(Mono.error(new NotFoundException("Barcode not found on this variant"))))
                        .flatMap(target -> {
                            UUID id = (UUID) target.get("id");
                            UUID uomId = (UUID) target.get("uom_id");
                            return (primary ? clearPrimary(handle, caller.getOrgId(), variantId, uomId) : Mono.<Void>empty())
                                    .then(handle.client().sql("""
                                            UPDATE variant_barcodes SET is_primary = :primary WHERE id = :id RETURNING id
                                            """)
                                            .bind("primary", primary)
                                            .bind("id", id)
                                            .fetch()
                                            .rowsUpdated())
                                    .then(loadBarcodeById(handle, caller.getOrgId(), id));
                        })
        )));
    }

    public Mono<Void> remove(UUID productId, UUID variantId, String barcode) {
        return ctx.withHandle((caller, handle) ->
                verifyVariant(handle, productId, variantId, caller.getOrgId())
                        .then(handle.client().sql("""
                                DELETE FROM variant_barcodes
                                WHERE org_id = :orgId AND variant_id = :variantId AND barcode = ANY(:candidates)
                                """)
                                .bind("orgId", caller.getOrgId())
                                .bind("variantId", variantId)
                                .bind("candidates", lookupCandidates(barcode).toArray(new String[0]))
                                .fetch()
                                .rowsUpdated())
                        .flatMap(rows -> rows == 0
                                ? Mono.error(new NotFoundException("Barcode not found on this variant"))
                                : Mono.empty())
        ).then();
    }

    /** Point-of-sale resolution: a scanned string to the variant it identifies. */
    public Mono<BarcodeLookupResponseDTO> lookup(String scanned) {
        if (scanned == null || scanned.isBlank()) {
            return Mono.error(new BadRequestException("barcode is required"));
        }
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                SELECT vb.barcode, vb.symbology, vb.variant_id, ou.name AS uom,
                       CASE WHEN vb.uom_id = pv.base_uom_id THEN 1 ELSE vuc.factor END AS factor,
                       vuc.price AS pack_price_explicit, pv.price AS variant_price,
                       p.id AS product_id, p.sku AS product_sku, p.name AS product_name
                FROM variant_barcodes vb
                JOIN product_variants pv ON pv.id = vb.variant_id
                JOIN products p ON p.id = pv.product_id
                JOIN org_units ou ON ou.id = vb.uom_id
                LEFT JOIN variant_uom_conversions vuc ON vuc.variant_id = vb.variant_id AND vuc.uom_id = vb.uom_id
                WHERE vb.org_id = :orgId AND vb.barcode = ANY(:candidates)
                LIMIT 1
                """)
                .bind("orgId", caller.getOrgId())
                .bind("candidates", lookupCandidates(scanned).toArray(new String[0]))
                .fetch()
                .one()
                .switchIfEmpty(Mono.error(new NotFoundException("No variant is linked to barcode \"" + scanned.trim() + "\"")))
                .flatMap(row -> productVariantService.loadVariantByIdForOrg(handle, (UUID) row.get("variant_id"), caller.getOrgId())
                        .map(variant -> {
                            BigDecimal factor = (BigDecimal) row.get("factor");
                            BigDecimal packPrice = UomConversionService.effectivePrice(
                                    (BigDecimal) row.get("pack_price_explicit"), factor, (BigDecimal) row.get("variant_price"));
                            return BarcodeLookupResponseDTO.builder()
                                    .barcode((String) row.get("barcode"))
                                    .symbology(BarcodeSymbology.valueOf((String) row.get("symbology")))
                                    .productId((UUID) row.get("product_id"))
                                    .productSku((String) row.get("product_sku"))
                                    .productName((String) row.get("product_name"))
                                    .uom((String) row.get("uom"))
                                    .factor(factor)
                                    .packPrice(packPrice)
                                    .variant(variant)
                                    .build();
                        }))
        );
    }

    public Mono<OrgBarcodeSettingsResponseDTO> getSettings() {
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                SELECT org_id, gs1_prefix, next_sequence, created_at, modified_at
                FROM org_barcode_settings WHERE org_id = :orgId
                """)
                .bind("orgId", caller.getOrgId())
                .fetch()
                .one()
                .map(BarcodeService::toSettingsDTO)
                .switchIfEmpty(Mono.just(OrgBarcodeSettingsResponseDTO.builder()
                        .orgId(caller.getOrgId())
                        .gs1Prefix(null)
                        .nextSequence(1L)
                        .build()))
        );
    }

    public Mono<OrgBarcodeSettingsResponseDTO> updateSettings(OrgBarcodeSettingsRequestDTO dto) {
        String prefix = dto == null || dto.getGs1Prefix() == null || dto.getGs1Prefix().isBlank()
                ? null
                : dto.getGs1Prefix().trim();
        if (prefix != null) {
            try {
                Barcodes.validateGs1Prefix(prefix);
            } catch (BadRequestException e) {
                return Mono.error(e);
            }
        }
        String finalPrefix = prefix;
        return ctx.withHandle((caller, handle) -> {
            DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                    INSERT INTO org_barcode_settings (org_id, gs1_prefix) VALUES (:orgId, :prefix)
                    ON CONFLICT (org_id) DO UPDATE SET gs1_prefix = :prefix, modified_at = current_timestamp
                    RETURNING org_id, gs1_prefix, next_sequence, created_at, modified_at
                    """)
                    .bind("orgId", caller.getOrgId());
            spec = finalPrefix != null ? spec.bind("prefix", finalPrefix) : spec.bindNull("prefix", String.class);
            return spec.fetch().one().map(BarcodeService::toSettingsDTO);
        });
    }

    /** Builds a printable PDF sheet of barcode labels for a product. By default one label per active
      * variant that has a barcode, using that variant's primary barcode; {@code which=all} emits a
      * label for every barcode instead, and {@code variantId} narrows it to a single variant.
      * There is no printer integration - the caller prints the returned PDF themselves. */
    public Mono<byte[]> generateLabels(UUID productId, String which, int columns, int copies,
                                       String pageSize, UUID variantId, String uom) {
        boolean allBarcodes = "all".equalsIgnoreCase(which);
        String uomFilter = uom == null || uom.isBlank() ? null : UomNames.normalize(uom);
        return ctx.withHandle((caller, handle) -> fetchProductName(handle, productId, caller.getOrgId())
                .flatMap(productName -> productVariantService
                        .loadVariantsForProduct(handle, productId, caller.getOrgId())
                        .collectList()
                        .flatMap(variants -> {
                            List<LabelItem> items = buildLabelItems(productName, variants, allBarcodes, variantId, uomFilter);
                            if (items.isEmpty()) {
                                return Mono.error(new BadRequestException(
                                        "This product has no barcodes to print. Link or issue a barcode first."));
                            }
                            return Mono.fromCallable(() -> labelDocumentService.generate(
                                            items, new LabelOptions(columns, copies, pageSize)))
                                    .subscribeOn(Schedulers.boundedElastic());
                        })));
    }

    private Mono<String> fetchProductName(BimeDbHandle handle, UUID productId, UUID orgId) {
        return handle.client().sql("SELECT name FROM products WHERE id = :productId AND org_id = :orgId")
                .bind("productId", productId)
                .bind("orgId", orgId)
                .fetch()
                .one()
                .map(row -> (String) row.get("name"))
                .switchIfEmpty(Mono.error(new NotFoundException("Product not found")));
    }

    private static List<LabelItem> buildLabelItems(String productName, List<ProductVariantResponseDTO> variants,
                                                   boolean allBarcodes, UUID variantId, String uomFilter) {
        List<LabelItem> items = new ArrayList<>();
        for (ProductVariantResponseDTO variant : variants) {
            if (variantId != null && !variantId.equals(variant.getId())) continue;
            if (Boolean.FALSE.equals(variant.getIsActive())) continue;
            List<VariantBarcodeResponseDTO> barcodes = variant.getBarcodes();
            if (barcodes == null || barcodes.isEmpty()) continue;
            if (uomFilter != null) {
                barcodes = barcodes.stream()
                        .filter(b -> uomFilter.equalsIgnoreCase(b.getUom()))
                        .toList();
                if (barcodes.isEmpty()) continue;
            }

            List<VariantBarcodeResponseDTO> chosen;
            if (allBarcodes) {
                chosen = barcodes;
            } else {
                VariantBarcodeResponseDTO primary = barcodes.stream()
                        .filter(b -> Boolean.TRUE.equals(b.getIsPrimary()))
                        .findFirst()
                        .orElse(barcodes.get(0));
                chosen = List.of(primary);
            }

            String optionSummary = variant.getOptions() == null ? "" : variant.getOptions().stream()
                    .map(MetadataOptionResponseDTO::getValue)
                    .collect(Collectors.joining(" / "));
            String priceLabel = variant.getPrice() != null
                    ? (variant.getPriceCurrency() == null ? "" : variant.getPriceCurrency() + " ") + variant.getPrice()
                    : "";

            for (VariantBarcodeResponseDTO b : chosen) {
                String unitLabel = packLabel(b);
                items.add(new LabelItem(productName, variant.getSku(), optionSummary,
                        b.getBarcode(), b.getSymbology().name(), priceLabel, unitLabel));
            }
        }
        return items;
    }

    /** A short "CASE x24" style caption for a barcode's unit, or just the unit name for the base
      * unit / a factor of 1, or empty when the unit is unknown. */
    private static String packLabel(VariantBarcodeResponseDTO b) {
        if (b.getUom() == null) return "";
        String unit = b.getUom().toUpperCase();
        BigDecimal factor = b.getFactor();
        if (factor == null || factor.compareTo(BigDecimal.ONE) == 0) {
            return unit;
        }
        return unit + " x" + factor.stripTrailingZeros().toPlainString();
    }

    private Mono<Void> verifyVariant(BimeDbHandle handle, UUID productId, UUID variantId, UUID orgId) {
        return handle.client().sql("""
                SELECT 1 FROM product_variants
                WHERE id = :variantId AND product_id = :productId AND org_id = :orgId
                """)
                .bind("variantId", variantId)
                .bind("productId", productId)
                .bind("orgId", orgId)
                .fetch()
                .one()
                .switchIfEmpty(Mono.error(new NotFoundException("Variant not found")))
                .then();
    }

    private Mono<Void> clearPrimary(BimeDbHandle handle, UUID orgId, UUID variantId, UUID uomId) {
        return handle.client().sql("""
                UPDATE variant_barcodes SET is_primary = false
                WHERE org_id = :orgId AND variant_id = :variantId AND uom_id = :uomId AND is_primary
                """)
                .bind("orgId", orgId)
                .bind("variantId", variantId)
                .bind("uomId", uomId)
                .fetch()
                .rowsUpdated()
                .then();
    }

    private Mono<VariantBarcodeResponseDTO> insertBarcode(BimeDbHandle handle, UUID orgId, UUID variantId, String value,
                                                          BarcodeSymbology symbology, BarcodeSource source,
                                                          boolean isPrimary, UUID uomId) {
        return handle.client().sql("""
                INSERT INTO variant_barcodes (org_id, variant_id, barcode, symbology, source, is_primary, uom_id)
                VALUES (:orgId, :variantId, :barcode, :symbology, :source, :isPrimary, :uomId)
                RETURNING id
                """)
                .bind("orgId", orgId)
                .bind("variantId", variantId)
                .bind("barcode", value)
                .bind("symbology", symbology.name())
                .bind("source", source.name())
                .bind("isPrimary", isPrimary)
                .bind("uomId", uomId)
                .fetch()
                .one()
                .flatMap(row -> loadBarcodeById(handle, orgId, (UUID) row.get("id")));
    }

    private Mono<VariantBarcodeResponseDTO> loadBarcodeById(BimeDbHandle handle, UUID orgId, UUID id) {
        return handle.client().sql(BARCODE_SELECT + " WHERE vb.id = :id AND vb.org_id = :orgId")
                .bind("id", id)
                .bind("orgId", orgId)
                .fetch()
                .one()
                .map(ProductVariantService::toBarcodeDTO);
    }

    private Flux<VariantBarcodeResponseDTO> selectBarcodes(BimeDbHandle handle, UUID orgId, UUID variantId) {
        return handle.client().sql(BARCODE_SELECT
                        + " WHERE vb.org_id = :orgId AND vb.variant_id = :variantId ORDER BY vb.is_primary DESC, vb.created_at")
                .bind("orgId", orgId)
                .bind("variantId", variantId)
                .fetch()
                .all()
                .map(ProductVariantService::toBarcodeDTO);
    }

    /** Resolves the unit a barcode should be filed under: the variant's base unit when {@code rawUom}
      * is null/blank, otherwise a unit that is either the base unit or one of the variant's configured
      * pack-size conversions. Rejects anything else. */
    private Mono<UUID> resolveBarcodeUomId(BimeDbHandle handle, UUID orgId, UUID variantId, String rawUom) {
        if (rawUom == null || rawUom.isBlank()) {
            return handle.client().sql("SELECT base_uom_id FROM product_variants WHERE id = :variantId AND org_id = :orgId")
                    .bind("variantId", variantId)
                    .bind("orgId", orgId)
                    .fetch()
                    .one()
                    .map(row -> (UUID) row.get("base_uom_id"));
        }
        String normalized = UomNames.normalize(rawUom);
        return handle.client().sql("""
                SELECT id FROM (
                    SELECT pv.base_uom_id AS id, bu.name AS name
                    FROM product_variants pv JOIN org_units bu ON bu.id = pv.base_uom_id
                    WHERE pv.id = :variantId AND pv.org_id = :orgId
                    UNION
                    SELECT vuc.uom_id AS id, cu.name AS name
                    FROM variant_uom_conversions vuc JOIN org_units cu ON cu.id = vuc.uom_id
                    WHERE vuc.variant_id = :variantId AND vuc.org_id = :orgId
                ) allowed
                WHERE allowed.name = :name
                """)
                .bind("variantId", variantId)
                .bind("orgId", orgId)
                .bind("name", normalized)
                .fetch()
                .one()
                .map(row -> (UUID) row.get("id"))
                .switchIfEmpty(Mono.error(new BadRequestException("Unit \"" + normalized
                        + "\" is not this variant's base unit or a configured pack size. Configure it via PUT /variants/{variantId}/uom-conversions first.")));
    }

    /** The stored forms a raw scan could match: the value as-is, upper-cased (CODE39/128), and -
      * when it is a bare 12-digit UPC-A - its 13-digit EAN-13 canonical form. */
    private static Set<String> lookupCandidates(String scanned) {
        String trimmed = scanned.trim();
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(trimmed);
        candidates.add(trimmed.toUpperCase());
        if (trimmed.length() == 12 && trimmed.chars().allMatch(Character::isDigit)) {
            candidates.add("0" + trimmed);
        }
        return candidates;
    }

    private static OrgBarcodeSettingsResponseDTO toSettingsDTO(Map<String, Object> row) {
        return OrgBarcodeSettingsResponseDTO.builder()
                .orgId((UUID) row.get("org_id"))
                .gs1Prefix((String) row.get("gs1_prefix"))
                .nextSequence(((Number) row.get("next_sequence")).longValue())
                .createdAt((LocalDateTime) row.get("created_at"))
                .modifiedAt((LocalDateTime) row.get("modified_at"))
                .build();
    }
}
