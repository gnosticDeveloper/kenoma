package common.grants;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the checked-in {@code scripts/openbao/generated-role-statements.json} in sync with
 * {@link GrantStatementRenderer}. That file feeds {@code kenoma-pre-init.sh} (a plain shell
 * container with no JVM) the exact per-tier OpenBao {@code creation_statements} raum renders
 * at runtime, with literal {@code __DBNAME__} / {@code __ORG_ID__} markers the script
 * substitutes per credentials row.
 *
 * <p>If a grant profile changes and this file goes stale, the test rewrites it and fails —
 * so "regenerate" is just re-running {@code mvn -pl services/common test}, then committing
 * the updated file.
 */
class GeneratedRoleStatementsFileTest {

    private static final String DB_MARKER = "__DBNAME__";
    private static final String ORG_MARKER = "__ORG_ID__";

    @Test
    void checkedInFileMatchesTheRenderer() throws IOException {
        Path repoRoot = Path.of("").toAbsolutePath().getParent().getParent();
        Path file = repoRoot.resolve("scripts/openbao/generated-role-statements.json");

        String expected = render();
        String actual = Files.exists(file) ? Files.readString(file) : "";

        if (!expected.equals(actual)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, expected);
            throw new AssertionError(
                    file + " was stale and has been regenerated. Re-run the build and commit it.");
        }
        assertThat(actual).isEqualTo(expected);
    }

    private static String render() {
        return "{\n"
                + serviceBlock("vassago", VassagoGrantProfile.PROFILE) + ",\n"
                + serviceBlock("bime", BimeGrantProfile.PROFILE) + "\n"
                + "}\n";
    }

    private static String serviceBlock(String key, ServiceGrantProfile profile) {
        List<String> tierLines = new ArrayList<>();
        for (ServiceTier tier : profile.supportedTiers()) {
            String statement = GrantStatementRenderer.creationStatements(profile, tier, DB_MARKER, ORG_MARKER);
            tierLines.add("    \"" + tier.roleSuffix() + "\": \"" + escape(statement) + "\"");
        }
        return "  \"" + key + "\": {\n" + String.join(",\n", tierLines) + "\n  }";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
