package bime.dto;

import lombok.Data;

@Data
public class ProductRequestDTO {
    private String sku;
    private String name;
    private String description;
    private Boolean isActive;
}
