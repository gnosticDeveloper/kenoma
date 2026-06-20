package raum.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import raum.dto.OrgRequestDTO;
import raum.dto.OrgResponseDTO;
import raum.services.OrganizationService;
import reactor.core.publisher.Mono;
import java.util.UUID;

@RestController
@RequestMapping("/orgs")
public class OrganizationsController {
    final OrganizationService service;
    public OrganizationsController(OrganizationService service) {
        this.service = service;
    }

    @PostMapping
    Mono<OrgResponseDTO> registerOrg(@RequestBody OrgRequestDTO dto) {
        return service.registerOrg(dto);
    }

    @GetMapping("/{id}")
    Mono<OrgResponseDTO> getOrgDataById(@PathVariable("id") UUID id) {
        return service.getOrgDataById(id);
    }

    @PutMapping("/{id}")
    Mono<OrgResponseDTO> updateOrg(@PathVariable("id") UUID id, @RequestBody OrgRequestDTO dto) {
        return service.updateOrg(id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> deleteOrg(@PathVariable("id") UUID id) {
        return service.deleteOrg(id);
    }
}