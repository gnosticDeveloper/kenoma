package raum.backup;

import reactor.core.publisher.Mono;

import java.nio.file.Path;

/** Storage-agnostic sink for backup artifacts, keyed by object key. */
public interface ArtifactStore {
    Mono<Void> upload(String key, Path file);

    /** A short-lived, directly-downloadable URL for an already-uploaded key. */
    Mono<String> presign(String key);
}
