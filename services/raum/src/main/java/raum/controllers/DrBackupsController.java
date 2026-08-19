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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import raum.dto.DrBackupResponseDTO;
import raum.dto.RestoreRequestDTO;
import raum.services.DrBackupService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/dr-backups")
@Tag(name = "DR recovery", description = "Disaster-recovery backup catalog and restore - operator-only, backups here are never downloadable")
@SecurityRequirement(name = "bearerAuth")
public class DrBackupsController {

    private final DrBackupService drBackupService;

    public DrBackupsController(DrBackupService drBackupService) {
        this.drBackupService = drBackupService;
    }

    @Operation(summary = "List DR backup catalog entries", description = "Newest first. Requires ORG_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "DR backups listed"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    Flux<DrBackupResponseDTO> list(@RequestParam(name = "scope", required = false) String scope,
                                    @RequestParam(name = "orgId", required = false) UUID orgId) {
        return drBackupService.list(scope, orgId);
    }

    @Operation(summary = "Restore a DR backup", description = "Destructive: INSTANCE scope replays a full instance dump " +
            "in place; ORG scope deletes then replays a single org's rows. Requires body {\"confirm\": true} and ORG_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Restore completed"),
            @ApiResponse(responseCode = "400", description = "Missing confirmation, or bad request", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "DR backup not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/restore")
    Mono<Void> restore(@PathVariable("id") UUID id, @RequestBody RestoreRequestDTO request) {
        return drBackupService.restore(id, request.isConfirm());
    }
}
