package vassago.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import vassago.dto.PasswordChangeRequestDTO;
import vassago.dto.UserRequestDTO;
import vassago.dto.UserResponseDTO;
import vassago.dto.VerifyTokenRequestDTO;
import vassago.services.UserService;
import java.util.UUID;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @PostMapping
    @PreAuthorize("hasAuthority('VASSAGO_CREATE_USER')")
    public Mono<UserResponseDTO> createUser(@RequestBody UserRequestDTO dto) {
        return userService.createUser(dto);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VASSAGO_VIEW_USER')")
    public Mono<UserResponseDTO> getUserById(@PathVariable UUID id) {
        return userService.getUserById(id);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('VASSAGO_VIEW_USER')")
    public Flux<UserResponseDTO> getUsersByOrgId() {
        return userService.getUsersByOrgId();
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('VASSAGO_EDIT_USER')")
    public Mono<UserResponseDTO> updateUser(@PathVariable UUID id, @RequestBody UserRequestDTO dto) {
        return userService.updateUser(id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('VASSAGO_OFFBOARD_USER')")
    public Mono<Void> deleteUser(@PathVariable UUID id) {
        return userService.deleteUser(id);
    }

    @PostMapping("/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> verifyToken(@RequestBody VerifyTokenRequestDTO dto) {
        return userService.verifyToken(dto);
    }

    @PatchMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> changePassword(@RequestBody PasswordChangeRequestDTO dto) {
        return userService.changePassword(dto);
    }
}