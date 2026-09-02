package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "A completed sale and its lines")
public class SaleResponseDTO {
    private UUID id;
    private UUID orgId;
    private UUID locationId;
    private String reference;
    private SaleStatus status;
    @Schema(description = "Sum of the line totals", example = "42.50")
    private BigDecimal subtotal;
    @Schema(description = "Currency of the amounts, taken from the variants' price currency. May be null")
    private String currency;
    private String note;
    private List<SaleLineResponseDTO> lines;
    private LocalDateTime soldAt;
    private UUID soldBy;
    private LocalDateTime voidedAt;
    private UUID voidedBy;
}
