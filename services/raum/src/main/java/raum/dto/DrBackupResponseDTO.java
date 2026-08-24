package raum.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A DR backup catalog entry - never downloadable, only restorable via POST /dr-backups/{id}/restore")
public class DrBackupResponseDTO {
    UUID id;
    @Schema(description = "INSTANCE (whole shared physical instance) or ORG (single org/service rewind)")
    String scope;
    String instanceHost;
    Integer instancePort;
    String instanceDb;
    UUID orgId;
    String serviceName;
    String objectKey;
    Instant createdAt;
    @Schema(description = "False only for raum's own platform-database INSTANCE backup - restoring it while raum itself is serving the request is unsafe and not exposed via this API; restore that one manually instead")
    boolean restorable;
}
