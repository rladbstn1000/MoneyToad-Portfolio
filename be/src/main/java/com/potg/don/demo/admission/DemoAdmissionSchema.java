package com.potg.don.demo.admission;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure JDBC, shared with the maintenance executable. Never creates or repairs schema/data. */
public final class DemoAdmissionSchema {
    private DemoAdmissionSchema() { }

    public record Capacity(long visitCount, int maxVisitors) { }

    public static void verify(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = connection.getCatalog();
        if (catalog == null || catalog.isBlank()) throw invalid("DEMO_SCHEMA_CATALOG_INVALID");
        try (var statement = connection.prepareStatement("SELECT table_name, engine FROM information_schema.tables "
            + "WHERE table_schema=? AND table_name IN ('demo_capacity','demo_visit','demo_admission_lock')")) {
            statement.setString(1, catalog);
            try (var rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    count++;
                    if (!"InnoDB".equalsIgnoreCase(rows.getString("engine"))) throw invalid("DEMO_SCHEMA_ENGINE_INVALID");
                }
                if (count != 3) throw invalid("DEMO_SCHEMA_TABLE_COUNT_INVALID");
            }
        }
        columns(metadata, catalog, "demo_capacity", Map.of(
            "id", Types.TINYINT, "max_visitors", Types.INTEGER));
        columns(metadata, catalog, "demo_admission_lock", Map.of("id", Types.TINYINT));
        columns(metadata, catalog, "demo_visit", Map.of(
            "user_id", Types.BIGINT, "created_at", Types.TIMESTAMP, "scenario_version", Types.VARCHAR,
            "session_expires_at", Types.TIMESTAMP));
        // Connector/J's DECIMAL_DIGITS is not the DATETIME fractional-seconds contract.
        // Read the authoritative schema field and still require exactly DATETIME(6).
        try (var statement = connection.prepareStatement("SELECT data_type,datetime_precision "
            + "FROM information_schema.columns WHERE table_schema=? AND table_name='demo_visit' "
            + "AND column_name IN ('created_at','session_expires_at')")) {
            statement.setString(1, catalog);
            try (var rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    count++;
                    if (!"datetime".equalsIgnoreCase(rows.getString("data_type"))
                        || rows.getInt("datetime_precision") != 6 || rows.wasNull()) {
                        throw invalid("DEMO_SCHEMA_DATETIME_PRECISION_INVALID");
                    }
                }
                if (count != 2) throw invalid("DEMO_SCHEMA_DATETIME_PRECISION_INVALID");
            }
        }
        primaryKey(metadata, catalog, "demo_capacity", "id");
        primaryKey(metadata, catalog, "demo_visit", "user_id");
        primaryKey(metadata, catalog, "demo_admission_lock", "id");
        try (ResultSet rows = metadata.getImportedKeys(catalog, null, "demo_admission_lock")) {
            if (rows.next()) throw invalid("DEMO_SCHEMA_LOCK_FK_INVALID");
        }
        try (ResultSet rows = metadata.getImportedKeys(catalog, null, "demo_capacity")) {
            if (rows.next()) throw invalid("DEMO_SCHEMA_CAPACITY_FK_INVALID");
        }
        int foreignKeys = 0;
        try (ResultSet rows = metadata.getImportedKeys(catalog, null, "demo_visit")) {
            while (rows.next()) {
                foreignKeys++;
                if (!catalog.equals(rows.getString("PKTABLE_CAT"))
                    || !"users".equals(rows.getString("PKTABLE_NAME"))
                    || !"id".equals(rows.getString("PKCOLUMN_NAME"))
                    || !"user_id".equals(rows.getString("FKCOLUMN_NAME"))
                    || rows.getShort("KEY_SEQ") != 1
                    || !restrict(rows.getShort("DELETE_RULE"))
                    || !restrict(rows.getShort("UPDATE_RULE"))) throw invalid("DEMO_SCHEMA_VISIT_FK_INVALID");
            }
        }
        if (foreignKeys != 1) throw invalid("DEMO_SCHEMA_FK_COUNT_INVALID");
        Map<String, TreeMap<Integer, String>> indices = new HashMap<>();
        try (ResultSet rows = metadata.getIndexInfo(catalog, null, "demo_visit", false, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                if (name != null) indices.computeIfAbsent(name, ignored -> new TreeMap<>())
                    .put(rows.getInt("ORDINAL_POSITION"), rows.getString("COLUMN_NAME"));
            }
        }
        if (indices.values().stream().noneMatch(index ->
            List.copyOf(index.values()).equals(List.of("created_at", "user_id")))) throw invalid("DEMO_SCHEMA_INDEX_INVALID");
    }

    /** One statement gives one coherent snapshot, including during startup with active visitors. */
    public static Capacity verifySnapshot(Connection connection, int expectedMax) throws SQLException {
        if (expectedMax < 1) throw invalid("DEMO_CAPACITY_CONFIG_INVALID");
        String sql = "SELECT COUNT(*) AS rows_count, MIN(id) AS min_id, MAX(id) AS max_id, "
            + "MIN(max_visitors) AS maximum, "
            + "(SELECT COUNT(*) FROM demo_visit) AS visits, "
            + "(SELECT COUNT(*) FROM demo_admission_lock) AS lock_rows, "
            + "(SELECT MIN(id) FROM demo_admission_lock) AS lock_min_id, "
            + "(SELECT MAX(id) FROM demo_admission_lock) AS lock_max_id FROM demo_capacity";
        try (var statement = connection.prepareStatement(sql); var rows = statement.executeQuery()) {
            if (!rows.next() || rows.getLong("rows_count") != 1 || rows.getInt("min_id") != 1
                || rows.getInt("max_id") != 1) throw invalid("DEMO_CAPACITY_INTEGRITY_INVALID");
            if (rows.getLong("lock_rows") != 1 || rows.getInt("lock_min_id") != 1
                || rows.getInt("lock_max_id") != 1) throw invalid("DEMO_CAPACITY_LOCK_INTEGRITY_INVALID");
            long visits = rows.getLong("visits");
            int maximum = rows.getInt("maximum");
            if (maximum < 1 || maximum != expectedMax || visits < 0 || visits > maximum) throw invalid("DEMO_CAPACITY_INTEGRITY_INVALID");
            return new Capacity(visits, maximum);
        }
    }

    /** TiDB's row locking contract requires pessimistic mode; no session/global setting is modified. */
    public static void verifyTransactionMode(Connection connection) throws SQLException {
        if (connection.getAutoCommit() || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw invalid("DEMO_TRANSACTION_INVALID");
        }
        try (var statement = connection.prepareStatement("SELECT VERSION()"); var rows = statement.executeQuery()) {
            if (!rows.next()) throw invalid("DEMO_TRANSACTION_INVALID");
            if (!rows.getString(1).toLowerCase(java.util.Locale.ROOT).contains("tidb")) return;
        }
        try (var statement = connection.prepareStatement("SELECT @@tidb_txn_mode"); var rows = statement.executeQuery()) {
            if (!rows.next() || !"pessimistic".equalsIgnoreCase(rows.getString(1))) {
                throw invalid("DEMO_TRANSACTION_INVALID");
            }
        }
    }

    /** MySQL/TiDB ER_LOCK_NOWAIT only, never deadlock/timeout/connection failure. */
    public static boolean isNowaitConflict(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean recognized = false;
        for (Throwable next = failure; next != null && seen.add(next); next = next.getCause()) {
            if (next instanceof SQLException sql) {
                Set<SQLException> sqlSeen = Collections.newSetFromMap(new IdentityHashMap<>());
                for (SQLException item = sql; item != null && sqlSeen.add(item); item = item.getNextException()) {
                    if ((item.getErrorCode() != 0 && item.getErrorCode() != 3572)
                        || (item.getSQLState() != null && !"HY000".equals(item.getSQLState()))) return false;
                    recognized |= item.getErrorCode() == 3572 && "HY000".equals(item.getSQLState());
                }
            }
        }
        return recognized;
    }

    private static void columns(DatabaseMetaData metadata, String catalog, String table,
        Map<String, Integer> expected) throws SQLException {
        Map<String, Integer> found = new HashMap<>();
        try (ResultSet rows = metadata.getColumns(catalog, null, table, null)) {
            while (rows.next()) {
                if (!table.equals(rows.getString("TABLE_NAME"))) continue;
                String name = rows.getString("COLUMN_NAME");
                found.put(name, rows.getInt("DATA_TYPE"));
                if (rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls
                    || "YES".equals(rows.getString("IS_AUTOINCREMENT"))) throw invalid("DEMO_SCHEMA_COLUMN_FLAGS_INVALID");
                if ("scenario_version".equals(name) && rows.getInt("COLUMN_SIZE") != 16) {
                    throw invalid("DEMO_SCHEMA_VERSION_LENGTH_INVALID");
                }
            }
        }
        if (!found.equals(expected)) throw invalid("DEMO_SCHEMA_COLUMN_TYPES_INVALID");
    }

    private static void primaryKey(DatabaseMetaData metadata, String catalog, String table,
        String expected) throws SQLException {
        int count = 0;
        try (ResultSet rows = metadata.getPrimaryKeys(catalog, null, table)) {
            while (rows.next()) {
                count++;
                if (!expected.equals(rows.getString("COLUMN_NAME")) || rows.getShort("KEY_SEQ") != 1) {
                    throw invalid("DEMO_SCHEMA_PRIMARY_KEY_INVALID");
                }
            }
        }
        if (count != 1) throw invalid("DEMO_SCHEMA_PRIMARY_COUNT_INVALID");
    }

    private static boolean restrict(short rule) {
        return rule == DatabaseMetaData.importedKeyRestrict || rule == DatabaseMetaData.importedKeyNoAction;
    }
    private static SQLException invalid(String code) { return new SQLException(code, "HY000"); }
}
