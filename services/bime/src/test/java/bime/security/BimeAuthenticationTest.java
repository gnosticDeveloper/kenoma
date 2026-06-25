package bime.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BimeAuthenticationTest {

    private static final UUID SERVICE_ID = UUID.randomUUID();
    private static final UUID ORG_ID     = UUID.randomUUID();
    private static final UUID USER_ID    = UUID.randomUUID();

    private BimeAuthentication auth(String... roles) {
        Map<String, List<String>> roleMap = Map.of(SERVICE_ID.toString(), List.of(roles));
        return new BimeAuthentication(ORG_ID, USER_ID, roleMap, SERVICE_ID, "token");
    }

    private List<String> authorityNames(BimeAuthentication a) {
        return a.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .sorted()
                .toList();
    }

    @Test
    void bimeAdmin_hasAllThreePermissions() {
        List<String> authorities = authorityNames(auth("BIME_ADMIN"));
        assertThat(authorities).containsExactlyInAnyOrder(
                "BIME_MANAGE", "BIME_VIEW", "BIME_VIEW_CATALOG");
    }

    @Test
    void bimeManager_hasAllThreePermissions() {
        List<String> authorities = authorityNames(auth("BIME_MANAGER"));
        assertThat(authorities).containsExactlyInAnyOrder(
                "BIME_MANAGE", "BIME_VIEW", "BIME_VIEW_CATALOG");
    }

    @Test
    void bimeViewer_hasViewAndCatalog() {
        List<String> authorities = authorityNames(auth("BIME_VIEWER"));
        assertThat(authorities).containsExactlyInAnyOrder(
                "BIME_VIEW", "BIME_VIEW_CATALOG");
        assertThat(authorities).doesNotContain("BIME_MANAGE");
    }

    @Test
    void bimeUser_hasCatalogOnly() {
        List<String> authorities = authorityNames(auth("BIME_USER"));
        assertThat(authorities).containsExactly("BIME_VIEW_CATALOG");
    }

    @Test
    void unknownRole_hasNoPermissions() {
        Collection<? extends GrantedAuthority> authorities = auth("BIME_SUPERUSER").getAuthorities();
        assertThat(authorities).isEmpty();
    }

    @Test
    void roleForDifferentService_hasNoPermissions() {
        UUID otherService = UUID.randomUUID();
        Map<String, List<String>> roleMap = Map.of(
                otherService.toString(), List.of("BIME_ADMIN"));
        BimeAuthentication a = new BimeAuthentication(ORG_ID, USER_ID, roleMap, SERVICE_ID, "token");
        assertThat(a.getAuthorities()).isEmpty();
    }
}
