package vassago.db;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Entry point for all database access in Vassago.
 *
 * <p>Delegates to {@link ConnectionPoolService}. The {@code orgId} is passed in
 * from the caller for now and will be replaced by JWT context extraction once
 * the security layer is implemented.
 */
@Service
@RequiredArgsConstructor
public class VassagoDbService {

    private final ConnectionPoolService connectionPoolService;

    public Mono<DatabaseClient> getClient(UUID orgId) {
        return connectionPoolService.getClient(orgId);
    }
}