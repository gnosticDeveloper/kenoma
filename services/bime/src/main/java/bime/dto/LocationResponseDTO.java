package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Stock location details")
public class LocationResponseDTO {
    private UUID id;
    private UUID orgId;
    private String name;
    @Schema(description = "Short alphanumeric code used to identify the location in reports and imports", example = "WH-01")
    private String code;
    private Boolean isActive;
    private String notificationEmail;
    private LocalDateTime createdAt;
    private LocalDateTime modifiedAt;
}
