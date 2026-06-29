package raum.controllers;

import common.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import raum.dto.OnboardingRequestDTO;
import raum.services.OnboardingService;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/onboarding")
@RequiredArgsConstructor
@Tag(name = "Onboarding", description = "Org onboarding flows. Requires INITIATE_ONBOARDING.")
@SecurityRequirement(name = "bearerAuth")
class OnboardingController {

    private final OnboardingService onboardingService;

    @Operation(
            summary = "Initiate org onboarding",
            description = "Fetches ephemeral database credentials for every registered service under the given org. Requires INITIATE_ONBOARDING."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Onboarding initiated"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{orgId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> initiateOnboarding(@PathVariable UUID orgId, @RequestBody OnboardingRequestDTO request) {
        return onboardingService.initiateOnboarding(orgId, request);
    }
}
