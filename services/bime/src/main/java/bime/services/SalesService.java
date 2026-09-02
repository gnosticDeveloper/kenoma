package bime.services;

import bime.db.BimeContextService;
import bime.db.BimeDbHandle;
import bime.dto.MovementStatus;
import bime.dto.MovementType;
import bime.dto.SaleLineRequestDTO;
import bime.dto.SaleLineResponseDTO;
import bime.dto.SaleRequestDTO;
import bime.dto.SaleResponseDTO;
import bime.dto.SaleStatus;
import common.db.WhereClause;
import common.exception.BadRequestException;
import common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Point-of-sale sales. {@link #create} rings up one or more scanned items at a single location:
 * it resolves each line to a variant, unit and price, records the priced document
 * ({@code sales} / {@code sale_lines}), and depletes stock through {@link StockLedgerService}
 * with {@link MovementType#SALE} movements - one per variant, or one per batch when the variant
 * is batch-tracked and consumed first-expired-first-out. Recalled batches are skipped by
 * {@link StockLedgerService#allocateFefo} and so are never auto-sold.
 */
@Service
@RequiredArgsConstructor
public class SalesService {

    private final BimeContextService ctx;
    private final StockLedgerService stockLedgerService;

    private static final String SALE_COLUMNS = """
            id, org_id, location_id, reference, status, subtotal, currency, note,
            sold_at, sold_by, voided_at, voided_by
            """;
    private static final String LINE_COLUMNS = """
            id, variant_id, barcode, qty_base, uom, uom_quantity, unit_price, line_total
            """;

    public Mono<SaleResponseDTO> create(SaleRequestDTO dto) {
        if (dto.getLocationId() == null) {
            return Mono.error(new BadRequestException("locationId is required"));
        }
        List<SaleLineRequestDTO> lines = dto.getLines();
        if (lines == null || lines.isEmpty()) {
            return Mono.error(new BadRequestException("a sale needs at least one line"));
        }
        for (SaleLineRequestDTO line : lines) {
            boolean hasBarcode = line.getBarcode() != null && !line.getBarcode().isBlank();
            if (!hasBarcode && line.getVariantId() == null) {
                return Mono.error(new BadRequestException("each line needs a barcode or a variantId"));
            }
            if (line.getQuantity() == null || line.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                return Mono.error(new BadRequestException("each line quantity must be a positive number"));
            }
            if (line.getUnitPrice() != null && line.getUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
                return Mono.error(new BadRequestException("unitPrice cannot be negative"));
            }
        }
        return ctx.withHandle((caller, handle) -> Mono.from(handle.tx().transactional(
                requireLocation(handle, caller.getOrgId(), dto.getLocationId())
                        .thenMany(Flux.fromIterable(lines))
                        .concatMap(line -> resolveLine(handle, caller.getOrgId(), line))
                        .collectList()
                        .flatMap(resolved -> {
                            BigDecimal subtotal = resolved.stream()
                                    .map(ResolvedLine::lineTotal)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                            String currency = resolved.stream()
                                    .map(ResolvedLine::currency)
                                    .filter(Objects::nonNull)
                                    .findFirst()
                                    .orElse(null);
                            return insertSale(handle, caller.getOrgId(), caller.getId(), dto, subtotal, currency)
                                    .flatMap(saleId -> Flux.fromIterable(resolved)
                                            .concatMap(r -> insertLine(handle, caller.getOrgId(), saleId, r)
                                                    .then(depleteStock(handle, caller.getOrgId(), caller.getId(),
                                                            saleId, dto.getLocationId(), r)))
                                            .then(loadSale(handle, caller.getOrgId(), saleId)));
                        })
        )));
    }

    public Mono<SaleResponseDTO> getById(UUID id) {
        return ctx.withHandle((caller, handle) -> loadSale(handle, caller.getOrgId(), id));
    }

    public Flux<SaleResponseDTO> list(UUID locationId, LocalDate from, LocalDate to) {
        return ctx.withHandleMany((caller, handle) -> {
            WhereClause where = WhereClause.of()
                    .eq("org_id", "orgId", caller.getOrgId())
                    .eqIfPresent("location_id", "locationId", locationId);
            if (from != null) {
                where.raw("sold_at >= :from");
            }
            if (to != null) {
                where.raw("sold_at < :to");
            }
            DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                    SELECT %s FROM sales
                    %s
                    ORDER BY sold_at DESC
                    """.formatted(SALE_COLUMNS.strip(), where.toSql()));
            for (WhereClause.Binding b : where.bindings()) {
                spec = spec.bind(b.name(), b.value());
            }
            if (from != null) {
                spec = spec.bind("from", from.atStartOfDay());
            }
            if (to != null) {
                spec = spec.bind("to", to.plusDays(1).atStartOfDay());
            }
            DatabaseClient.GenericExecuteSpec finalSpec = spec;
            return finalSpec.fetch().all()
                    .concatMap(row -> loadLines(handle, caller.getOrgId(), (UUID) row.get("id"))
                            .map(saleLines -> toSaleDTO(row, saleLines)));
        });
    }

    private record ResolvedLine(UUID variantId, String barcode, String uom, BigDecimal baseQty,
                                BigDecimal uomQuantity, BigDecimal unitPrice, BigDecimal lineTotal,
                                String currency) {}

    private Mono<ResolvedLine> resolveLine(BimeDbHandle handle, UUID orgId, SaleLineRequestDTO line) {
        boolean hasBarcode = line.getBarcode() != null && !line.getBarcode().isBlank();
        return hasBarcode
                ? resolveByBarcode(handle, orgId, line)
                : resolveByVariant(handle, orgId, line);
    }

    private Mono<ResolvedLine> resolveByBarcode(BimeDbHandle handle, UUID orgId, SaleLineRequestDTO line) {
        String scanned = line.getBarcode().trim();
        return handle.client().sql("""
                SELECT vb.barcode, vb.variant_id, ou.name AS uom,
                       CASE WHEN vb.uom_id = pv.base_uom_id THEN 1 ELSE vuc.factor END AS factor,
                       vuc.price AS pack_price, pv.price AS variant_price, pv.price_currency AS currency
                FROM variant_barcodes vb
                JOIN product_variants pv ON pv.id = vb.variant_id
                JOIN org_units ou ON ou.id = vb.uom_id
                LEFT JOIN variant_uom_conversions vuc ON vuc.variant_id = vb.variant_id AND vuc.uom_id = vb.uom_id
                WHERE vb.org_id = :orgId AND vb.barcode = ANY(:candidates)
                LIMIT 1
                """)
                .bind("orgId", orgId)
                .bind("candidates", barcodeCandidates(scanned))
                .fetch()
                .one()
                .switchIfEmpty(Mono.error(new NotFoundException("No variant is linked to barcode \"" + scanned + "\"")))
                .flatMap(row -> {
                    BigDecimal factor = (BigDecimal) row.get("factor");
                    if (factor == null) {
                        return Mono.error(new BadRequestException(
                                "the pack size for barcode \"" + scanned + "\" is no longer configured; sell this item by unit instead"));
                    }
                    boolean isPack = factor.compareTo(BigDecimal.ONE) != 0;
                    BigDecimal variantPrice = (BigDecimal) row.get("variant_price");
                    BigDecimal unitPrice = line.getUnitPrice() != null
                            ? line.getUnitPrice()
                            : UomConversionService.effectivePrice((BigDecimal) row.get("pack_price"), factor, variantPrice);
                    return buildLine(line, (UUID) row.get("variant_id"), (String) row.get("barcode"),
                            isPack ? (String) row.get("uom") : null, factor, unitPrice, (String) row.get("currency"));
                });
    }

    private Mono<ResolvedLine> resolveByVariant(BimeDbHandle handle, UUID orgId, SaleLineRequestDTO line) {
        String uomName = line.getUom() == null || line.getUom().isBlank() ? null : UomNames.normalize(line.getUom());
        return handle.client().sql("""
                SELECT pv.price AS variant_price, pv.price_currency AS currency, bu.name AS base_uom_name,
                       vuc.factor AS conv_factor, vuc.price AS conv_price
                FROM product_variants pv
                JOIN org_units bu ON bu.id = pv.base_uom_id
                LEFT JOIN org_units ou ON ou.org_id = pv.org_id AND ou.name = :uomName
                LEFT JOIN variant_uom_conversions vuc ON vuc.variant_id = pv.id AND vuc.uom_id = ou.id
                WHERE pv.id = :variantId AND pv.org_id = :orgId
                """)
                .bind("variantId", line.getVariantId())
                .bind("orgId", orgId)
                .bind("uomName", uomName == null ? "" : uomName)
                .fetch()
                .one()
                .switchIfEmpty(Mono.error(new NotFoundException("Variant not found")))
                .flatMap(row -> {
                    BigDecimal variantPrice = (BigDecimal) row.get("variant_price");
                    String currency = (String) row.get("currency");
                    if (uomName == null || uomName.equals(row.get("base_uom_name"))) {
                        BigDecimal unitPrice = line.getUnitPrice() != null ? line.getUnitPrice() : variantPrice;
                        return buildLine(line, line.getVariantId(), null, null, BigDecimal.ONE, unitPrice, currency);
                    }
                    BigDecimal factor = (BigDecimal) row.get("conv_factor");
                    if (factor == null) {
                        return Mono.error(new BadRequestException("Unit \"" + uomName
                                + "\" is not this variant's base unit or a configured pack size"));
                    }
                    BigDecimal unitPrice = line.getUnitPrice() != null
                            ? line.getUnitPrice()
                            : UomConversionService.effectivePrice((BigDecimal) row.get("conv_price"), factor, variantPrice);
                    return buildLine(line, line.getVariantId(), null, uomName, factor, unitPrice, currency);
                });
    }

    /** Common tail of both resolution paths: normalize the quantity to base units, total the line,
      * and fail when there is no price to charge. */
    private Mono<ResolvedLine> buildLine(SaleLineRequestDTO line, UUID variantId, String barcode, String uom,
                                         BigDecimal factor, BigDecimal unitPrice, String currency) {
        BigDecimal qty = line.getQuantity();
        BigDecimal baseQty = qty.multiply(factor).setScale(3, RoundingMode.HALF_UP);
        if (baseQty.signum() <= 0) {
            return Mono.error(new BadRequestException(
                    "a line quantity is too small to record - it rounds to zero in the variant's base unit"));
        }
        if (unitPrice == null) {
            String ident = barcode != null ? "barcode \"" + barcode + "\"" : "variant " + variantId;
            return Mono.error(new BadRequestException(
                    "no price on file for " + ident + "; pass unitPrice on the line"));
        }
        BigDecimal lineTotal = unitPrice.multiply(qty).setScale(2, RoundingMode.HALF_UP);
        BigDecimal uomQuantity = uom != null ? qty : null;
        return Mono.just(new ResolvedLine(variantId, barcode, uom, baseQty, uomQuantity,
                unitPrice.setScale(2, RoundingMode.HALF_UP), lineTotal, currency));
    }

    private Mono<Void> requireLocation(BimeDbHandle handle, UUID orgId, UUID locationId) {
        return handle.client().sql("SELECT 1 FROM locations WHERE id = :id AND org_id = :orgId")
                .bind("id", locationId)
                .bind("orgId", orgId)
                .fetch()
                .one()
                .switchIfEmpty(Mono.error(new NotFoundException("Location not found")))
                .then();
    }

    private Mono<UUID> insertSale(BimeDbHandle handle, UUID orgId, UUID userId, SaleRequestDTO dto,
                                  BigDecimal subtotal, String currency) {
        DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                INSERT INTO sales (org_id, location_id, reference, status, subtotal, currency, note, sold_by)
                VALUES (:orgId, :locationId, :reference, 'COMPLETED', :subtotal, :currency, :note, :soldBy)
                RETURNING id
                """)
                .bind("orgId", orgId)
                .bind("locationId", dto.getLocationId())
                .bind("subtotal", subtotal)
                .bind("soldBy", userId);
        spec = bindNullableString(spec, "reference", nullable(dto.getReference()));
        spec = bindNullableString(spec, "note", nullable(dto.getNote()));
        spec = bindNullableString(spec, "currency", currency);
        return spec.fetch().one().map(row -> (UUID) row.get("id"));
    }

    private Mono<Void> insertLine(BimeDbHandle handle, UUID orgId, UUID saleId, ResolvedLine r) {
        DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                INSERT INTO sale_lines
                    (sale_id, org_id, variant_id, barcode, qty_base, uom, uom_quantity, unit_price, line_total)
                VALUES (:saleId, :orgId, :variantId, :barcode, :qtyBase, :uom, :uomQuantity, :unitPrice, :lineTotal)
                """)
                .bind("saleId", saleId)
                .bind("orgId", orgId)
                .bind("variantId", r.variantId())
                .bind("qtyBase", r.baseQty())
                .bind("unitPrice", r.unitPrice())
                .bind("lineTotal", r.lineTotal());
        spec = bindNullableString(spec, "barcode", r.barcode());
        if (r.uom() != null) {
            spec = spec.bind("uom", r.uom()).bind("uomQuantity", r.uomQuantity());
        } else {
            spec = spec.bindNull("uom", String.class).bindNull("uomQuantity", BigDecimal.class);
        }
        return spec.fetch().rowsUpdated().then();
    }

    /** Deplete {@code r.baseQty} from stock at {@code locationId} as SALE movements. A batch-tracked
      * variant is FEFO-allocated across its active batches (one movement + batch-balance hit per
      * batch), mirroring transfer-order dispatch; anything else is a single movement. */
    private Mono<Void> depleteStock(BimeDbHandle handle, UUID orgId, UUID userId, UUID saleId,
                                    UUID locationId, ResolvedLine r) {
        return isBatchTracked(handle, orgId, r.variantId()).flatMap(tracked -> {
            if (!tracked) {
                return stockLedgerService.appendMovement(handle, orgId, userId, r.variantId(), locationId,
                                MovementType.SALE, MovementStatus.POSTED, r.baseQty().negate(),
                                r.uom(), r.uomQuantity(), saleId, "", null)
                        .then();
            }
            return stockLedgerService.allocateFefo(handle, orgId, r.variantId(), locationId, r.baseQty())
                    .flatMapMany(Flux::fromIterable)
                    .concatMap(entry -> {
                        UUID batchId = (UUID) entry[0];
                        BigDecimal take = (BigDecimal) entry[1];
                        return stockLedgerService.appendMovement(handle, orgId, userId, r.variantId(), locationId,
                                        MovementType.SALE, MovementStatus.POSTED, take.negate(), null, null, saleId, "", batchId)
                                .then(stockLedgerService.upsertBatchBalance(handle, orgId, batchId, r.variantId(),
                                        locationId, take.negate()));
                    })
                    .then();
        });
    }

    private Mono<Boolean> isBatchTracked(BimeDbHandle handle, UUID orgId, UUID variantId) {
        return handle.client().sql("""
                SELECT p.tracks_batches
                FROM product_variants pv
                JOIN products p ON p.id = pv.product_id
                WHERE pv.id = :variantId AND pv.org_id = :orgId
                """)
                .bind("variantId", variantId)
                .bind("orgId", orgId)
                .fetch()
                .one()
                .switchIfEmpty(Mono.error(new NotFoundException("Variant not found")))
                .map(row -> Boolean.TRUE.equals(row.get("tracks_batches")));
    }

    private Mono<SaleResponseDTO> loadSale(BimeDbHandle handle, UUID orgId, UUID id) {
        return handle.client().sql("SELECT %s FROM sales WHERE id = :id AND org_id = :orgId".formatted(SALE_COLUMNS))
                .bind("id", id)
                .bind("orgId", orgId)
                .fetch()
                .one()
                .switchIfEmpty(Mono.error(new NotFoundException("Sale not found")))
                .flatMap(row -> loadLines(handle, orgId, id).map(lines -> toSaleDTO(row, lines)));
    }

    private Mono<List<SaleLineResponseDTO>> loadLines(BimeDbHandle handle, UUID orgId, UUID saleId) {
        return handle.client().sql("""
                SELECT %s FROM sale_lines
                WHERE sale_id = :saleId AND org_id = :orgId
                ORDER BY created_at, id
                """.formatted(LINE_COLUMNS))
                .bind("saleId", saleId)
                .bind("orgId", orgId)
                .fetch()
                .all()
                .map(SalesService::toLineDTO)
                .collectList();
    }

    private static SaleResponseDTO toSaleDTO(Map<String, Object> row, List<SaleLineResponseDTO> lines) {
        return SaleResponseDTO.builder()
                .id((UUID) row.get("id"))
                .orgId((UUID) row.get("org_id"))
                .locationId((UUID) row.get("location_id"))
                .reference((String) row.get("reference"))
                .status(SaleStatus.valueOf((String) row.get("status")))
                .subtotal((BigDecimal) row.get("subtotal"))
                .currency((String) row.get("currency"))
                .note((String) row.get("note"))
                .lines(lines)
                .soldAt((LocalDateTime) row.get("sold_at"))
                .soldBy((UUID) row.get("sold_by"))
                .voidedAt((LocalDateTime) row.get("voided_at"))
                .voidedBy((UUID) row.get("voided_by"))
                .build();
    }

    private static SaleLineResponseDTO toLineDTO(Map<String, Object> row) {
        return SaleLineResponseDTO.builder()
                .id((UUID) row.get("id"))
                .variantId((UUID) row.get("variant_id"))
                .barcode((String) row.get("barcode"))
                .qtyBase((BigDecimal) row.get("qty_base"))
                .uom((String) row.get("uom"))
                .uomQuantity((BigDecimal) row.get("uom_quantity"))
                .unitPrice((BigDecimal) row.get("unit_price"))
                .lineTotal((BigDecimal) row.get("line_total"))
                .build();
    }

    /** The stored forms a raw scan could match: the value as-is, upper-cased (CODE39/128), a bare
      * 12-digit UPC-A as its 13-digit EAN-13, and - for a GS1 element string - the GTIN it carries,
      * trimmed to 13/12/8 digits. Mirrors BarcodeService's point-of-sale matching. */
    private static String[] barcodeCandidates(String scanned) {
        Gs1Parser.Gs1Scan gs1 = Gs1Parser.parse(scanned);
        if (gs1 != null && gs1.gtin() != null) {
            String gtin = gs1.gtin();
            LinkedHashSet<String> out = new LinkedHashSet<>();
            out.add(gtin);
            for (int len : new int[]{13, 12, 8}) {
                if (gtin.length() >= len) {
                    out.add(gtin.substring(gtin.length() - len));
                }
            }
            String trimmed = gtin.replaceFirst("^0+", "");
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
            return out.toArray(new String[0]);
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(scanned);
        out.add(scanned.toUpperCase());
        if (scanned.length() == 12 && scanned.chars().allMatch(Character::isDigit)) {
            out.add("0" + scanned);
        }
        return out.toArray(new String[0]);
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static DatabaseClient.GenericExecuteSpec bindNullableString(
            DatabaseClient.GenericExecuteSpec spec, String name, String value) {
        return value != null ? spec.bind(name, value) : spec.bindNull(name, String.class);
    }
}
