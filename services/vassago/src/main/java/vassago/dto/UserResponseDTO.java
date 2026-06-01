package vassago.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserResponseDTO {
    UUID id;
    UUID orgId;
    String name;
    String lastName;
    String email;
    String username;
    List<String> roles;
}