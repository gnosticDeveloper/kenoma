package raum.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BasicCredentialDTO {
    UUID orgId;
    UUID serviceId; 
}
