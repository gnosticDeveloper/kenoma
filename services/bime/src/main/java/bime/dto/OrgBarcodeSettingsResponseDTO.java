package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "The org's barcode issuance settings")
public class OrgBarcodeSettingsResponseDTO {
    private UUID orgId;
    @Schema(description = "The org's GS1 company prefix, or null when issuance uses the restricted-distribution range")
    private String gs1Prefix;
    @Schema(description = "The next item-reference number that will be consumed by an issue call")
    private long nextSequence;
    private LocalDateTime createdAt;
    private LocalDateTime modifiedAt;
}
