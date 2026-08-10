package raum.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import raum.security.RaumRole;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/roles")
@Tag(name = "Roles", description = "Role names valid for this service")
@SecurityRequirement(name = "bearerAuth")
public class RolesController {

    @Operation(summary = "List Raum's role names", description = "Static role names accepted for this service's role assignments.")
    @GetMapping
    Mono<List<String>> getRoles() {
        return Flux.fromArray(RaumRole.values()).map(Enum::name).collectList();
    }
}
