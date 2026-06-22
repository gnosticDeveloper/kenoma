package bime.db;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * Groups the two objects a Bime service method needs to do transactional work:
 * a {@link DatabaseClient} for executing SQL and a {@link TransactionalOperator}
 * for wrapping multi-statement units atomically.
 *
 * <p>Both are scoped to the same org's {@link io.r2dbc.spi.ConnectionFactory},
 * so the operator's transaction manager and the client draw from the same pool.
 */
public record BimeDbHandle(DatabaseClient client, TransactionalOperator tx) {}
