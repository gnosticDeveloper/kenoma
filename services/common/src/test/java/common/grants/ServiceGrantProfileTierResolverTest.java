package common.grants;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceGrantProfileTierResolverTest {

    private final ServiceGrantProfile bime = BimeGrantProfile.PROFILE;
    private final ServiceGrantProfile vassago = VassagoGrantProfile.PROFILE;

    @Test
    void bimeSingleRoleMapping() {
        assertThat(bime.resolveTier(List.of("BIME_ADMIN"))).isEqualTo(ServiceTier.FULL);
        assertThat(bime.resolveTier(List.of("BIME_STOCK_OPERATOR"))).isEqualTo(ServiceTier.OPERATIONS);
        assertThat(bime.resolveTier(List.of("BIME_TRANSFER_APPROVER"))).isEqualTo(ServiceTier.OPERATIONS);
        assertThat(bime.resolveTier(List.of("BIME_CASHIER"))).isEqualTo(ServiceTier.SALES);
        assertThat(bime.resolveTier(List.of("BIME_VIEWER"))).isEqualTo(ServiceTier.READONLY);
        assertThat(bime.resolveTier(List.of("BIME_CATALOG_VIEWER"))).isEqualTo(ServiceTier.CATALOG);
    }

    @Test
    void bimeMultiRoleResolvesToStrongest() {
        assertThat(bime.resolveTier(List.of("BIME_CATALOG_VIEWER", "BIME_CASHIER")))
                .isEqualTo(ServiceTier.SALES);
        assertThat(bime.resolveTier(List.of("BIME_CASHIER", "BIME_TRANSFER_APPROVER")))
                .isEqualTo(ServiceTier.OPERATIONS);
        assertThat(bime.resolveTier(List.of("BIME_VIEWER", "BIME_ADMIN", "BIME_CASHIER")))
                .isEqualTo(ServiceTier.FULL);
    }

    @Test
    void bimeIgnoresUnknownRoleNamesAlongsideKnownOnes() {
        assertThat(bime.resolveTier(List.of("VASSAGO_ADMIN", "BIME_VIEWER", "ROLE_NOT_A_THING")))
                .isEqualTo(ServiceTier.READONLY);
    }

    @Test
    void bimeNoMappableRoleThrows() {
        assertThatThrownBy(() -> bime.resolveTier(List.of("VASSAGO_ADMIN")))
                .isInstanceOf(NoTierForRolesException.class);
        assertThatThrownBy(() -> bime.resolveTier(List.of()))
                .isInstanceOf(NoTierForRolesException.class);
    }

    @Test
    void vassagoRolesAllResolveToFull() {
        assertThat(vassago.resolveTier(List.of("VASSAGO_ADMIN"))).isEqualTo(ServiceTier.FULL);
        assertThat(vassago.resolveTier(List.of("VASSAGO_MEMBER"))).isEqualTo(ServiceTier.FULL);
    }

    @Test
    void genericProfileHasNoRoleMappings() {
        ServiceGrantProfile generic = ServiceGrantProfiles.forServiceName("SomeFutureService");
        assertThatThrownBy(() -> generic.resolveTier(List.of("ANYTHING")))
                .isInstanceOf(NoTierForRolesException.class);
    }

    @Test
    void forServiceNameIsCaseInsensitive() {
        assertThat(ServiceGrantProfiles.forServiceName("bime")).isSameAs(BimeGrantProfile.PROFILE);
        assertThat(ServiceGrantProfiles.forServiceName("VASSAGO")).isSameAs(VassagoGrantProfile.PROFILE);
    }

    @Test
    void supportedTiersAreWeakestFirst() {
        assertThat(bime.supportedTiers()).containsExactly(
                ServiceTier.CATALOG, ServiceTier.READONLY, ServiceTier.SALES,
                ServiceTier.OPERATIONS, ServiceTier.FULL);
        assertThat(vassago.supportedTiers()).containsExactly(ServiceTier.READONLY, ServiceTier.FULL);
    }
}
