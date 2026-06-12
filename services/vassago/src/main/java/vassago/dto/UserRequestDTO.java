package vassago.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class UserRequestDTO {
    String email;
    String name;
    String lastName;
    String username;
    Map<String, List<String>> roles;
}