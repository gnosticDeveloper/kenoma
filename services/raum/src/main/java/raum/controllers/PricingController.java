package raum.controllers;

import common.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import raum.dto.BasePricingRequestDTO;
import raum.dto.BasePricingResponseDTO;
import raum.dto.ExchangeRateRequestDTO;
import raum.dto.ExchangeRateResponseDTO;
import raum.dto.ModulePricingRequestDTO;
import raum.dto.ModulePricingResponseDTO;
import raum.services.PricingAdminService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/pricing")
@Tag(name = "Pricing", description = "Base price, per-module price, and exchange rate configuration. Requires PRICING_MANAGE.")
@SecurityRequirement(name = "bearerAuth")
public class PricingController {

    private final PricingAdminService service;

    public PricingController(PricingAdminService service) {
        this.service = service;
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
}
