package vassago.services;

import common.dto.BasicCredentialDTO;
import common.dto.CredentialsDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import vassago.clients.RaumClient;
import vassago.db.DatabaseConnectionService;

@Service
@RequiredArgsConstructor
public class UserService {

    private final RaumClient client;
    private final DatabaseConnectionService dbConnectionService;

    public Mono<CredentialsDTO> test(BasicCredentialDTO requestDTO) {
        return client.getEphemeralCredentials(requestDTO);
    }

    public Mono<DatabaseClient> getClientWithEphemeralCredentials(BasicCredentialDTO requestDTO) {
        return client.getEphemeralCredentials(requestDTO)
                .map(dbConnectionService::createReactiveClient);
    }
}