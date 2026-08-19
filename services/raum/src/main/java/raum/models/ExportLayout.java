package raum.models;

/** Only meaningful for the JSON/CSV export formats - SQL always produces one restorable dump per
 * service (raum/vassago/bime), since a merged SQL script wouldn't be replayable against any single
 * database anyway. */
public enum ExportLayout {
    /** One file per service (today's original behavior). */
    SEPARATE,
    /** Every service's data combined into a single file, namespaced by service to avoid collisions
     * (e.g. two services could each have their own "users" table). */
    MERGED
}
