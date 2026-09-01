package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "A production batch (lot) of a batch-tracked variant, with its per-location on-hand quantities")
public class BatchResponseDTO {
    private UUID id;
    private UUID variantId;
    @Schema(description = "The producer's batch/lot code, unique per variant within the organization")
    private String batchCode;
    @Schema(description = "Expiry date, or null when this batch carries no date")
    private LocalDate expiryDate;
    private BatchStatus status;
    private LocalDateTime recalledAt;
    private String recallNote;
    private LocalDateTime createdAt;
    @Schema(description = "On-hand quantity per location; only locations with a balance row are listed")
    private List<BatchLocationBalanceDTO> balances;
    @Schema(description = "Total on-hand across all locations, in the variant's base unit")
    private BigDecimal totalQuantity;
}
