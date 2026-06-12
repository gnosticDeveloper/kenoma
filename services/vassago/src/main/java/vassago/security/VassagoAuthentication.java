package vassago.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class VassagoAuthentication implements Authentication {
    private final UUID orgId;
    private final String username;
    private final Map<String, List<String>> roles;
    private final UUID serviceId;
    private boolean authenticated = true;

    public VassagoAuthentication(UUID orgId, String username, Map<String, List<String>> roles, UUID serviceId) {
        this.orgId     = orgId;
        this.username  = username;
        this.roles     = roles;
        this.serviceId = serviceId;
    }

    public UUID getOrgId()                      { return orgId; }
    public Map<String, List<String>> getRoles() { return roles; }

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
    @Override public String getName()                  { return username; }
}