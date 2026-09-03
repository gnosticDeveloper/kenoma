package common.pool;

import common.grants.ServiceTier;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionPoolKeyTest {

    private static final UUID ORG_ID     = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SERVICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final ServiceTier TIER = ServiceTier.FULL;

    @Test
    void equalKeysAreEqual() {
        ConnectionPoolKey a = new ConnectionPoolKey(ORG_ID, SERVICE_ID, TIER);
        ConnectionPoolKey b = new ConnectionPoolKey(ORG_ID, SERVICE_ID, TIER);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void equalKeysHaveSameHashCode() {
        ConnectionPoolKey a = new ConnectionPoolKey(ORG_ID, SERVICE_ID, TIER);
        ConnectionPoolKey b = new ConnectionPoolKey(ORG_ID, SERVICE_ID, TIER);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void differentOrgIdProducesDifferentKey() {
        ConnectionPoolKey a = new ConnectionPoolKey(ORG_ID, SERVICE_ID, TIER);
        ConnectionPoolKey b = new ConnectionPoolKey(UUID.randomUUID(), SERVICE_ID, TIER);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void differentServiceIdProducesDifferentKey() {
        ConnectionPoolKey a = new ConnectionPoolKey(ORG_ID, SERVICE_ID, TIER);
        ConnectionPoolKey b = new ConnectionPoolKey(ORG_ID, UUID.randomUUID(), TIER);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void differentTierProducesDifferentKey() {
        ConnectionPoolKey a = new ConnectionPoolKey(ORG_ID, SERVICE_ID, ServiceTier.READONLY);
        ConnectionPoolKey b = new ConnectionPoolKey(ORG_ID, SERVICE_ID, ServiceTier.FULL);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void accessors() {
        ConnectionPoolKey key = new ConnectionPoolKey(ORG_ID, SERVICE_ID, TIER);
        assertThat(key.orgId()).isEqualTo(ORG_ID);
        assertThat(key.serviceId()).isEqualTo(SERVICE_ID);
        assertThat(key.tier()).isEqualTo(TIER);
    }
}
