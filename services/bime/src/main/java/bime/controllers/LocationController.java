package bime.controllers;

import bime.dto.LocationRequestDTO;
import bime.dto.LocationResponseDTO;
import bime.services.LocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/locations")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locationService;

    @PostMapping
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<LocationResponseDTO> createLocation(@RequestBody LocationRequestDTO dto) {
        return locationService.createLocation(dto);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Flux<LocationResponseDTO> getLocations() {
        return locationService.getLocations();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Mono<LocationResponseDTO> getLocationById(@PathVariable UUID id) {
        return locationService.getLocationById(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<LocationResponseDTO> updateLocation(@PathVariable UUID id, @RequestBody LocationRequestDTO dto) {
        return locationService.updateLocation(id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<Void> deactivateLocation(@PathVariable UUID id) {
        return locationService.deactivateLocation(id);
    }
}
