package vassago.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "User details")
public class UserResponseDTO {
    UUID id;
    String name;
    String lastName;
    String email;
    String username;
    @Schema(description = "Role assignments keyed by service ID (UUID as string), with a list of role names as the value")
    Map<String, List<String>> roles;
}
