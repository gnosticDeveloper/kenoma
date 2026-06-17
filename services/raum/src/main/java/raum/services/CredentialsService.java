package raum.services;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import common.dto.BasicCredentialDTO;
import common.dto.CredentialsDTO;
import raum.models.Credentials;
import raum.openbao.OpenBaoService;
import raum.repository.CredentialsRepository;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CredentialsService {

    private final CredentialsRepository credentialsRepository;
    private final OpenBaoService openBaoService;
    private final UUID serviceId;

    public Mono<BasicCredentialDTO> saveNewCredentials(CredentialsDTO dto) {
        if (dto.getServiceId().equals(serviceId)) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Credentials cannot be registered for Raum"));
        }
        return credentialsRepository.save(Credentials.builder()
                        .orgId(dto.getOrgId())
                        .serviceId(dto.getServiceId())
                        .dbEngine(dto.getDbEngine())
                        .dbHost(dto.getDbHost())
                        .dbPort(dto.getDbPort())
                        .dbName(dto.getDbName())
                        .createdAt(Instant.now())
                        .modifiedAt(Instant.now())
                        .build())
                .flatMap(saved -> openBaoService.storeCredentials(saved.getId(), dto.getUserName(), dto.getPassword())
                        .then(openBaoService.registerDatabaseConnection(
                                saved.getId(),
                                dto.getUserName(),
                                dto.getPassword(),
                                dto.getDbHost(),
                                dto.getDbPort(),
                                dto.getDbName()
                        ))
                        .thenReturn(BasicCredentialDTO.builder()
                                .orgId(saved.getOrgId())
                                .serviceId(saved.getServiceId())
                                .build())
                );
    }

    public Mono<CredentialsDTO> getEphemeralCredentialsByOrgIdAndServiceId(BasicCredentialDTO dto) {
        return credentialsRepository.findByOrgIdAndServiceId(dto.getOrgId(), dto.getServiceId())
                .flatMap(credentials ->
                        openBaoService.issueEphemeralCredentials(credentials.getId())
                                .map(ephemeral -> {
                                    CredentialsDTO result = new CredentialsDTO();
                                    result.setOrgId(credentials.getOrgId());
                                    result.setServiceId(credentials.getServiceId());
                                    result.setUserName(ephemeral.getUserName());
                                    result.setPassword(ephemeral.getPassword());
                                    result.setDbHost(credentials.getDbHost());
                                    result.setDbPort(credentials.getDbPort());
                                    result.setDbName(credentials.getDbName());
                                    result.setDbEngine(credentials.getDbEngine());
                                    result.setLeaseId(ephemeral.getLeaseId());
                                    result.setLeaseDuration(ephemeral.getLeaseDuration());
                                    return result;
                                })
                )
                .switchIfEmpty(Mono.error(new RuntimeException("No credentials found")));
    }

    public Mono<Boolean> testDB() {
        return credentialsRepository.count()
                .map(count -> true)
                .doOnError(Throwable::printStackTrace);
    }
}