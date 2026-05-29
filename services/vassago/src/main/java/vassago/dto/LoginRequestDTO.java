package vassago.dto;

import common.dto.BasicCredentialDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class LoginRequestDTO extends BasicCredentialDTO {
    String username;
    String password;
}
