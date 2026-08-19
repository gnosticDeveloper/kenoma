package raum.controllers;

import common.exception.ErrorResponse;
import common.exception.ForbiddenException;
import common.exception.NotFoundException;
import common.exception.UnauthorizedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import raum.dto.BillingEmailRequestDTO;
import raum.dto.BillingEmailVerifyRequestDTO;
import raum.dto.BillingInfoRequestDTO;
import raum.dto.OrgCurrencyResponseDTO;
import raum.backup.ArtifactStore;
import raum.dto.ExportDownloadResponseDTO;
import raum.dto.ExportJobResponseDTO;
import raum.dto.OrgRequestDTO;
import raum.dto.OrgResponseDTO;
import raum.openbao.OpenBaoService;
import raum.security.RaumAuthentication;
import raum.security.RaumPermission;
import raum.services.ExportJobService;
import raum.services.OrganizationService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.UUID;

@RestController
@RequestMapping("/orgs")
@Tag(name = "Organizations", description = "Tenant registration and management")
@SecurityRequirement(name = "bearerAuth")
public class OrganizationsController {
    final OrganizationService service;
    final OpenBaoService openBaoService;
    final ExportJobService exportJobService;
    final ArtifactStore artifactStore;

    public OrganizationsController(OrganizationService service, OpenBaoService openBaoService, ExportJobService exportJobService,
                                    @Qualifier("s3ArtifactStore") ArtifactStore artifactStore) {
        this.service = service;
        this.openBaoService = openBaoService;
        this.exportJobService = exportJobService;
        this.artifactStore = artifactStore;
    }

    @Operation(summary = "Register an organisation", description = "Creates a new tenant organisation. Requires ORG_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Organisation registered"),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    Mono<OrgResponseDTO> registerOrg(@RequestBody OrgRequestDTO dto) {
        return service.registerOrg(dto);
    }

    @Operation(summary = "List organisations", description = "Returns all active (non-deleted) organisations. Requires ORG_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Organisations listed"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    Flux<OrgResponseDTO> getAllOrgs() {
        return service.getAllOrgs();
    }

    @Operation(
            summary = "List active organisation IDs",
            description = "Returns the IDs of all active (non-deleted) organisations, with no other org detail. " +
                    "For machine-to-machine callers only — authenticates via X-Vault-Token (OpenBao AppRole), not a user JWT. " +
                    "Intended for background jobs in other services (e.g. Bime's stock alert scheduler) that need to iterate over every org."
    )
    @SecurityRequirements({@SecurityRequirement(name = "vaultToken")})
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Org IDs listed"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid X-Vault-Token", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/active-ids")
    Flux<UUID> getActiveOrgIds(
            @Parameter(description = "OpenBao AppRole token") @RequestHeader(value = "X-Vault-Token", required = false) String vaultToken) {
        if (vaultToken == null) {
            return Flux.error(new UnauthorizedException("X-Vault-Token required"));
        }
        return openBaoService.validateToken(vaultToken)
                .flatMapMany(valid -> valid
                        ? service.getActiveOrgIds()
                        : Flux.error(new UnauthorizedException("Invalid token")));
    }

    @Operation(
            summary = "Get an organisation's billing and product pricing currencies",
            description = "Returns the org's billing currency (currency), its product pricing currency " +
                    "(productPricingCurrency - what it prices its own catalog in, independent of billing), " +
                    "and currencyRefreshMode, with no other org detail. " +
                    "For machine-to-machine callers only — authenticates via X-Vault-Token (OpenBao AppRole), not a user JWT. " +
                    "Intended for other services (e.g. Bime) that need to know an org's currency setup for pricing."
    )
    @SecurityRequirements({@SecurityRequirement(name = "vaultToken")})
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Org currency found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid X-Vault-Token", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Organisation not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}/currency")
    Mono<OrgCurrencyResponseDTO> getOrgCurrency(
            @PathVariable("id") UUID id,
            @Parameter(description = "OpenBao AppRole token") @RequestHeader(value = "X-Vault-Token", required = false) String vaultToken) {
        if (vaultToken == null) {
            return Mono.error(new UnauthorizedException("X-Vault-Token required"));
        }
        return openBaoService.validateToken(vaultToken)
                .flatMap(valid -> valid
                        ? service.getOrgDataById(id)
                                .switchIfEmpty(Mono.error(new NotFoundException("Organization not found")))
                                .map(org -> new OrgCurrencyResponseDTO(
                                        org.getCurrency(), org.getCurrencyRefreshMode(), org.getProductPricingCurrency()))
                        : Mono.error(new UnauthorizedException("Invalid token")));
    }

    @Operation(summary = "Get an organisation by ID", description = "Requires ORG_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Organisation found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Organisation not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    Mono<OrgResponseDTO> getOrgDataById(@PathVariable("id") UUID id) {
        return service.getOrgDataById(id);
    }

    @Operation(summary = "Update an organisation", description = "Replaces all fields of the organisation. Requires ORG_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Organisation updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Organisation not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/{id}")
    Mono<OrgResponseDTO> updateOrg(@PathVariable("id") UUID id, @RequestBody OrgRequestDTO dto) {
        return service.updateOrg(id, dto);
    }

