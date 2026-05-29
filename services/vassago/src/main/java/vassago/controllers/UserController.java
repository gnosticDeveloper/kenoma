package vassago.controllers;

import common.dto.BasicCredentialDTO;
import common.dto.CredentialsDTO;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import vassago.services.UserService;

@RestController
@RequestMapping("/user")
public class UserController {
    final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/login")
    public Mono<CredentialsDTO> getEphemeralCredential(@RequestBody BasicCredentialDTO requestDTO){
        return userService.test(requestDTO);
    }
}
