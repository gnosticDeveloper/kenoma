package vassago.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import vassago.dto.LoginRequestDTO;
import vassago.dto.LoginResponseDTO;
import vassago.services.AuthService;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Mono<LoginResponseDTO> login(@RequestBody LoginRequestDTO dto) {
        return authService.login(dto).map(LoginResponseDTO::new);
    }
}