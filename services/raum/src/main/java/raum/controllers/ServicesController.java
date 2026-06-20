package raum.controllers;

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
public class ServicesController {
    private final ServiceService service;

    @PostMapping
    Mono<ServiceResponseDTO> register(@RequestBody ServiceRequestDTO dto) {
        return service.register(dto);
    }

    @GetMapping("/{id}")
    Mono<ServiceResponseDTO> getById(@PathVariable("id") UUID id) {
        return service.getById(id);
    }

    @GetMapping
    Flux<ServiceResponseDTO> getAll() {
        return service.getAll();
    }

    @PutMapping("/{id}")
    Mono<ServiceResponseDTO> update(@PathVariable("id") UUID id, @RequestBody ServiceRequestDTO dto) {
        return service.update(id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> delete(@PathVariable("id") UUID id) {
        return service.delete(id);
    }
}