    @Operation(summary = "Delete an organisation", description = "Soft-deletes the organisation by setting stopped_at. Requires ORG_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Organisation deleted"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Organisation not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> deleteOrg(@PathVariable("id") UUID id) {
        return service.deleteOrg(id);
    }

    @Operation(summary = "Update billing info", description = "Sets tax ID, fiscal name/address, billing cycle and next invoice due date. Requires ORG_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Billing info updated"),
            @ApiResponse(responseCode = "400", description = "Invalid billing cycle", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Organisation not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/{id}/billing-info")
    Mono<OrgResponseDTO> updateBillingInfo(@PathVariable("id") UUID id, @RequestBody BillingInfoRequestDTO dto) {
        return service.updateBillingInfo(id, dto);
    }

    @Operation(summary = "Request billing email verification", description = "Sets the organisation's billing email and sends a confirmation link. Requires ORG_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Verification email sent"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Organisation not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/billing-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> requestBillingEmailVerification(@PathVariable("id") UUID id, @RequestBody BillingEmailRequestDTO dto) {
        return service.requestBillingEmailVerification(id, dto);
    }

    @Operation(summary = "Confirm billing email", description = "Confirms a billing email verification token. No authentication required — the token itself is the credential.")
    @SecurityRequirements({})
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Billing email confirmed"),
            @ApiResponse(responseCode = "404", description = "Invalid or expired token", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/billing-email/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> confirmBillingEmail(@PathVariable("id") UUID id, @RequestBody BillingEmailVerifyRequestDTO dto) {
        return service.confirmBillingEmail(id, dto);
    }

    @Operation(summary = "Confirm contact email", description = "Confirms the organisation's contact email verification token. No authentication required — the token itself is the credential.")
    @SecurityRequirements({})
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Contact email confirmed"),
            @ApiResponse(responseCode = "404", description = "Invalid or expired token", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/contact-email/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> confirmContactEmail(@PathVariable("id") UUID id, @RequestBody BillingEmailVerifyRequestDTO dto) {
        return service.confirmContactEmail(id, dto);
    }

    @Operation(
            summary = "Request a tenant data export",
            description = "Queues an export of the organization's data (raum's org-scoped rows plus a full " +
                    "dump of its dedicated Vassago/Bime databases) to object storage, for offboarding. Runs " +
                    "asynchronously — poll the returned job via GET /orgs/{id}/export/{jobId}. Idempotent: " +
                    "if a PENDING or RUNNING export job already exists for this org, that job is returned " +
                    "instead of queuing a duplicate — its original format/layout are kept, the query params " +
                    "are ignored in that case. `layout` only applies to JSON/CSV: SEPARATE (default) keeps " +
                    "one file per service, MERGED combines every service's data into a single file " +
                    "(namespaced by service to avoid table-name collisions) — SQL always stays one restorable " +
                    "dump per service regardless of `layout`, and rejects layout=MERGED with 400. Requires " +
                    "ORG_MANAGE, or ORG_EXPORT_SELF for the caller's own org."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Export job queued"),
            @ApiResponse(responseCode = "400", description = "Unknown format/layout, or layout=MERGED with format=SQL", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions, or ORG_EXPORT_SELF holder requesting another org's export", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Organisation not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/export")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<ExportJobResponseDTO> requestExport(@PathVariable("id") UUID id,
                                              @Parameter(description = "SQL (default), JSON or CSV")
                                              @RequestParam(name = "format", required = false) String format,
                                              @Parameter(description = "SEPARATE (default) or MERGED - JSON/CSV only")
                                              @RequestParam(name = "layout", required = false) String layout) {
        return requireOwnOrgUnlessOrgManage(id).then(exportJobService.requestExport(id, format, layout));
    }

    @Operation(summary = "Get a tenant export job's status", description = "Requires ORG_MANAGE, or ORG_EXPORT_SELF for the caller's own org.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Export job found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions, or ORG_EXPORT_SELF holder requesting another org's job", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Export job not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}/export/{jobId}")
    Mono<ExportJobResponseDTO> getExportJob(@PathVariable("id") UUID id, @PathVariable("jobId") UUID jobId) {
        return requireOwnOrgUnlessOrgManage(id).then(exportJobService.getJob(id, jobId));
    }

    @Operation(
            summary = "Get short-lived download links for a completed tenant export job",
            description = "Returns a presigned URL (expires in 15 minutes) per uploaded file for a DONE " +
                    "export job - one file under SEPARATE layout per service, one under MERGED. Requires " +
                    "ORG_MANAGE, or ORG_EXPORT_SELF for the caller's own org."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Download links generated"),
            @ApiResponse(responseCode = "400", description = "Export job has not finished yet", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions, or ORG_EXPORT_SELF holder requesting another org's job", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Export job not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}/export/{jobId}/download")
    Mono<ExportDownloadResponseDTO> getExportDownloadLinks(@PathVariable("id") UUID id, @PathVariable("jobId") UUID jobId) {
        return requireOwnOrgUnlessOrgManage(id)
                .then(exportJobService.getDownloadKeys(id, jobId))
                .flatMapMany(Flux::fromIterable)
                .flatMap(key -> artifactStore.presign(key)
                        .map(url -> new ExportDownloadResponseDTO.ExportFilePartDTO(key, url)))
                .collectList()
                .map(files -> ExportDownloadResponseDTO.builder().jobId(jobId).files(files).build());
    }

    /**
     * ORG_MANAGE holders (the platform operator) may export any org. ORG_EXPORT_SELF holders
     * (an org's own users) may only export their own org — without this check, that permission
     * would let any org read another tenant's exported data just by naming its org id in the path.
     */
    private Mono<Void> requireOwnOrgUnlessOrgManage(UUID requestedOrgId) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (RaumAuthentication) ctx.getAuthentication())
                .flatMap(auth -> {
                    boolean isOrgManage = auth.getAuthorities().stream()
                            .anyMatch(a -> a.getAuthority().equals(RaumPermission.ORG_MANAGE.name()));
                    if (isOrgManage || auth.getOrgId().equals(requestedOrgId)) {
                        return Mono.empty();
                    }
                    return Mono.error(new ForbiddenException("Cannot export another organization's data"));
                });
    }
}
