package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Request body for recalling a batch")
public class RecallRequestDTO {
    @Schema(description = "Optional free-text reason for the recall, kept on the batch record", example = "Supplier notice 2026-09-01: possible contamination")
    private String note;
}
