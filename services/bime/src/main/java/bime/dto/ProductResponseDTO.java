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
    @Schema(description = "Stock Keeping Unit. A unique identifier for this product within the organization", example = "WIDGET-001")
    private String sku;
    private String name;
    @Schema(description = "Optional description of the product")
    private String description;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime modifiedAt;
    @Schema(description = "Metadata attributes assigned to this product, each with the options that are selected for it")
    private List<AssignedMetadataDTO> metadata;
    @Schema(description = "All variants of this product (e.g. different color/size combinations). Populated by GET /products/{id}; omitted (null) by the GET /products list endpoint, which reports variantCount instead")
    private List<ProductVariantResponseDTO> variants;
    @Schema(description = "Number of variants this product has. Populated by the GET /products list endpoint; omitted (null) by GET /products/{id}, which reports the full variants list instead")
    private Integer variantCount;
}
