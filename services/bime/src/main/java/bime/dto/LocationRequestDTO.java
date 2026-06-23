package bime.dto;

import lombok.Data;

@Data
public class LocationRequestDTO {
    private String name;
    private String code;
    private Boolean isActive;
}
