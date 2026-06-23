package vassago.security;

import lombok.Getter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class VassagoAuthentication implements Authentication {
    @Getter
    private final UUID orgId;
    private final UUID userId;
    @Getter
    private final Map<String, List<String>> roles;
    private final UUID serviceId;
    @Getter
    private final String jti;
    @Getter
    private final Instant expiry;
    private boolean authenticated = true;

    public VassagoAuthentication(UUID orgId, UUID userId, Map<String, List<String>> roles,
                                 UUID serviceId, String jti, Instant expiry) {
        this.orgId     = orgId;
        this.userId    = userId;
        this.roles     = roles;
        this.serviceId = serviceId;
        this.jti       = jti;
        this.expiry    = expiry;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.getOrDefault(serviceId.toString(), List.of())
                .stream()
                .flatMap(roleName -> Arrays.stream(VassagoRole.values())
                        .filter(r -> r.name().equals(roleName))
                        .findFirst()
                        .map(VassagoRole::getPermissions)
                        .orElse(Set.of())
                        .stream()
                )
                .distinct()
                .map(p -> new SimpleGrantedAuthority(p.name()))
                .collect(Collectors.toList());
    }

    @Override public Object getCredentials()           { return null; }
    @Override public Object getDetails()               { return null; }
    @Override public Object getPrincipal()             { return getName(); }
    @Override public boolean isAuthenticated()         { return authenticated; }
    @Override public void setAuthenticated(boolean b)  { this.authenticated = b; }
    public UUID getId()                                { return userId; }
    @Override public String getName()                  { return userId.toString(); }
}