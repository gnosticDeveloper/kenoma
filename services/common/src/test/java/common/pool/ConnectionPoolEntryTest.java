package common.pool;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConnectionPoolEntryTest {

    private static final ConnectionPoolKey KEY = new ConnectionPoolKey(
            UUID.randomUUID(), UUID.randomUUID());

    @Test
    void isExpired_returnsFalse_whenExpiryIsInFuture() {
        ConnectionPoolEntry entry = new ConnectionPoolEntry(
                KEY, mock(ConnectionFactory.class), Instant.now().plusSeconds(3600), "lease-1");
        assertThat(entry.isExpired()).isFalse();
    }

    @Test
    void isExpired_returnsTrue_whenExpiryIsInPast() {
        ConnectionPoolEntry entry = new ConnectionPoolEntry(
                KEY, mock(ConnectionFactory.class), Instant.now().minusSeconds(1), "lease-1");
        assertThat(entry.isExpired()).isTrue();
    }

    @Test
    void dispose_callsPoolDispose_whenFactoryIsConnectionPool() {
        ConnectionPool pool = mock(ConnectionPool.class);
        ConnectionPoolEntry entry = new ConnectionPoolEntry(
                KEY, pool, Instant.now().plusSeconds(3600), "lease-1");

        entry.dispose();

        verify(pool, times(1)).dispose();
    }

    @Test
    void dispose_doesNotThrow_whenFactoryIsNotConnectionPool() {
        ConnectionFactory plainFactory = mock(ConnectionFactory.class);
        ConnectionPoolEntry entry = new ConnectionPoolEntry(
                KEY, plainFactory, Instant.now().plusSeconds(3600), "lease-1");

        // Should complete without error — dispose() is a no-op for non-pool factories.
        entry.dispose();

        verifyNoInteractions(plainFactory);
    }

    @Test
    void accessors_returnConstructorValues() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Instant expiresAt = Instant.now().plusSeconds(3600);
        ConnectionPoolEntry entry = new ConnectionPoolEntry(KEY, factory, expiresAt, "lease-abc");

        assertThat(entry.key()).isEqualTo(KEY);
        assertThat(entry.connectionFactory()).isSameAs(factory);
        assertThat(entry.expiresAt()).isEqualTo(expiresAt);
        assertThat(entry.leaseId()).isEqualTo("lease-abc");
    }
}