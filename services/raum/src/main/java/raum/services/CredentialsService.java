package raum.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
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

    public Mono<BasicCredentialDTO> saveNewCredentials(CredentialsDTO dto) {
        UUID orgId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        return credentialsRepository.save(Credentials.builder()
                        .orgId(orgId)
                        .serviceId(serviceId)
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
                        .thenReturn(new BasicCredentialDTO(saved.getOrgId(), saved.getServiceId()))
                );
    }

    public Mono<CredentialsDTO> getEphemeralCredentialsByOrgIdAndServiceId(BasicCredentialDTO dto) {
        return credentialsRepository.findByOrgIdAndServiceId(dto.getOrgId(), dto.getServiceId())
                .flatMap(credentials ->
                        openBaoService.issueEphemeralCredentials(credentials.getId())
                                .map(ephemeral -> CredentialsDTO.builder()
                                        .userName(ephemeral.getUserName())
                                        .password(ephemeral.getPassword())
                                        .dbHost(credentials.getDbHost())
                                        .dbPort(credentials.getDbPort())
                                        .dbName(credentials.getDbName())
                                        .dbEngine(credentials.getDbEngine())
                                        .build())
                )
                .switchIfEmpty(Mono.error(new RuntimeException("No credentials found")));
    }

    public Mono<Boolean> testDB() {
        return credentialsRepository.count()
                .map(count -> true)
                .doOnError(Throwable::printStackTrace);
    }
}