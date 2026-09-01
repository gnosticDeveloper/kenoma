package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Traceability report for a batch: where its stock currently sits and every movement it was involved in")
public class RecallReportDTO {
    private BatchResponseDTO batch;
    @Schema(description = "Locations that still hold stock of this batch, with quantities")
    private List<BatchLocationBalanceDTO> affectedLocations;
    @Schema(description = "Every stock movement recorded against this batch, oldest first")
    private List<StockMovementResponseDTO> history;
}
