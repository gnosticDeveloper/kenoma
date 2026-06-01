package vassago.dto;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class UserRequestDTO {
    String email;
    String name;
    String lastName;
    String username;
    List<String> roles;
    String password;
    UUID orgId;
}
