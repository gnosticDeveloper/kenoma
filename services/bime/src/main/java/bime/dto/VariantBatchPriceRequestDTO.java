package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Batch of variant price updates, applied in one call. All prices are stored in the " +
        "organization's current base currency")
public class VariantBatchPriceRequestDTO {
    private List<VariantPriceUpdateDTO> items;
}
