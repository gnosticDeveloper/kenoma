package raum.controllers;

import common.exception.ErrorResponse;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import raum.dto.BillingEmailRequestDTO;
import raum.dto.BillingEmailVerifyRequestDTO;
import raum.dto.BillingInfoRequestDTO;
import raum.dto.OrgCurrencyResponseDTO;
import raum.dto.OrgRequestDTO;
import raum.dto.OrgResponseDTO;
import raum.openbao.OpenBaoService;
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

    public OrganizationsController(OrganizationService service, OpenBaoService openBaoService) {
        this.service = service;
        this.openBaoService = openBaoService;
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
}
