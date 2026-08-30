package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "A transfer order and its lines")
public class StockTransferResponseDTO {
    private UUID id;
    private UUID orgId;
    private String reference;
    private TransferStatus status;
    private String note;
    @Schema(description = "Source location shared by every line")
    private UUID sourceLocationId;
    @Schema(description = "Destination location shared by every line")
    private UUID destLocationId;
    private List<StockTransferLineResponseDTO> lines;
    private LocalDateTime createdAt;
    private UUID createdBy;
    private LocalDateTime submittedAt;
    private UUID submittedBy;
    private LocalDateTime approvedAt;
    private UUID approvedBy;
    private LocalDateTime dispatchedAt;
    private UUID dispatchedBy;
    private LocalDateTime completedAt;
    private UUID completedBy;
    private LocalDateTime cancelledAt;
    private UUID cancelledBy;
}
