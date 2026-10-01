package com.potg.verification.cleanupremote;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.Set;

/** Finite command-equivalents, not provider billing. No SQL/arguments are retained. */
final class CleanupSqlBudget {
    static final int WORK_LIMIT = 1200, CLEANUP_LIMIT = 300, CONNECTION_LIMIT = 2;
    static final int CONNECTION_RESERVE = 32, METADATA_RESERVE = 8;
    int work, cleanup, connections, dml, deletes, updates, ddl, commits, rollbacks, metadataCalls, unsupported1235;
    int nowaitVendor;
    String nowaitState = "NOT_OBSERVED";
    boolean cleaning;
    volatile boolean cancelled;
    java.util.function.BiConsumer<Integer, String> sqlFailureObserver = (vendor, state) -> { };
    java.util.function.BiConsumer<Boolean,Integer> charged = (cleaning,count) -> { };
    final long deadline;
    long cleanupDeadline;
    CleanupSqlBudget(long deadline) { this.deadline = deadline; }
    synchronized void spend(int count) {
        if (count < 1) throw new Rejected("BUDGET_INVALID");
        if (cleaning) {
            if (cleanup + count > CLEANUP_LIMIT || System.currentTimeMillis() > cleanupDeadline) throw new Rejected("CLEANUP_BUDGET_OR_DEADLINE");
            cleanup += count;
        } else {
            if (cancelled || work + count > WORK_LIMIT || System.currentTimeMillis() > deadline) throw new Rejected("WORK_BUDGET_OR_DEADLINE");
            work += count;
        }
        charged.accept(cleaning,count);
    }
    void opening() { if (connections >= CONNECTION_LIMIT) throw new Rejected("CONNECTION_LIMIT"); connections++; spend(CONNECTION_RESERVE); }
    void startCleanup() { if (cleaning) return; cleaning = true; cleanupDeadline = System.currentTimeMillis() + 90_000; }
    Connection observe(Connection raw) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            String name = method.getName();
            if (name.equals("unwrap")) throw new SQLException("UNWRAP_REJECTED");
            if (name.equals("isWrapperFor")) return false;
            if (Set.of("setAutoCommit", "setReadOnly", "setTransactionIsolation", "setCatalog", "commit", "rollback", "getTransactionIsolation", "getAutoCommit", "isReadOnly", "getCatalog").contains(name)) spend(1);
            Object value;
            try { value = invoke(raw, method, args); }
            catch (SQLException error) { recordSqlFailure(error); throw error; }
            if(name.equals("commit"))commits++;
            if(name.equals("rollback"))rollbacks++;
            if (value instanceof Statement statement && (name.equals("prepareStatement") || name.equals("createStatement"))) {
                return statement(statement, name.equals("prepareStatement") ? (String) args[0] : null, (Connection) proxy);
            }
            if (name.equals("getMetaData")) return metadata((DatabaseMetaData) value, (Connection) proxy);
            return value;
        });
    }
    private Object metadata(DatabaseMetaData raw, Connection connection) {
        return Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class}, (proxy, method, args) -> {
            if (method.getName().equals("getConnection")) return connection;
            if (method.getName().equals("unwrap")) throw new SQLException("UNWRAP_REJECTED");
            if (method.getName().equals("isWrapperFor")) return false;
            if (ResultSet.class.isAssignableFrom(method.getReturnType())) {
                if (!Set.of("getColumns", "getPrimaryKeys", "getImportedKeys", "getIndexInfo").contains(method.getName())) throw new Rejected("UNREVIEWED_METADATA");
                spend(METADATA_RESERVE); metadataCalls++;
            } else if (method.getName().startsWith("get")) spend(1);
            try { return invoke(raw, method, args); }
            catch (SQLException error) { recordSqlFailure(error); throw error; }
        });
    }
    private Object statement(Statement raw, String prepared, Connection connection) {
        Class<?> type = raw instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            String name = method.getName();
            if (name.equals("unwrap")) throw new SQLException("UNWRAP_REJECTED");
            if (name.equals("getConnection")) return connection;
            if (name.equals("isWrapperFor")) return false;
            if (name.contains("Batch") || name.equals("addBatch")) throw new Rejected("BATCH_DISABLED");
            String sql = prepared;
            if (name.startsWith("execute")) {
                if (sql == null && args != null && args.length > 0 && args[0] instanceof String text) sql = text;
                if (sql == null) throw new Rejected("UNREVIEWED_EXECUTION");
                spend(1);
                String normalized = sql.stripLeading().toUpperCase(java.util.Locale.ROOT);
                if (normalized.startsWith("INSERT ") || normalized.startsWith("UPDATE ") || normalized.startsWith("DELETE ")) dml++;
                if (normalized.startsWith("DELETE ")) deletes++;
                if (normalized.startsWith("UPDATE ")) updates++;
                if(normalized.matches("^(CREATE|ALTER|DROP|TRUNCATE) .*"))ddl++;
            }
            try { return invoke(raw, method, args); }
            catch (SQLException error) {
                recordSqlFailure(error);
                if (sql != null && sql.contains("FOR UPDATE NOWAIT")) { nowaitVendor = error.getErrorCode(); nowaitState = error.getSQLState() == null ? "NOT_OBSERVED" : error.getSQLState(); }
                throw error;
            }
        });
    }
    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); } catch (InvocationTargetException error) { throw error.getCause(); }
    }
    private void recordSqlFailure(SQLException error) {
        if(error.getErrorCode()==1235&&"42000".equals(error.getSQLState()))unsupported1235++;
        sqlFailureObserver.accept(error.getErrorCode(),safeState(error.getSQLState()));
    }
    static String safeState(String value) { return value != null && value.matches("[A-Z0-9]{5}") ? value : "NOT_OBSERVED"; }
    static final class Rejected extends RuntimeException {
        Rejected(String code) { super(code, null, false, false); }
        Rejected(String code, Throwable cause) { super(code, cause, false, false); }
    }
}
