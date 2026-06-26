package vassago.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Step 1 of a self-service password change. Verifies the current password and sends a reset link to the user's email address. " +
        "The new password is set in step 2 by submitting the emailed token to POST /user/verify")
public class PasswordChangeRequestDTO {
    @NonNull
    String oldPassword;
}
