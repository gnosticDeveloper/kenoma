package bime.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProductVariantResponseDTO {
    private UUID id;
    private UUID productId;
    private UUID orgId;
    private String sku;
    private boolean isActive;
    private LocalDateTime createdAt;
    private List<MetadataOptionResponseDTO> options;
    private List<VariantStockDTO> stock;
}
