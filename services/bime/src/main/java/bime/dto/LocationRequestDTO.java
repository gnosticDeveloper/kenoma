package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Request body for creating or updating a stock location")
public class LocationRequestDTO {
    private String name;
    @Schema(description = "Short alphanumeric code used to identify the location in reports and imports", example = "WH-01")
    private String code;
    private Boolean isActive;
}
