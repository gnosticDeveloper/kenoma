package raum.controllers;

import common.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import raum.dto.BillingHistoryResponseDTO;
import raum.services.BillingHistoryService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/orgs/{orgId}/billing-history")
@Tag(name = "Billing history", description = "Invoiced billing cycles per organization")
@SecurityRequirement(name = "bearerAuth")
public class BillingHistoryController {

    private final BillingHistoryService service;

    public BillingHistoryController(BillingHistoryService service) {
        this.service = service;
    }

    @Operation(summary = "List billing history", description = "Returns invoiced billing cycles for an organization, most recent first. Requires ORG_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Billing history listed"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    Flux<BillingHistoryResponseDTO> listBillingHistory(@PathVariable UUID orgId) {
        return service.listForOrg(orgId);
    }

    @Operation(summary = "Download an invoice", description = "Regenerates the PDF invoice for a billing history entry from its snapshotted amounts. Requires ORG_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invoice PDF"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Billing history entry not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{historyId}/invoice")
    Mono<ResponseEntity<byte[]>> downloadInvoice(@PathVariable UUID orgId, @PathVariable UUID historyId) {
        return service.getInvoicePdf(orgId, historyId)
                .map(pdf -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=invoice-" + historyId + ".pdf")
                        .body(pdf));
    }
}
