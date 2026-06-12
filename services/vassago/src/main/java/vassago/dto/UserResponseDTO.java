package vassago.dto;

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
public class UserResponseDTO {
    UUID id;
    String name;
    String lastName;
    String email;
    String username;
    Map<String, List<String>> roles;
}