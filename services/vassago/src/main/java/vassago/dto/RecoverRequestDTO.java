package vassago.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Initiates account recovery. A reset link is sent by email. Always returns 204 regardless of whether the account exists to prevent user enumeration")
public class RecoverRequestDTO {
    @NonNull
    UUID orgId;
    @NonNull
    String username;
}
