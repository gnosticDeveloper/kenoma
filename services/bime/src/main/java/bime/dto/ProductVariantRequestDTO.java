package bime.dto;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class ProductVariantRequestDTO {
    private List<UUID> optionIds;
    private String sku;
    private Boolean isActive;
}
