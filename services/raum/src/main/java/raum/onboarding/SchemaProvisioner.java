package raum.onboarding;

import common.dto.CredentialsDTO;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.util.StreamUtils;
import raum.openbao.OpenBaoService;
import raum.repository.CredentialsRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static io.r2dbc.spi.ConnectionFactoryOptions.*;

public final class SchemaProvisioner {

    private SchemaProvisioner() {
    }

    /**
     * Builds a client authenticated with the static (long-lived) credentials for a service's database,
     * targeting the same host/port/dbName as the given ephemeral credentials. Schema DDL must run under
     * these static credentials rather than an ephemeral role, since each onboarding call gets a different
     * ephemeral role and CREATE TABLE/INDEX IF NOT EXISTS requires ownership of any already-existing object.
     */
    public static Mono<DatabaseClient> staticClientFor(
            CredentialsRepository credentialsRepository, OpenBaoService openBaoService,
            UUID orgId, UUID serviceId, CredentialsDTO ephemeralCredentials) {
        return credentialsRepository.findByOrgIdAndServiceId(orgId, serviceId)
                .flatMap(entity -> openBaoService.getStaticCredentials(entity.getId()))
                .map(staticCredentials -> {
                    staticCredentials.setDbHost(ephemeralCredentials.getDbHost());
                    staticCredentials.setDbPort(ephemeralCredentials.getDbPort());
                    staticCredentials.setDbName(ephemeralCredentials.getDbName());
                    return buildClient(staticCredentials);
                });
    }

    public static DatabaseClient buildClient(CredentialsDTO credentials) {
        ConnectionFactoryOptions opts = ConnectionFactoryOptions.builder()
                .option(DRIVER, "postgresql")
                .option(HOST, credentials.getDbHost())
                .option(PORT, credentials.getDbPort())
                .option(USER, credentials.getUserName())
                .option(PASSWORD, credentials.getPassword())
                .option(DATABASE, credentials.getDbName())
                .build();
        return DatabaseClient.create(ConnectionFactories.get(opts));
    }

    public static Mono<Void> applySchema(DatabaseClient client, String schemaResource) {
        return Mono.fromCallable(() -> loadStatements(schemaResource))
                .flatMapMany(Flux::fromIterable)
                .concatMap(statement -> client.sql(statement).then())
                .then();
    }

    /**
     * Grants DML on all current tables in schema public to the given (already-issued) ephemeral role.
     * The ephemeral role's own creation_statements only grant access to tables that existed at the moment
     * OpenBao issued it — if schema provisioning creates new tables afterward, the role needs this explicit
     * re-grant (run via the static/superuser connection) before it can read or write those tables.
     */
    public static Mono<Void> grantDml(DatabaseClient staticClient, String username) {
        return staticClient.sql("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO \"" + username + "\"")
                .then();
    }

    private static List<String> loadStatements(String schemaResource) throws IOException {
        String sql = StreamUtils.copyToString(
                new ClassPathResource(schemaResource).getInputStream(), StandardCharsets.UTF_8);
        return Arrays.stream(sql.split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .toList();
    }
}
