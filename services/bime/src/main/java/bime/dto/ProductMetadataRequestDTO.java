package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Request body for creating a product metadata definition (e.g. 'Colour' or 'Size')")
public class ProductMetadataRequestDTO {
    @Schema(description = "Name of the metadata attribute. Must be unique within the organisation", example = "Colour")
    private String name;
}
