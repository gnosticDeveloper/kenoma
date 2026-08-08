package raum.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A single invoiced billing cycle for an organization")
public class BillingHistoryResponseDTO {
    UUID id;
    UUID orgId;
    String billingCycle;
    Instant dueAt;
    Instant createdAt;
    BigDecimal amount;
    String currency;
    @Schema(description = "JSON breakdown of the invoiced total, snapshotted at invoice time")
    String lineItems;
    @Schema(description = "PENDING or PAID, set manually by an admin")
    String paymentStatus;
    @Schema(description = "True if still PENDING past its due date; derived, not stored")
    boolean overdue;
    Instant paidAt;
    String paymentReference;
}
