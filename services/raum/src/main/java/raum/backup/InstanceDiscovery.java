package raum.backup;

import raum.models.Credentials;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reduces raum's full {@code credentials} table down to one representative row per distinct
 * physical (db_host, db_port, db_name) - the unit both DR backups and schema migrations operate
 * on, since every org/service credential row sharing that key points at the same physical
 * database. Shared by {@link DrBackupScheduler} and {@code raum.migration.MigrationRunner}.
 */
public final class InstanceDiscovery {

    private InstanceDiscovery() {
    }

    public static List<Instance> discoverInstances(List<Credentials> all) {
        Map<String, Instance> byKey = new LinkedHashMap<>();
        for (Credentials c : all) {
            String key = c.getDbHost() + ":" + c.getDbPort() + "/" + c.getDbName();
            byKey.putIfAbsent(key, new Instance(key, c));
        }
        return List.copyOf(byKey.values());
    }

    public record Instance(String key, Credentials representative) {}
}
