package raum.controllers;

import common.exception.ErrorResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import raum.billing.PricingService;
import raum.dto.BasePricingRequestDTO;
import raum.dto.BasePricingResponseDTO;
import raum.dto.ExchangeRateRequestDTO;
import raum.dto.ExchangeRateResponseDTO;
import raum.dto.ModulePricingRequestDTO;
import raum.dto.ModulePricingResponseDTO;
import raum.openbao.OpenBaoService;
import raum.services.PricingAdminService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;

@RestController
@RequestMapping("/pricing")
@Tag(name = "Pricing", description = "Base price, per-module price, and exchange rate configuration. Requires PRICING_MANAGE.")
@SecurityRequirement(name = "bearerAuth")
public class PricingController {

    private final PricingAdminService service;
    private final PricingService pricingService;
    private final OpenBaoService openBaoService;

    public PricingController(PricingAdminService service, PricingService pricingService, OpenBaoService openBaoService) {
        this.service = service;
        this.pricingService = pricingService;
        this.openBaoService = openBaoService;
    }

    @Operation(summary = "List base price history", description = "Requires PRICING_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Base price history listed"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/base")
    Flux<BasePricingResponseDTO> listBasePricing() {
        return service.listBasePricing();
    }

    @Operation(summary = "Add a new effective-dated base price", description = "Inserts a new price row; does not overwrite prior history. Requires PRICING_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Base price added"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/base")
    Mono<BasePricingResponseDTO> addBasePricing(@RequestBody BasePricingRequestDTO dto) {
        return service.addBasePricing(dto);
    }

    @Operation(summary = "List module price history", description = "Requires PRICING_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Module price history listed"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/modules")
    Flux<ModulePricingResponseDTO> listModulePricing() {
        return service.listModulePricing();
    }

    @Operation(summary = "Add a new effective-dated module price", description = "Inserts a new price row for a service; does not overwrite prior history. Requires PRICING_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Module price added"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Service not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/modules")
    Mono<ModulePricingResponseDTO> addModulePricing(@RequestBody ModulePricingRequestDTO dto) {
        return service.addModulePricing(dto);
    }

    @Operation(summary = "List exchange rate history", description = "Requires PRICING_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Exchange rate history listed"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/exchange-rates")
    Flux<ExchangeRateResponseDTO> listExchangeRates() {
        return service.listExchangeRates();
    }

    @Operation(summary = "Add a new effective-dated exchange rate", description = "Inserts a new rate row; does not overwrite prior history. Requires PRICING_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Exchange rate added"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/exchange-rates")
    Mono<ExchangeRateResponseDTO> addExchangeRate(@RequestBody ExchangeRateRequestDTO dto) {
        return service.addExchangeRate(dto);
    }

    @Operation(
            summary = "Get the current conversion rate between two currencies",
            description = "Returns just the rate (not a converted amount) so callers can apply it to many values locally. " +
                    "Always reads the latest stored exchange_rates row - never triggers a live external FX call, " +
                    "so read traffic can't drive up FX provider costs. That table is kept fresh by a daily scheduler " +
                    "(PERIODIC orgs) or curated by hand via POST /pricing/exchange-rates (MANUAL orgs). " +
                    "For machine-to-machine callers only — authenticates via X-Vault-Token (OpenBao AppRole), not a user JWT. " +
                    "Intended for other services (e.g. Bime) converting prices for display."
    )
    @SecurityRequirements({@SecurityRequirement(name = "vaultToken")})
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rate resolved"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid X-Vault-Token", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "No exchange rate configured for the requested pair", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/rate")
    Mono<BigDecimal> getRate(
            @Parameter(description = "OpenBao AppRole token") @RequestHeader(value = "X-Vault-Token", required = false) String vaultToken,
            @RequestParam String from,
            @RequestParam String to) {
        if (vaultToken == null) {
            return Mono.error(new UnauthorizedException("X-Vault-Token required"));
        }
        return openBaoService.validateToken(vaultToken)
                .flatMap(valid -> valid
                        ? pricingService.getRate(from, to, Instant.now())
                        : Mono.error(new UnauthorizedException("Invalid token")));
    }
}
