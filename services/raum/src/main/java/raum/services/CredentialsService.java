package raum.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import raum.DTO.BasicCredentialDTO;
import raum.DTO.CredentialsDTO;
import raum.models.Credentials;
import raum.openbao.OpenBaoService;
import raum.repository.CredentialsRepository;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CredentialsService {

    private final CredentialsRepository credentialsRepository;
    private final OpenBaoService openBaoService;

    public Mono<BasicCredentialDTO> saveNewCredentials(Mono<CredentialsDTO> credentialMono) {
        return credentialMono.flatMap(dto ->
                Mono.zip(
                        openBaoService.encrypt(dto.getUserName()),
                        openBaoService.encrypt(dto.getPassword())
                ).flatMap(tuple ->
                        credentialsRepository.save(Credentials.builder()
                                //random ids used here until appropriate service is implemented
                                .orgId(UUID.randomUUID())
                                .serviceId(UUID.randomUUID())
                                .dbEngine(dto.getDbEngine())
                                .dbHost(dto.getDbHost())
                                .dbPort(dto.getDbPort())
                                .dbName(dto.getDbName())
                                .userName(tuple.getT1())
                                .encryptedPassword(tuple.getT2())
                                .createdAt(Instant.now())
                                .modifiedAt(Instant.now())
                                .build())
                ).map(saved -> new BasicCredentialDTO(saved.getOrgId(), saved.getServiceId()))
        );
    }

    public Mono<CredentialsDTO> getEphemeralCredentialsByOrgIdAndServiceId(BasicCredentialDTO dto) {
        return credentialsRepository.findByOrgIdAndServiceId(dto.getOrgId(), dto.getServiceId())
                .flatMap(credentials -> {
                    String userCiphertext = new String(credentials.getUserName(), StandardCharsets.UTF_8);
                    String passCiphertext = new String(credentials.getEncryptedPassword(), StandardCharsets.UTF_8);

                    return Mono.zip(
                            openBaoService.decrypt(userCiphertext),
                            openBaoService.decrypt(passCiphertext)
                            ).flatMap(tuple ->
                            openBaoService.issueEphemeralCredentials(
                                    tuple.getT1(),
                                    tuple.getT2(),
                                    credentials.getDbHost(),
                                    credentials.getDbPort(),
                                    credentials.getDbName()
                            ).map(ephemeral -> {
                                ephemeral.setDbHost(credentials.getDbHost());
                                ephemeral.setDbPort(credentials.getDbPort());
                                ephemeral.setDbName(credentials.getDbName());
                                ephemeral.setDbEngine(credentials.getDbEngine());
                                return ephemeral;
                            }));

                })
                .switchIfEmpty(Mono.error(new RuntimeException("No credentials found")));
    }


    public Mono<Boolean> testDB() {
        return credentialsRepository.count()
                .map(count -> true)
                .doOnError(Throwable::printStackTrace);
    }
}