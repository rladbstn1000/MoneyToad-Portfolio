package com.potg.don.maintenance;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Application JDBC boundary only: driver-internal session/metadata SQL stays inside the driver.
 * A client read-only hint is not a server guarantee. Only these reviewed SELECTs and metadata
 * reads can cross the VERIFY/DRY_RUN boundary; APPLY never uses this wrapper. */
final class CleanupReadOnlyGuard {
    private CleanupReadOnlyGuard() { }

    private static final Set<String> SELECTS = Set.of(
        "SELECT table_name, engine FROM information_schema.tables WHERE table_schema=? AND table_name IN ('demo_capacity','demo_visit','demo_admission_lock')",
        "SELECT data_type,datetime_precision FROM information_schema.columns WHERE table_schema=? AND table_name='demo_visit' AND column_name IN ('created_at','session_expires_at')",
        "SELECT TABLE_NAME,TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()",
        "SELECT k.TABLE_NAME,k.COLUMN_NAME,k.REFERENCED_TABLE_NAME,k.REFERENCED_COLUMN_NAME,r.DELETE_RULE,r.UPDATE_RULE,k.REFERENCED_TABLE_SCHEMA,k.CONSTRAINT_SCHEMA "
            + "FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r "
            + "ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.TABLE_NAME=k.TABLE_NAME AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME "
            + "WHERE (k.CONSTRAINT_SCHEMA=DATABASE() AND k.TABLE_NAME IN ('users','cards','transactions','budgets','demo_visit')) "
            + "OR (k.REFERENCED_TABLE_SCHEMA=DATABASE() AND k.REFERENCED_TABLE_NAME IN ('users','cards','transactions','budgets','demo_visit'))",
        "SELECT id,max_visitors,(SELECT COUNT(*) FROM demo_visit),(SELECT COUNT(*) FROM demo_admission_lock),"
            + "(SELECT MIN(id) FROM demo_admission_lock),(SELECT MAX(id) FROM demo_admission_lock) FROM demo_capacity",
        "SELECT UTC_TIMESTAMP(6)",
        "SELECT user_id,created_at,scenario_version,session_expires_at FROM demo_visit WHERE created_at<=? AND session_expires_at<? ORDER BY created_at,user_id LIMIT ?",
        "SELECT id,file_id FROM users WHERE id=?",
        "SELECT id,card_no,cvc FROM cards WHERE user_id=? ORDER BY id",
        "SELECT id,transaction_date_time,amount,merchant_name FROM transactions WHERE card_id=? ORDER BY id",
        "SELECT id,budget_date,category,initial_amount,initial_file_id,predicted_at FROM budgets WHERE user_id=? ORDER BY id",
        "SELECT COUNT(*) FROM analysis_job WHERE user_id=?"
    ).stream().map(CleanupReadOnlyGuard::normalize).collect(Collectors.toUnmodifiableSet());
    private static final Set<String> DEMO_TABLES = Set.of("demo_capacity", "demo_visit", "demo_admission_lock");
    private static final Set<String> PARAMETER_SETTERS = Set.of("setString", "setInt", "setLong", "setObject");
    private static final Set<String> RESULT_READS = Set.of("next", "getString", "getInt", "getLong", "getShort", "getObject", "wasNull", "close", "isClosed");
    private static final Set<Class<?>> OBJECT_TYPES = Set.of(Integer.class, Long.class, String.class, LocalDate.class, LocalDateTime.class);

    static Connection wrap(Connection delegate) throws SQLException {
        String catalog = delegate.getCatalog();
        if (catalog == null || catalog.isBlank()) throw rejected();
        return proxy(Connection.class, (self, method, args) -> {
            String name = method.getName();
            if (name.equals("isWrapperFor")) return false;
            if (name.equals("prepareStatement")) {
                if (args == null || args.length != 1 || !(args[0] instanceof String sql) || !allowedSelect(sql)) throw rejected();
                return statement((PreparedStatement) invoke(delegate, method, args), (Connection) self);
            }
            if (name.equals("getMetaData")) return metadata(delegate.getMetaData(), (Connection) self, catalog);
            if (Set.of("getCatalog", "getAutoCommit", "getTransactionIsolation", "isReadOnly", "rollback", "close", "isClosed").contains(name)
                && (args == null || args.length == 0)) return invoke(delegate, method, args);
            throw rejected();
        });
    }

