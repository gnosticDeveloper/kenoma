package bime.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.*;
import java.util.stream.Collectors;

public class BimeAuthentication implements Authentication {

    private final UUID orgId;
    private final UUID userId;
    private final Map<String, List<String>> roles;
    private final UUID serviceId;
    private final String jwtToken;
    private boolean authenticated = true;

    public BimeAuthentication(UUID orgId, UUID userId, Map<String, List<String>> roles, UUID serviceId, String jwtToken) {
        this.orgId     = orgId;
        this.userId    = userId;
        this.roles     = roles;
        this.serviceId = serviceId;
        this.jwtToken  = jwtToken;
    }

    public UUID getOrgId()                      { return orgId; }
    public UUID getId()                         { return userId; }
    public Map<String, List<String>> getRoles() { return roles; }
    public String getJwtToken()                 { return jwtToken; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.getOrDefault(serviceId.toString(), List.of())
                .stream()
                .flatMap(roleName -> Arrays.stream(BimeRole.values())
                        .filter(r -> r.name().equals(roleName))
                        .findFirst()
                        .map(BimeRole::getPermissions)
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
    @Override public String getName()                  { return userId.toString(); }
}
