package common.grants;

/**
 * A SQL table privilege that a tenant's ephemeral database role can be granted.
 *
 * <p>Declared in the order privileges are rendered into a {@code GRANT} statement, so
 * {@link java.util.EnumSet} iteration produces {@code SELECT, INSERT, UPDATE, DELETE}
 * deterministically.
 */
public enum Op {
    SELECT,
    INSERT,
    UPDATE,
    DELETE
}
