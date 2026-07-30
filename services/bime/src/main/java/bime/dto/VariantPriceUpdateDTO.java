package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Schema(description = "A single variant's new price, in a batch price update")
public class VariantPriceUpdateDTO {
    private UUID variantId;
    private BigDecimal price;
}
