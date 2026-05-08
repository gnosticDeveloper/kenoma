package raum.controllers;

import org.springframework.web.bind.annotation.*;
import raum.DTO.BasicCredentialDTO;
import raum.DTO.CredentialsDTO;
import raum.services.CredentialsService;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/credentials")
class CredentialsController {

    final CredentialsService credentialsService;

    public CredentialsController(CredentialsService credentialsService) {
        this.credentialsService = credentialsService;
    }

    @PostMapping("/ephemeral")
    public Mono<CredentialsDTO> getCredentialsForOrgAndService(@RequestBody BasicCredentialDTO requestDTO) {
        return credentialsService.getEphemeralCredentialsByOrgIdAndServiceId(requestDTO);
    }

    @GetMapping("/test-db")
    public Mono<Boolean> testDb() {
        return credentialsService.testDB();
    }

    @PostMapping
    public Mono<BasicCredentialDTO> saveCredentials(@RequestBody CredentialsDTO credentials) {
        return credentialsService.saveNewCredentials(Mono.just(credentials));
    }
}