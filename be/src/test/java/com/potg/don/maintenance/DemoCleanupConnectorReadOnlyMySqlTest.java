package com.potg.don.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.potg.don.auth.OwnedDemoSchemaPreparation;
import com.potg.don.demo.seed.DemoSeedScenario;
import com.potg.don.maintenance.CleanupOptions.Mode;

/** Actual owned MySQL + Connector/J; only unsupported READ ONLY is a driver-level synthetic fault. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class DemoCleanupConnectorReadOnlyMySqlTest {
    private String url, username, password;
    private final DemoCleanupService service = new DemoCleanupService();

    @BeforeAll void prepareOwnedMysql() throws Exception {
        url = System.getenv("A1_DB_URL"); username = System.getenv("A1_DB_USERNAME"); password = System.getenv("A1_DB_PASSWORD");
        assertThat(url != null && username != null && password != null).as("Explicit owned runner inputs").isTrue();
        URI target = URI.create(url.substring(5));
        assertThat(url.startsWith("jdbc:mysql://") && "127.0.0.1".equals(target.getHost())
            && target.getPort() > 0 && target.getPort() != 3306 && target.getUserInfo() == null
            && target.getPath().matches("/moneytoad_a1_[a-z0-9_]+")
            && !url.toLowerCase(Locale.ROOT).contains("readonlypropagatestoserver"))
            .as("Disposable local schema; runtime URL has no maintenance-only option").isTrue();
        OwnedDemoSchemaPreparation.recreate(url, username, password, true);
        try (Connection c = open(false)) {
            assertThat(c.getMetaData().getDriverVersion().contains("9.4.0")).as("Actual Connector/J 9.4.0").isTrue();
            assertThat(scalarText(c, "SELECT VERSION()").startsWith("8.4.")).as("Actual MySQL 8.4").isTrue();
        }
    }
    @BeforeEach void resetBefore() throws Exception { reset(); }
    @AfterEach void resetAfter() throws Exception { reset(); }

    @Test void defaultPropagationActuallyEnablesServerReadOnlyReference() throws Exception {
        try (Connection c = open(false)) {
            DriverBoundary observations = DriverBoundary.install(c, false);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setReadOnly(true); c.setAutoCommit(false);
            assertThat(c.isReadOnly()).isTrue();
            assertThat(scalar(c, "SELECT @@session.transaction_read_only")).isEqualTo(1);
            assertThat(c.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            assertThat(c.getAutoCommit()).isFalse();
            assertThat(scalar(c, "SELECT COUNT(*) FROM demo_visit")).isZero();
            c.rollback();
            assertThat(observations.readOnlyStatements.get()).isEqualTo(1);
            assertThat(observations.rollbacks.get()).isEqualTo(1);
        }
    }

    @Test void falsePropagationKeepsClientReadOnlyButDoesNotEnableServerReadOnly() throws Exception {
        try (Connection c = open(true)) {
            DriverBoundary observations = DriverBoundary.install(c, false);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setReadOnly(true); c.setAutoCommit(false);
            assertThat(c.isReadOnly()).isTrue();
            assertThat(scalar(c, "SELECT @@session.transaction_read_only")).isZero();
            assertThat(c.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            assertThat(c.getAutoCommit()).isFalse();
            assertThat(scalar(c, "SELECT COUNT(*) FROM demo_visit")).isZero();
            c.rollback();
            assertThat(observations.readOnlyStatements.get()).isZero();
            assertThat(observations.rollbacks.get()).isEqualTo(1);
        }
    }

    @Test void driverLevelUnsupportedFixtureReproduces1235WithoutProviderConnection() throws Exception {
        try (Connection c = open(false)) {
            DriverBoundary observations = DriverBoundary.install(c, true);
            assertThatThrownBy(() -> c.setReadOnly(true)).isInstanceOfSatisfying(SQLException.class, error -> {
                assertThat(error.getErrorCode()).isEqualTo(1235);
                assertThat(error.getSQLState()).isEqualTo("42000");
            });
            assertThat(observations.readOnlyStatements.get()).isEqualTo(1);
            assertThat(observations.unsupportedFailures.get()).isEqualTo(1);
            assertThat(c.getAutoCommit()).isTrue();
            assertThat(scalar(c, "SELECT @@session.transaction_read_only")).isZero();
        }
    }

    @ParameterizedTest @EnumSource(value = Mode.class, names = {"VERIFY", "DRY_RUN"})
    void defaultPropagationRejectsProductReadOnlyModesAtActualDriverBoundary(Mode mode) throws Exception {
        seed();
        try (Connection c = open(false)) {
            DriverBoundary observations = DriverBoundary.install(c, true);
            assertThatThrownBy(() -> service.execute(c, mode, 10, 1000)).hasMessage("SQL_FAILURE");
            assertThat(observations.readOnlyStatements.get()).isEqualTo(1);
            assertThat(observations.unsupportedFailures.get()).isEqualTo(1);
            assertThat(observations.dml.get()).isZero();
            assertThat(c.getAutoCommit()).isTrue();
        }
        assertSeedCounts();
    }

    @ParameterizedTest @EnumSource(value = Mode.class, names = {"VERIFY", "DRY_RUN"})
    void falsePropagationCompletesSameProductModesWithNoUnsupportedStatementOrDml(Mode mode) throws Exception {
        seed();
        try (Connection c = open(true)) {
            DriverBoundary observations = DriverBoundary.install(c, true);
            var result = service.execute(c, mode, 10, 1000);
            assertThat(result.candidates()).isEqualTo(mode == Mode.DRY_RUN ? 1 : 0);
            assertThat(result.deleted()).isZero();
            assertThat(result.countBefore()).isEqualTo(1); assertThat(result.countAfter()).isEqualTo(1);
            assertThat(c.isReadOnly()).isTrue(); assertThat(c.getAutoCommit()).isFalse();
            assertThat(c.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            assertThat(observations.readOnlyStatements.get()).isZero();
            assertThat(observations.unsupportedFailures.get()).isZero();
            assertThat(observations.selects.get()).isGreaterThan(0);
            assertThat(observations.dml.get()).isZero();
            assertThat(observations.rollbacks.get()).isEqualTo(1);
        }
        assertSeedCounts();
    }

    @Test void falsePropagationApplyStaysWritableAndCommitsExactFiveDeletes() throws Exception {
        seed();
        try (Connection c = open(true)) {
            DriverBoundary observations = DriverBoundary.install(c, true);
            var result = service.execute(c, Mode.APPLY, 10, 1000);
            assertThat(result.deleted()).isEqualTo(1);
            assertThat(result.countBefore()).isEqualTo(1); assertThat(result.countAfter()).isZero();
            assertThat(c.isReadOnly()).isFalse();
            assertThat(c.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            assertThat(observations.readOnlyStatements.get()).isZero();
            assertThat(observations.unsupportedFailures.get()).isZero();
            assertThat(observations.dml.get()).isEqualTo(5);
            assertThat(observations.deletes.get()).isEqualTo(5);
            assertThat(observations.commits.get()).isEqualTo(1);
        }
        try (Connection c = open(true)) {
            for (String table : List.of("users", "cards", "transactions", "budgets", "demo_visit"))
                assertThat(scalar(c, "SELECT COUNT(*) FROM " + table)).isZero();
            assertThat(scalar(c, "SELECT max_visitors FROM demo_capacity WHERE id=1")).isEqualTo(1000);
            assertThat(scalar(c, "SELECT COUNT(*) FROM demo_admission_lock WHERE id=1")).isEqualTo(1);
        }
    }

    @Test void applicationGuardRejectsWritesAndBatchBeforeActualJdbcDelegate() throws Exception {
        try (Connection real = open(true)) {
            DriverBoundary driver = DriverBoundary.install(real, false);
            real.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            real.setReadOnly(true); real.setAutoCommit(false);
            AtomicInteger prepares = new AtomicInteger(), creates = new AtomicInteger(), batches = new AtomicInteger();
            Connection watched = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("prepareStatement")) prepares.incrementAndGet();
                if (method.getName().equals("createStatement")) creates.incrementAndGet();
                Object value;
                try { value = method.invoke(real, args); }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
                if (value instanceof PreparedStatement actual) {
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(statement, action, parameters)-> {
                        if (action.getName().contains("Batch")) batches.incrementAndGet();
                        try { return action.invoke(actual, parameters); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
                }
                return value;
            });
            Connection guarded = CleanupReadOnlyGuard.wrap(watched);
            for (String sql : List.of("INSERT INTO users(id) VALUES(-1)", "UPDATE users SET id=id WHERE id=-1",
                    "DELETE FROM users WHERE id=-1", "CREATE TABLE forbidden_probe(id BIGINT)",
                    "ALTER TABLE users COMMENT='forbidden'", "DROP TABLE users")) {
                assertThatThrownBy(() -> guarded.prepareStatement(sql)).hasMessage("READ_ONLY_GUARD_REJECTED");
            }
            assertThatThrownBy(guarded::createStatement).hasMessage("READ_ONLY_GUARD_REJECTED");
            assertThat(prepares.get()).isZero(); assertThat(creates.get()).isZero();
            try (var statement = guarded.prepareStatement("SELECT UTC_TIMESTAMP(6)")) {
                try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
                assertThatThrownBy(statement::addBatch).hasMessage("READ_ONLY_GUARD_REJECTED");
                assertThatThrownBy(statement::executeBatch).hasMessage("READ_ONLY_GUARD_REJECTED");
                assertThatThrownBy(statement::executeLargeBatch).hasMessage("READ_ONLY_GUARD_REJECTED");
            }
            assertThat(prepares.get()).isEqualTo(1); assertThat(batches.get()).isZero();
            assertThat(driver.selects.get()).isGreaterThan(0); assertThat(driver.dml.get()).isZero();
            assertThat(driver.readOnlyStatements.get()).isZero();
            guarded.rollback();
            assertThat(driver.rollbacks.get()).isEqualTo(1);
        }
    }

    private Connection open(boolean cleanupOption) throws SQLException {
        return DriverManager.getConnection(url + (cleanupOption ? (url.contains("?") ? "&" : "?")
            + "readOnlyPropagatesToServer=false" : ""), username, password);
    }
    private void reset() throws SQLException {
        if (url == null) return;
        try (Connection c = open(true); Statement s = c.createStatement()) {
            for (String table : List.of("transactions", "budgets", "cards", "demo_visit", "analysis_job", "users"))
                s.executeUpdate("DELETE FROM " + table);
        }
    }
    private void assertSeedCounts() throws SQLException {
        try (Connection c = open(true)) {
            for (var expected : Map.of("users",1,"cards",1,"transactions",240,"budgets",72,"demo_visit",1).entrySet())
                assertThat(scalar(c, "SELECT COUNT(*) FROM " + expected.getKey())).isEqualTo(expected.getValue().longValue());
        }
    }
    private void seed() throws SQLException {
        try (Connection c = open(true)) {
            c.setAutoCommit(false);
            long user = insert(c, "INSERT INTO users(created_at,email,name) VALUES(UTC_TIMESTAMP(6),'readonly-fixture@example.invalid','Synthetic cleanup fixture')");
            long card = insert(c, "INSERT INTO cards(created_at,user_id) VALUES(UTC_TIMESTAMP(6),?)", user);
            var scenario = DemoSeedScenario.generate(LocalDate.of(2026,3,15));
            try (PreparedStatement s = c.prepareStatement("INSERT INTO transactions(created_at,card_id,transaction_date_time,amount,merchant_name,category) VALUES(UTC_TIMESTAMP(6),?,?,?,?,?)")) {
                for (var row : scenario.transactions()) {
                    s.setLong(1,card); s.setObject(2,row.dateTime()); s.setInt(3,row.amount()); s.setString(4,row.merchantName()); s.setString(5,row.category()); s.addBatch();
                }
                assertThat(s.executeBatch()).hasSize(240);
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO budgets(user_id,budget_date,category,amount,is_overridden) VALUES(?,?,?,?,0)")) {
                for (var row : scenario.budgets()) {
                    s.setLong(1,user); s.setObject(2,row.date()); s.setString(3,row.category()); s.setInt(4,row.amount()); s.addBatch();
                }
                assertThat(s.executeBatch()).hasSize(72);
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO demo_visit(user_id,created_at,scenario_version,session_expires_at) VALUES(?,UTC_TIMESTAMP(6)-INTERVAL 48 HOUR,'V1',UTC_TIMESTAMP(6)-INTERVAL 47 HOUR)")) {
                s.setLong(1,user); assertThat(s.executeUpdate()).isEqualTo(1);
            }
            c.commit();
        }
    }
    private static long insert(Connection c, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i=0;i<arguments.length;i++) s.setObject(i+1,arguments[i]);
            assertThat(s.executeUpdate()).isEqualTo(1);
            try (var rows=s.getGeneratedKeys()) { assertThat(rows.next()).isTrue(); return rows.getLong(1); }
        }
    }
    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement s=c.createStatement(); var rows=s.executeQuery(sql)) { assertThat(rows.next()).isTrue(); return rows.getLong(1); }
    }
    private static String scalarText(Connection c, String sql) throws SQLException {
        try (Statement s=c.createStatement(); var rows=s.executeQuery(sql)) { assertThat(rows.next()).isTrue(); return rows.getString(1); }
    }

    /** Public Connector/J interceptor API, reflected only because the driver is a runtime dependency. */
    static final class DriverBoundary {
        final AtomicInteger readOnlyStatements=new AtomicInteger(), unsupportedFailures=new AtomicInteger(),
            selects=new AtomicInteger(), dml=new AtomicInteger(), deletes=new AtomicInteger(),
            commits=new AtomicInteger(), rollbacks=new AtomicInteger();
        static DriverBoundary install(Connection connection, boolean unsupportedReadOnly) throws Exception {
            DriverBoundary counters=new DriverBoundary();
            Class<?> api=Class.forName("com.mysql.cj.interceptors.QueryInterceptor");
            Object interceptor=Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(proxy,method,args)-> {
                if (method.getName().equals("executeTopLevelOnly")) return false;
                if (method.getName().equals("init")) return proxy;
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy==args[0];
                if (method.getName().equals("toString")) return "OwnedDriverBoundary";
                if (method.getName().equals("preProcess") && args.length==2 && args[0] instanceof Supplier<?> supplier) {
                    String sql=supplier.get().toString().strip().replaceAll("\\s+"," ").toUpperCase(Locale.ROOT);
                    if (sql.equals("SET SESSION TRANSACTION READ ONLY")) {
                        counters.readOnlyStatements.incrementAndGet();
                        if (unsupportedReadOnly) { counters.unsupportedFailures.incrementAndGet(); throw unsupported(); }
                    }
                    if (sql.startsWith("SELECT ") || sql.startsWith("SHOW ")) counters.selects.incrementAndGet();
                    if (sql.matches("^(INSERT|UPDATE|DELETE) .*")) counters.dml.incrementAndGet();
                    if (sql.startsWith("DELETE ")) counters.deletes.incrementAndGet();
                    if (sql.equals("COMMIT")) counters.commits.incrementAndGet();
                    if (sql.equals("ROLLBACK")) counters.rollbacks.incrementAndGet();
                }
                return null;
            });
            Object session=connection.getClass().getMethod("getSession").invoke(connection);
            session.getClass().getMethod("setQueryInterceptors",List.class).invoke(session,List.of(interceptor));
            return counters;
        }
        private static RuntimeException unsupported() throws Exception {
            Class<?> type=Class.forName("com.mysql.cj.exceptions.CJException");
            RuntimeException failure=(RuntimeException)type.getConstructor(String.class).newInstance("SYNTHETIC_READ_ONLY_UNSUPPORTED");
            type.getMethod("setSQLState",String.class).invoke(failure,"42000");
            type.getMethod("setVendorCode",int.class).invoke(failure,1235);
            return failure;
        }
    }
}
