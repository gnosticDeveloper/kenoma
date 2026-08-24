package vassago.controllers;

import common.dto.RoleDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import vassago.security.VassagoRole;

import java.util.List;

@RestController
@RequestMapping("/roles")
@Tag(name = "Roles", description = "Roles valid for this service's role assignments")
@SecurityRequirement(name = "bearerAuth")
public class RolesController {

    @Operation(summary = "List Vassago's roles", description = "Static roles accepted for this service's role assignments, with display names and descriptions.")
    @GetMapping
    Mono<List<RoleDTO>> getRoles() {
        return Flux.fromArray(VassagoRole.values())
                .map(r -> new RoleDTO(r.name(), r.getDisplayName(), r.getDescription()))
                .collectList();
    }
}
