package bime.db;

import bime.security.BimeAuthentication;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.BiFunction;

@Service
@RequiredArgsConstructor
public class BimeContextService {

    private final BimeDbService bimeDbService;

    public <T> Mono<T> withHandle(BiFunction<BimeAuthentication, BimeDbHandle, Mono<T>> fn) {
        return getCaller()
                .flatMap(caller -> bimeDbService.getHandle(caller.getOrgId())
                        .flatMap(handle -> fn.apply(caller, handle)));
    }

    public <T> Flux<T> withHandleMany(BiFunction<BimeAuthentication, BimeDbHandle, Flux<T>> fn) {
        return getCaller()
                .flatMapMany(caller -> bimeDbService.getHandle(caller.getOrgId())
                        .flatMapMany(handle -> fn.apply(caller, handle)));
    }

    private Mono<BimeAuthentication> getCaller() {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (BimeAuthentication) ctx.getAuthentication());
    }
}
