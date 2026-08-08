package raum.backup;

import reactor.core.publisher.Mono;

import java.nio.file.Path;

/** Storage-agnostic sink for backup artifacts, keyed by object key. */
public interface ArtifactStore {
    Mono<Void> upload(String key, Path file);
}
