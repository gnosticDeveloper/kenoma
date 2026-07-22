package vassago.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Schema(description = "Request body for creating or updating a user")
public class UserRequestDTO {
    String email;
    String name;
    String lastName;
    String username;
    @Schema(description = "Role assignments keyed by service ID (UUID as string), with a list of role names as the value. " +
            "Callers cannot grant roles they do not hold themselves. Example: {\"<vassago-service-id>\": [\"VASSAGO_ADMIN\"]}")
    Map<String, List<String>> roles;
    @Schema(description = "Preferred locale for account emails (e.g. \"en\", \"es\"). Defaults to \"en\" if omitted.")
    String locale;
}
