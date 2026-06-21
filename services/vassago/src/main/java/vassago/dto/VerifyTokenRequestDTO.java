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
public class VerifyTokenRequestDTO {
    @NonNull
    UUID orgId;
    @NonNull
    String token;
    @NonNull
    @Schema(
            description = "New password. Must be 12–72 characters and contain at least one uppercase letter, one lowercase letter, one digit, and one special character.",
            minLength = 12,
            maxLength = 72
    )
    String newPassword;
}
