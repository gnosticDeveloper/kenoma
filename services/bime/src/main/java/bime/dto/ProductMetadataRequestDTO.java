package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Request body for creating a product metadata definition (e.g. 'Color' or 'Size')")
public class ProductMetadataRequestDTO {
    @Schema(description = "Name of the metadata attribute. Must be unique within the organization", example = "Color")
    private String name;
}
