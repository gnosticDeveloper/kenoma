package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Request body for setting a variant's unit-of-measure conversion")
public class UomConversionRequestDTO {
    @Schema(description = "Name of the alternate unit (e.g. \"case\", \"pack\")", example = "case")
    private String uomName;
    @Schema(description = "Number of base units (the variant's baseUom) that make up one of this unit. " +
            "Must be positive - e.g. 24 for a case of 24 cans when baseUom is \"units\"", example = "24")
    private BigDecimal factor;
    @Schema(description = "Optional flat price for one of this unit, in the variant's priceCurrency - lets a bulk unit " +
            "be priced as a discount rather than always being exactly factor * the variant's price. " +
            "Omit to fall back to factor * price at read time", example = "18.00")
    private BigDecimal price;
}
