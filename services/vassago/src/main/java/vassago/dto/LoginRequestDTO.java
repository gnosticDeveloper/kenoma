package vassago.dto;

import common.dto.BasicCredentialDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "Login credentials. orgId identifies the tenant; serviceId is unused for user logins and may be omitted")
public class LoginRequestDTO extends BasicCredentialDTO {
    String username;
    String password;
}
