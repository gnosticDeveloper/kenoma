package raum.backup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import raum.openbao.OpenBaoService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * DR-only decorator around {@link S3ArtifactStore}: encrypts content via Vault's transit engine
 * ({@link OpenBaoService#encrypt}/{@link OpenBaoService#decrypt}) before upload and after download,
 * and refuses to ever hand out a presigned link - DR backups (unlike tenant exports, which use the
 * plain {@link S3ArtifactStore} directly) must never be directly downloadable.
 */
@Slf4j
@Component("encryptingArtifactStore")
public class EncryptingArtifactStore implements ArtifactStore {

    private final S3ArtifactStore delegate;
    private final OpenBaoService openBaoService;

    public EncryptingArtifactStore(@Qualifier("s3ArtifactStore") S3ArtifactStore delegate, OpenBaoService openBaoService) {
        this.delegate = delegate;
        this.openBaoService = openBaoService;
    }

    @Override
    public Mono<Void> upload(String key, Path file) {
        return Mono.fromCallable(() -> Files.readAllBytes(file))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(openBaoService::encrypt)
                .flatMap(ciphertext -> Mono.fromCallable(() -> writeCiphertext(ciphertext))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(encryptedFile -> delegate.upload(key, encryptedFile)
                        .doFinally(signal -> deleteQuietly(encryptedFile)));
    }

    @Override
    public Mono<String> presign(String key) {
        return Mono.error(new UnsupportedOperationException(
                "DR backups are never downloadable - refusing to presign key " + key));
    }

    @Override
    public Mono<Path> download(String key) {
        return delegate.download(key)
                .flatMap(encryptedFile -> Mono.fromCallable(() -> readCiphertext(encryptedFile))
                        .subscribeOn(Schedulers.boundedElastic())
                        .doFinally(signal -> deleteQuietly(encryptedFile)))
                .flatMap(openBaoService::decrypt)
                .flatMap(plaintext -> Mono.fromCallable(() -> writePlaintext(plaintext))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private Path writeCiphertext(String ciphertext) throws IOException {
        Path file = Files.createTempFile("dr-backup-enc-", ".enc");
        Files.writeString(file, ciphertext, StandardCharsets.UTF_8);
        return file;
    }

    private String readCiphertext(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private Path writePlaintext(byte[] plaintext) throws IOException {
        Path file = Files.createTempFile("dr-backup-dec-", ".bin");
        Files.write(file, plaintext);
        return file;
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete temp DR encryption artifact {}", file, e);
        }
    }
}
