package raum.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import common.dto.BasicCredentialDTO;
import common.dto.CredentialsDTO;
import raum.openbao.OpenBaoService;
import raum.services.CredentialsService;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/credentials")
class CredentialsController {
    final CredentialsService credentialsService;
    final OpenBaoService openBaoService;

    public CredentialsController(CredentialsService credentialsService, OpenBaoService openBaoService) {
        this.credentialsService = credentialsService;
        this.openBaoService = openBaoService;
    }

    @PostMapping("/ephemeral")
    public Mono<CredentialsDTO> getCredentialsForOrgAndService(
            @RequestHeader("X-Vault-Token") String token,
            @RequestBody BasicCredentialDTO requestDTO) {
        return openBaoService.validateToken(token)
                .flatMap(valid -> valid
                        ? credentialsService.getEphemeralCredentialsByOrgIdAndServiceId(requestDTO)
                        : Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token")));
    }

    @GetMapping("/test-db")
    public Mono<Boolean> testDb() {
        return credentialsService.testDB();
    }

    @PostMapping
    public Mono<BasicCredentialDTO> saveCredentials(@RequestBody CredentialsDTO credentials) {
        return credentialsService.saveNewCredentials(credentials);
    }
}