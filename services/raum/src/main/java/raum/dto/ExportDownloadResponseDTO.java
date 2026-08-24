package raum.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Downloadable files for a DONE tenant export job")
public class ExportDownloadResponseDTO {
    UUID jobId;
    List<ExportFilePartDTO> files;

    @Builder
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "One uploaded export file - fetch its content via GET /orgs/{id}/export/{jobId}/download/{index}")
    public static class ExportFilePartDTO {
        String key;
        int index;
    }
}
