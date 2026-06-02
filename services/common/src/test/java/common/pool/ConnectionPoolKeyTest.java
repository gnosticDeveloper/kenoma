package common.pool;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionPoolKeyTest {

    private static final UUID ORG_ID     = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SERVICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void equalKeysAreEqual() {
        ConnectionPoolKey a = new ConnectionPoolKey(ORG_ID, SERVICE_ID);
        ConnectionPoolKey b = new ConnectionPoolKey(ORG_ID, SERVICE_ID);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void equalKeysHaveSameHashCode() {
        ConnectionPoolKey a = new ConnectionPoolKey(ORG_ID, SERVICE_ID);
        ConnectionPoolKey b = new ConnectionPoolKey(ORG_ID, SERVICE_ID);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void differentOrgIdProducesDifferentKey() {
        ConnectionPoolKey a = new ConnectionPoolKey(ORG_ID, SERVICE_ID);
        ConnectionPoolKey b = new ConnectionPoolKey(UUID.randomUUID(), SERVICE_ID);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void differentServiceIdProducesDifferentKey() {
        ConnectionPoolKey a = new ConnectionPoolKey(ORG_ID, SERVICE_ID);
        ConnectionPoolKey b = new ConnectionPoolKey(ORG_ID, UUID.randomUUID());
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void accessors() {
        ConnectionPoolKey key = new ConnectionPoolKey(ORG_ID, SERVICE_ID);
        assertThat(key.orgId()).isEqualTo(ORG_ID);
        assertThat(key.serviceId()).isEqualTo(SERVICE_ID);
    }
}