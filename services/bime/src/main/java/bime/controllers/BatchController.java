package bime.controllers;

import bime.dto.BatchResponseDTO;
import bime.dto.BatchStatus;
import bime.dto.OrgBatchSettingsRequestDTO;
import bime.dto.OrgBatchSettingsResponseDTO;
import bime.dto.RecallReportDTO;
import bime.dto.RecallRequestDTO;
import bime.services.BatchService;
import common.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/batches")
@RequiredArgsConstructor
@Tag(name = "Batches", description = "Production batches (lots) of batch-tracked products: read on-hand by batch, recall a batch, and configure near-expiry alerts.")
@SecurityRequirement(name = "bearerAuth")
public class BatchController {

    private final BatchService batchService;

    @Operation(summary = "List batches", description = "Batches for the organization, oldest expiry first, each with its per-location on-hand quantities. " +
            "Batches are created implicitly by INBOUND stock movements of a batch-tracked product. Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of batches (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Flux<BatchResponseDTO> list(
            @Parameter(description = "Filter to batches of this variant") @RequestParam(required = false) UUID variantId,
            @Parameter(description = "Only report on-hand quantities at this location") @RequestParam(required = false) UUID locationId,
            @Parameter(description = "Filter to batches in this state (ACTIVE, RECALLED)") @RequestParam(required = false) BatchStatus status,
            @Parameter(description = "Filter to dated batches expiring within this many days from today") @RequestParam(required = false) Integer expiringWithinDays) {
        return batchService.listBatches(variantId, locationId, status, expiringWithinDays);
    }

    @Operation(summary = "Get a batch", description = "One batch with its per-location on-hand quantities. Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Batch found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Batch not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Mono<BatchResponseDTO> get(@PathVariable UUID id) {
        return batchService.getBatch(id);
    }

    @Operation(summary = "Recall traceability report", description = "For a batch: where its stock currently sits and every stock movement it was involved in, oldest first. Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Report generated"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Batch not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}/recall-report")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Mono<RecallReportDTO> recallReport(@PathVariable UUID id) {
        return batchService.recallReport(id);
    }

    @Operation(summary = "Recall a batch", description = "Marks the batch RECALLED: it is skipped by first-expired-first-out allocation and rejected for OUTBOUND " +
            "movements (a disposal ADJUSTMENT is still allowed). Requires BIME_RECALL_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Batch recalled"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Batch not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Batch is already under recall", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/recall")
    @PreAuthorize("hasAuthority('BIME_RECALL_MANAGE')")
    public Mono<BatchResponseDTO> recall(@PathVariable UUID id, @RequestBody(required = false) RecallRequestDTO dto) {
        return batchService.recall(id, dto != null ? dto.getNote() : null);
    }

    @Operation(summary = "Lift a batch recall", description = "Returns a RECALLED batch to ACTIVE, clearing the recall note. Requires BIME_RECALL_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recall lifted"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Batch not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Batch is not under recall", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/lift-recall")
    @PreAuthorize("hasAuthority('BIME_RECALL_MANAGE')")
    public Mono<BatchResponseDTO> liftRecall(@PathVariable UUID id) {
        return batchService.liftRecall(id);
    }

    @Operation(summary = "Get batch expiry-alert settings", description = "The org's near-expiry alert window (days before expiry). Defaults to 30 if never set. Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Settings returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Mono<OrgBatchSettingsResponseDTO> getSettings() {
        return batchService.getSettings();
    }

    @Operation(summary = "Update batch expiry-alert settings", description = "Sets how many days before expiry the daily sweep starts sending near-expiry alerts. Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Settings updated"),
            @ApiResponse(responseCode = "400", description = "nearExpiryDays must be a positive number", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<OrgBatchSettingsResponseDTO> updateSettings(@RequestBody OrgBatchSettingsRequestDTO dto) {
        return batchService.updateSettings(dto);
    }
}
