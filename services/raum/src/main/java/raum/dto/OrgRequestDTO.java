package raum.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Request body for registering or updating an organisation")
public class OrgRequestDTO {
    String name;
    String contactEmail;
    @Schema(description = "Name of the primary contact person. Stored but not returned in responses")
    String contactName;
}
