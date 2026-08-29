package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Schema(description = "A single variant's new cost, in a batch cost update")
public class VariantCostUpdateDTO {
    private UUID variantId;
    private BigDecimal cost;
}
