package vassago.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Represents a verified JWT principal in the reactive security context.
 *
 * <p>This is a placeholder showing the contract that {@link vassago.db.ConnectionPoolService}
 * expects when it calls {@code (VassagoAuthentication) ctx.getAuthentication()}.
 * The actual implementation will be filled in when the JWT filter chain is built.
 *
 * <p>The key requirement is that {@link #getOrgId()} returns the {@code orgId} claim
 * from the verified token — never from the request body or path.
 */
public class VassagoAuthentication implements Authentication {

    private final UUID orgId;
    private final String username;
    private final List<String> roles;
    private boolean authenticated = true;

    public VassagoAuthentication(UUID orgId, String username, List<String> roles) {
        this.orgId    = orgId;
        this.username = username;
        this.roles    = roles;
    }

    public UUID getOrgId() {
        return orgId;
    }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return List.of(); }
    @Override public Object getCredentials()  { return null; }
    @Override public Object getDetails()      { return null; }
    @Override public Object getPrincipal()    { return username; }
    @Override public boolean isAuthenticated(){ return authenticated; }
    @Override public void setAuthenticated(boolean b) { this.authenticated = b; }
    @Override public String getName()         { return username; }
}