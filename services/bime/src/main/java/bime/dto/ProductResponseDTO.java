package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Product details including assigned metadata and variants")
public class ProductResponseDTO {
    private UUID id;
    private UUID orgId;
    @Schema(description = "Stock Keeping Unit. A unique identifier for this product within the organisation", example = "WIDGET-001")
    private String sku;
    private String name;
    @Schema(description = "Optional description of the product")
    private String description;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime modifiedAt;
    @Schema(description = "Metadata attributes assigned to this product, each with the options that are selected for it")
    private List<AssignedMetadataDTO> metadata;
    @Schema(description = "All variants of this product (e.g. different colour/size combinations)")
    private List<ProductVariantResponseDTO> variants;
}
