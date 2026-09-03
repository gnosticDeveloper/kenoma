package common.grants;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GrantFragmentCompositionTest {

    private static GrantSpec specFor(Set<GrantSpec> specs, String table) {
        return specs.stream().filter(s -> s.table().equals(table)).findFirst().orElseThrow();
    }

    @Test
    void unionMergesOpsForSameTable() {
        Set<GrantSpec> merged = GrantFragment.union(
                GrantFragment.select("a"),
                GrantFragment.insert("a"),
                GrantFragment.insertUpdate("a"));

        assertThat(merged).hasSize(1);
        assertThat(specFor(merged, "a").ops())
                .containsExactly(Op.SELECT, Op.INSERT, Op.UPDATE);
    }

    @Test
    void unionKeepsDistinctTablesSeparate() {
        Set<GrantSpec> merged = GrantFragment.union(
                GrantFragment.select("a", "b"),
                GrantFragment.writes("b", "c"));

        assertThat(merged).extracting(GrantSpec::table).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(specFor(merged, "b").ops())
                .containsExactly(Op.SELECT, Op.INSERT, Op.UPDATE, Op.DELETE);
    }

    @Test
    void tableWideGrantSubsumesColumnScopedForSameTable() {
        Set<GrantSpec> merged = GrantFragment.union(
                GrantFragment.selectColumns("users", List.of("id", "email")),
                GrantFragment.select("users"));

        GrantSpec users = specFor(merged, "users");
        assertThat(users.isColumnScoped()).isFalse();
        assertThat(users.columns()).isEmpty();
    }

    @Test
    void columnScopedGrantsUnionTheirColumns() {
        Set<GrantSpec> merged = GrantFragment.union(
                GrantFragment.selectColumns("users", List.of("id", "email")),
                GrantFragment.selectColumns("users", List.of("email", "username")));

        assertThat(specFor(merged, "users").columns())
                .containsExactly("id", "email", "username");
    }

    @Test
    void opsIterationOrderIsEnumOrderRegardlessOfInsertionOrder() {
        GrantSpec spec = GrantSpec.of("t", Op.DELETE, Op.SELECT, Op.UPDATE, Op.INSERT);
        assertThat(spec.ops()).containsExactly(Op.SELECT, Op.INSERT, Op.UPDATE, Op.DELETE);
    }
}
