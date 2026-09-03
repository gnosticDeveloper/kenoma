package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Schema(description = "Request body for ringing up a sale: one or more items scanned at a single location. " +
        "Stock is depleted immediately through the ledger; batch-tracked items are consumed first-expired-first-out")
public class SaleRequestDTO {
    @Schema(description = "The location the sale is rung up at, and the location stock is taken from")
    private UUID locationId;
    @Schema(description = "Optional receipt number or label", example = "POS-2026-00123")
    private String reference;
    @Schema(description = "Optional free-text note")
    private String note;
    private List<SaleLineRequestDTO> lines;
}
