package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "A unit in the org's unit catalog. Variants can only reference units that exist here")
public class OrgUnitResponseDTO {
    private UUID id;
    private UUID orgId;
    private String name;
    @Schema(description = "Whether this is one of the built-in standard metric units (kg, g, m, cm, l, ml) or the " +
            "generic count unit (units), which have automatic conversions between them - as opposed to a custom " +
            "org-defined unit (e.g. \"case\"), which needs an explicit conversion configured per variant")
    private boolean standard;
    private LocalDateTime createdAt;
}
