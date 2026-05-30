package raum.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Builder
@Data
public class OrgResponseDTO {
    UUID id;
    String name;
    String contactEmail;
}
