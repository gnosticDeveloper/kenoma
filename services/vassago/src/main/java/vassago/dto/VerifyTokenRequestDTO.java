package vassago.dto;

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
    String newPassword;
}
