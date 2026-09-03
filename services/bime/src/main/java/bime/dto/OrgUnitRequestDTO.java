package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Request body for adding a custom unit to the org's unit catalog")
public class OrgUnitRequestDTO {
    @Schema(description = "Unit name, matched case-insensitively (\"Case\" and \"case\" are the same unit)", example = "case")
    private String name;
}
