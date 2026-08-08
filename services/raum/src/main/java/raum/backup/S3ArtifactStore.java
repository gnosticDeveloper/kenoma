package raum.backup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import raum.openbao.OpenBaoService;
import raum.openbao.S3CredentialsDTO;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.nio.file.Path;

@Slf4j
@Component
public class S3ArtifactStore implements ArtifactStore {

    private final OpenBaoService openBaoService;

    public S3ArtifactStore(OpenBaoService openBaoService) {
        this.openBaoService = openBaoService;
    }

    @Override
    public Mono<Void> upload(String key, Path file) {
        return openBaoService.getBackupS3Credentials()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(creds -> putObject(creds, key, file))
                .then();
    }

    private void putObject(S3CredentialsDTO creds, String key, Path file) {
        try (S3Client client = buildClient(creds)) {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(creds.bucket())
                            .key(key)
                            .build(),
                    file);
            log.info("Uploaded DR backup artifact to s3://{}/{}", creds.bucket(), key);
        }
    }


    private S3Client buildClient(S3CredentialsDTO creds) {
        return S3Client.builder()
                .endpointOverride(URI.create(withScheme(creds.endpoint())))
                .region(Region.of("auto"))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(creds.accessKey(), creds.secretKey())))
                .build();
    }

    // The endpoint stored in OpenBao KV may or may not include a scheme depending on how it was
    // entered — tolerate both rather than failing on a bare host.
    private String withScheme(String endpoint) {
        return endpoint.contains("://") ? endpoint : "https://" + endpoint;
    }
}