    static boolean allowedSelect(String sql) {
        return sql != null && sql.length() <= 8192 && SELECTS.contains(normalize(sql));
    }
    private static String normalize(String sql) { return sql.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT); }

    private static PreparedStatement statement(PreparedStatement delegate, Connection owner) {
        return proxy(PreparedStatement.class, (self, method, args) -> {
            String name = method.getName();
            if (name.equals("isWrapperFor")) return false;
            if (name.equals("getConnection")) return owner;
            if (name.equals("executeQuery") && (args == null || args.length == 0))
                return result(delegate.executeQuery(), (PreparedStatement) self);
            if (PARAMETER_SETTERS.contains(name) && args != null && args.length == 2 && args[0] instanceof Integer)
                return invoke(delegate, method, args);
            if (Set.of("close", "isClosed", "clearParameters").contains(name) && (args == null || args.length == 0))
                return invoke(delegate, method, args);
            throw rejected();
        });
    }

    private static DatabaseMetaData metadata(DatabaseMetaData delegate, Connection owner, String catalog) {
        return proxy(DatabaseMetaData.class, (self, method, args) -> {
            String name = method.getName();
            if (name.equals("isWrapperFor")) return false;
            if (name.equals("getConnection")) return owner;
            if (!Set.of("getColumns", "getPrimaryKeys", "getImportedKeys", "getIndexInfo").contains(name)
                || args == null || args.length < 3 || !catalog.equals(args[0]) || args[1] != null
                || !DEMO_TABLES.contains(args[2])) throw rejected();
            if (name.equals("getColumns") && (args.length != 4 || args[3] != null)) throw rejected();
            if (name.equals("getIndexInfo") && (args.length != 5 || !Boolean.FALSE.equals(args[3]) || !Boolean.FALSE.equals(args[4]))) throw rejected();
            if (Set.of("getPrimaryKeys", "getImportedKeys").contains(name) && args.length != 3) throw rejected();
            // Connector/J metadata implementations may issue their own internal statements.
            // Only the returned rows cross this boundary, never the internal statement/connection.
            return result((ResultSet) invoke(delegate, method, args), null);
        });
    }

    private static ResultSet result(ResultSet delegate, PreparedStatement owner) {
        return proxy(ResultSet.class, (self, method, args) -> {
            String name = method.getName();
            if (name.equals("isWrapperFor")) return false;
            if (name.equals("getStatement")) return owner;
            if (name.equals("getMetaData")) return resultMetadata(delegate.getMetaData());
            if (!RESULT_READS.contains(name)) throw rejected();
            if (name.equals("getObject") && args != null && args.length > 1
                && (!(args[1] instanceof Class<?> type) || !OBJECT_TYPES.contains(type))) throw rejected();
            Object value = invoke(delegate, method, args);
            if (value instanceof Connection || value instanceof java.sql.Statement || value instanceof ResultSet
                || value instanceof java.sql.Wrapper || value instanceof java.sql.Blob || value instanceof java.sql.Clob
                || value instanceof java.sql.Array || value instanceof java.sql.Ref || value instanceof java.sql.SQLXML) throw rejected();
            return value;
        });
    }

    private static ResultSetMetaData resultMetadata(ResultSetMetaData delegate) {
        return proxy(ResultSetMetaData.class, (self, method, args) -> {
            if (method.getName().equals("isWrapperFor")) return false;
            if (!(method.getName().startsWith("get") || method.getName().startsWith("is"))) throw rejected();
            return invoke(delegate, method, args);
        });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }
    @FunctionalInterface private interface Call { Object invoke(Object self, Method method, Object[] args) throws Throwable; }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "toString" -> "CleanupReadOnlyGuard";
                case "hashCode" -> System.identityHashCode(self);
                case "equals" -> self == args[0];
                default -> throw rejected();
            };
            return call.invoke(self, method, args);
        });
    }
    private static CleanupFailure rejected() { return new CleanupFailure(CleanupFailure.Code.READ_ONLY_GUARD_REJECTED); }
}
