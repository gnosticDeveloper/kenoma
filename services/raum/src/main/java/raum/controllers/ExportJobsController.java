package raum.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import common.exception.ErrorResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import raum.dto.ExportJobResponseDTO;
import raum.services.ExportJobService;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/export-jobs")
@Tag(name = "Tenant exports", description = "Platform-wide view of tenant export jobs")
@SecurityRequirement(name = "bearerAuth")
public class ExportJobsController {

    private final ExportJobService exportJobService;

    public ExportJobsController(ExportJobService exportJobService) {
        this.exportJobService = exportJobService;
    }

    @Operation(summary = "List every tenant export job across every organization",
            description = "Newest first. For the platform operator's exports overview. Requires ORG_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Export jobs listed"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    Flux<ExportJobResponseDTO> listAll() {
        return exportJobService.listAll();
    }
}
