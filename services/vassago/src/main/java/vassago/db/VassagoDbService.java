package vassago.db;

import common.dto.BasicCredentialDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import vassago.clients.RaumClient;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VassagoDbService {
    private final RaumClient raumClient;
    private final DatabaseConnectionService dbConnectionService;

    @Value("${vassago.service-id}")
    private String serviceId;

    public Mono<DatabaseClient> getClient(UUID orgId) {
        BasicCredentialDTO request = new BasicCredentialDTO();
        request.setOrgId(orgId);
        request.setServiceId(UUID.fromString(serviceId));
        return raumClient.getEphemeralCredentials(request)
                .map(dbConnectionService::createReactiveClient);
    }
}