package vassago.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class VassagoAuthentication implements Authentication {

    private final UUID orgId;
    private final String username;
    private final Map<String, List<String>> roles;
    private boolean authenticated = true;

    public VassagoAuthentication(UUID orgId, String username, Map<String, List<String>> roles) {
        this.orgId    = orgId;
        this.username = username;
        this.roles    = roles;
    }

    public UUID getOrgId() { return orgId; }

    public Map<String, List<String>> getRoles() { return roles; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.values().stream()
                .flatMap(List::stream)
                .distinct()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }

    @Override public Object getCredentials()           { return null; }
    @Override public Object getDetails()               { return null; }
    @Override public Object getPrincipal()             { return getName(); }
    @Override public boolean isAuthenticated()         { return authenticated; }
    @Override public void setAuthenticated(boolean b)  { this.authenticated = b; }
    @Override public String getName()                  { return username; }
}