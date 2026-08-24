package raum.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import common.exception.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import raum.migration.MigrationRunner;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/migrations")
@Tag(name = "Migrations", description = "Schema migration sweep across every discovered database instance - operator-only")
@SecurityRequirement(name = "bearerAuth")
public class MigrationsController {

    private final MigrationRunner migrationRunner;

    public MigrationsController(MigrationRunner migrationRunner) {
        this.migrationRunner = migrationRunner;
    }

    @Operation(summary = "Re-run the migration sweep", description = "Applies any pending Flyway migration " +
            "to every distinct database instance discoverable via the credentials table, plus raum's own " +
            "database. Safe to call any time - already-applied migrations are no-ops. Requires ORG_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Sweep completed"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/run")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> run() {
        return migrationRunner.migrateAll();
    }
}
