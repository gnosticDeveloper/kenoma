package raum.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentStatusUpdateRequestDTO {
    @Schema(description = "PENDING or PAID", requiredMode = Schema.RequiredMode.REQUIRED)
    String status;
    @Schema(description = "Optional payment reference/note (e.g. transfer ID), stored only when status is PAID")
    String reference;
}
