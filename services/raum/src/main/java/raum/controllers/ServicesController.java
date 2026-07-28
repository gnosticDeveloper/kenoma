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
import raum.dto.ServiceRequestDTO;
import raum.dto.ServiceResponseDTO;
import raum.services.ServiceService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.UUID;

@RestController
@RequestMapping("/services")
@RequiredArgsConstructor
@Tag(name = "Services", description = "Platform service registration. The service ID issued here is used as the key in role maps across the platform")
@SecurityRequirement(name = "bearerAuth")
public class ServicesController {
    private final ServiceService service;

    @Operation(summary = "Register a service", description = "Registers a new platform service and issues it a UUID. Requires SERVICE_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Service registered"),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    Mono<ServiceResponseDTO> register(@RequestBody ServiceRequestDTO dto) {
        return service.register(dto);
    }

    @Operation(summary = "Get a service by ID", description = "Requires SERVICE_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Service found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Service not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    Mono<ServiceResponseDTO> getById(@PathVariable("id") UUID id) {
        return service.getById(id);
    }

    @Operation(summary = "List all services", description = "Requires SERVICE_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of services (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    Flux<ServiceResponseDTO> getAll() {
        return service.getAll();
    }

    @Operation(summary = "Update a service", description = "Requires SERVICE_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Service updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Service not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/{id}")
    Mono<ServiceResponseDTO> update(@PathVariable("id") UUID id, @RequestBody ServiceRequestDTO dto) {
        return service.update(id, dto);
    }

    @Operation(summary = "Delete a service", description = "Requires SERVICE_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Service deleted"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Service not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> delete(@PathVariable("id") UUID id) {
        return service.delete(id);
    }
}
