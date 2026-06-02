package vassago.controllers;

import common.dto.BasicCredentialDTO;
import common.dto.CredentialsDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import vassago.dto.UserRequestDTO;
import vassago.dto.UserResponseDTO;
import vassago.services.UserService;
import java.util.UUID;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @PostMapping
    public Mono<UserResponseDTO> createUser(@RequestBody UserRequestDTO dto) {
        return userService.createUser(dto);
    }

    @GetMapping("/{orgId}/{id}")
    public Mono<UserResponseDTO> getUserById(@PathVariable UUID orgId, @PathVariable UUID id) {
        return userService.getUserById(orgId, id);
    }

    @GetMapping("/org/{orgId}")
    public Flux<UserResponseDTO> getUsersByOrgId(@PathVariable UUID orgId) {
        return userService.getUsersByOrgId(orgId);
    }

    @PutMapping("/{orgId}/{id}")
    public Mono<UserResponseDTO> updateUser(@PathVariable UUID orgId, @PathVariable UUID id, @RequestBody UserRequestDTO dto) {
        return userService.updateUser(orgId, id, dto);
    }

    @DeleteMapping("/{orgId}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteUser(@PathVariable UUID orgId, @PathVariable UUID id) {
        return userService.deleteUser(orgId, id);
    }
}