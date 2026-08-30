package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Schema(description = "Request body for creating or editing a draft transfer order. Every line moves stock " +
        "from the same source location to the same destination location")
public class StockTransferRequestDTO {
    @Schema(description = "Optional human-readable label or number for the transfer", example = "TR-2026-004")
    private String reference;
    @Schema(description = "Optional free-text note")
    private String note;
    private UUID sourceLocationId;
    private UUID destLocationId;
    private List<StockTransferLineRequestDTO> lines;
}
