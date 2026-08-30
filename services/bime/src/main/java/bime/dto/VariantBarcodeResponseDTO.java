package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "A barcode linked to a product variant")
public class VariantBarcodeResponseDTO {
    private UUID id;
    private UUID orgId;
    private UUID variantId;
    private String barcode;
    private BarcodeSymbology symbology;
    @Schema(description = "PROVIDER if scanned off manufacturer goods, ISSUED if minted by this system")
    private BarcodeSource source;
    @Schema(description = "The unit of measure this barcode identifies (the variant's base unit, or a configured pack size)")
    private String uom;
    @Schema(description = "How many base units one scan of this barcode represents: 1 for the base unit, or the pack size's " +
            "conversion factor (e.g. 24 for a case). Null if the pack size's conversion was removed after the barcode was linked")
    private BigDecimal factor;
    @Schema(description = "Whether this is the primary barcode for its unit. At most one primary per (variant, unit)")
    private Boolean isPrimary;
    private LocalDateTime createdAt;
}
