package vassago.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PasswordChangeRequestDTO {
    @NonNull
    UUID orgId;
    @NonNull
    String username;
    @NonNull
    String oldPassword;
    @NonNull
    String newPassword;
}
