package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Result of resolving a scanned barcode to the variant it identifies, for point-of-sale lookup")
public class BarcodeLookupResponseDTO {
    @Schema(description = "The barcode that was looked up, normalized")
    private String barcode;
    private BarcodeSymbology symbology;
    private UUID productId;
    private String productSku;
    private String productName;
    @Schema(description = "The unit of measure this barcode identifies - the variant's base unit, or a pack size (e.g. \"case\")")
    private String uom;
    @Schema(description = "How many base units this scan represents: 1 for the base unit, or the pack size's factor (e.g. 24 for a case). " +
            "The register should multiply the sold quantity by this. Null if the pack size's conversion was removed after linking")
    private BigDecimal factor;
    @Schema(description = "Price for one scan of this barcode: the variant's unit price for a base-unit barcode, or the pack " +
            "size's price (its explicit price if set, otherwise factor * unit price). Null if the variant has no price")
    private BigDecimal packPrice;
    @Schema(description = "The matched variant, including its SKU, price, defining options and per-location stock. Check variant.isActive - a retired variant still resolves")
    private ProductVariantResponseDTO variant;
}
