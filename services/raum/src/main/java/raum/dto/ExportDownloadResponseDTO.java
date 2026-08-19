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
@Schema(description = "Short-lived download links for a DONE tenant export job's uploaded files")
public class ExportDownloadResponseDTO {
    UUID jobId;
    List<ExportFilePartDTO> files;

    @Builder
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "One uploaded export file and its presigned download URL (expires in 15 minutes)")
    public static class ExportFilePartDTO {
        String key;
        String url;
    }
}
