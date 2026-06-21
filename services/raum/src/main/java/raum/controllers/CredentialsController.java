package raum.controllers;

import common.exception.UnauthorizedException;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.*;
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
            @RequestHeader(value = "X-Vault-Token", required = false) String vaultToken,
            @RequestBody BasicCredentialDTO requestDTO) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth != null && auth.isAuthenticated())
                .flatMap(auth -> credentialsService.getEphemeralCredentialsByOrgIdAndServiceId(requestDTO))
                .switchIfEmpty(Mono.defer(() -> {
                    if (vaultToken != null) {
                        return openBaoService.validateToken(vaultToken)
                                .flatMap(valid -> valid
                                        ? credentialsService.getEphemeralCredentialsByOrgIdAndServiceId(requestDTO)
                                        : Mono.error(new UnauthorizedException("Invalid token")));
                    }
                    return Mono.error(new UnauthorizedException("Authentication required"));
                }));
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