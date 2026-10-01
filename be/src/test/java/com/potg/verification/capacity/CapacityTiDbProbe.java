package com.potg.verification.capacity;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potg.don.demo.admission.*;
import com.potg.don.demo.seed.DemoSeedScenario;
import com.potg.don.maintenance.*;
import com.potg.don.maintenance.CleanupOptions.Mode;

/** Historical counter-based probe from deployment report 11.
 * Its reviewed migration digest intentionally remains pinned: the counterless migration
 * is rejected before opening any connection. Only Java call sites follow the current
 * product API so the retained safety tests compile; this is not the next remote probe.
 */
public final class CapacityTiDbProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<PosixFilePermission> FILE_MODE = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<String> TABLES = Set.of("users", "cards", "transactions", "budgets", "analysis_job", "peer_transaction_stats", "dummy", "demo_visit", "demo_capacity");
    private static final String BASELINE_SHA = "6f407517cea0879bf55cd4568a01128890b045d5e72565b855c546c8236c7c35";
    private static final String MIGRATION_SHA = "638e7c89fb078af9f268b3273627ade946a1365da36c68db66515378e3e4a614";
    private final Map<String, String> config;
    private final Path resultPath, ledgerPath;
    private final CapacitySqlBudget budget;
    private final Map<String, Object> result = new LinkedHashMap<>();
    private final Map<String, Boolean> checks = new LinkedHashMap<>();
    private final List<Connection> clients = new ArrayList<>();
    private final Set<String> createdTables = new LinkedHashSet<>();
    private final Set<Long> ownedUsers = new LinkedHashSet<>();
    private final String schema, runtimeAccount, cleanupAccount;
    private final Set<String> createdAccounts = new LinkedHashSet<>();
    private final Set<String> attemptedAccounts = new LinkedHashSet<>();
    private boolean schemaAttempted, schemaCreated, cleaned, cleanupComplete;
    private boolean agingAttempted, unsafeJobAttempted, unsafeTeardownAttempted, unsafeTeardownConfirmed;
    private long unsafeUser, unsafeJob;
    private boolean schemaVerified, admissionVerified, cleanupVerified, privilegesVerified;
    private Connection admin, runtimeA, runtimeB, cleaner;
    private String phase = "INPUT";
    private String sqlFailurePhase, sqlFailureState;
    private int sqlFailureVendor;
    private static final LocalDate ANCHOR = LocalDate.of(2024, 2, 29);

    private CapacityTiDbProbe(Map<String, String> config, Path resultPath, Path ledgerPath) throws Exception {
        validate(config);
        this.config = config; this.resultPath = resultPath; this.ledgerPath = ledgerPath;
        schema = config.get("schema"); runtimeAccount = config.get("runtimeAccount"); cleanupAccount = config.get("cleanupAccount");
        budget = new CapacitySqlBudget(Long.parseLong(config.get("deadlineEpochMillis")));
        budget.sqlFailureObserver = (vendor, state) -> { sqlFailurePhase = phase; sqlFailureVendor = vendor; sqlFailureState = state; };
        result.put("status", "BLOCKED"); result.put("checks", checks);
        writeLedger();
    }
    public static void main(String[] args) {
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        CapacityTiDbProbe probe = null;
        int exit = 2;
        try {
            require(args.length == 0, "ARGUMENTS_REJECTED");
            Path input = privateFile(System.getenv("CAPACITY_CHECK_CONFIG"));
            Path result = privateFile(System.getenv("CAPACITY_CHECK_RESULT"));
            Path ledger = privateFile(System.getenv("CAPACITY_CHECK_LEDGER"));
            require(input.getParent().equals(result.getParent()) && input.getParent().equals(ledger.getParent()), "PRIVATE_DIRECTORY_MISMATCH");
            Map<String, String> config = new LinkedHashMap<>();
            var root = JSON.readTree(Files.readAllBytes(input));
            require(root.isObject(), "INPUT_OBJECT");
            root.fields().forEachRemaining(e -> config.put(e.getKey(), e.getValue().asText()));
            probe = new CapacityTiDbProbe(config, result, ledger);
            CapacityTiDbProbe running = probe;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.budget.cancelled = true;
                synchronized (running) { running.cleanup(); running.save(); }
            }, "capacity-owned-cleanup"));
            synchronized (probe) {
                try { probe.run(); probe.result.put("status", "PASS"); exit = 0; }
                catch (Exception error) {
                    probe.result.put("status", "FAIL"); probe.result.put("failedPhase", probe.phase);
                    probe.result.put("failureCode", fixedFailure(error));
                    var sql = safeSqlFailure(error);
                    if (!sql.isEmpty()) probe.result.putAll(sql);
                    else if (probe.phase.equals(probe.sqlFailurePhase)) {
                        probe.result.put("failureVendor", probe.sqlFailureVendor); probe.result.put("failureSqlState", probe.sqlFailureState);
                    }
                } finally { probe.cleanup(); probe.save(); }
            }
            if (!probe.cleanupComplete) exit = 2;
        } catch (Exception ignored) { /* No raw driver or input message can reach logs. Missing result is failure. */ }
        System.exit(exit);
    }
    static void validate(Map<String, String> values) {
        require(values.keySet().equals(Set.of("TIDB_HOST", "TIDB_PORT", "TIDB_SETUP_USERNAME", "TIDB_SETUP_PASSWORD", "schema", "runtimeAccount", "runtimePassword", "cleanupAccount", "cleanupPassword", "baselinePath", "migrationPath", "deadlineEpochMillis", "workLimit", "cleanupLimit", "connectionLimit")), "INPUT_KEYS");
        require(values.get("TIDB_HOST").matches("[A-Za-z0-9.-]{1,253}") && !values.get("TIDB_HOST").contains(".."), "HOST_FORMAT");
        int port = Integer.parseInt(values.get("TIDB_PORT")); require(port > 0 && port <= 65535, "PORT_RANGE");
        require(values.get("TIDB_SETUP_USERNAME").matches("[A-Za-z0-9]{1,16}\\.[A-Za-z0-9_]+"), "SETUP_PREFIX");
        String prefix = values.get("TIDB_SETUP_USERNAME").split("\\.", 2)[0] + ".m";
        for (String key : List.of("runtimeAccount", "cleanupAccount")) require(values.get(key).startsWith(prefix) && values.get(key).substring(prefix.length()).matches("[a-f0-9]{14}") && values.get(key).length() <= 32, "ACCOUNT_FORMAT");
        require(!values.get("runtimeAccount").equals(values.get("cleanupAccount")), "ACCOUNTS_DISTINCT");
        require(values.get("schema").matches("mtcapacity[a-f0-9]{16}"), "SCHEMA_FORMAT");
        for (String key : List.of("runtimePassword", "cleanupPassword")) require(values.get(key).matches("[A-Za-z0-9_-]{40,128}"), "GENERATED_PASSWORD_FORMAT");
        require(!values.get("TIDB_SETUP_PASSWORD").isBlank(), "SETUP_PASSWORD");
        require("1500".equals(values.get("workLimit")) && "500".equals(values.get("cleanupLimit")) && "8".equals(values.get("connectionLimit")), "FIXED_BUDGET");
        long remaining = Long.parseLong(values.get("deadlineEpochMillis")) - System.currentTimeMillis();
        require(remaining > 0 && remaining <= 900_000, "FINITE_DEADLINE");
    }
    private void run() throws Exception {
        phase = "REVIEWED_DDL";
        List<String> baseline = statements(Path.of(config.get("baselinePath")), BASELINE_SHA, 7);
        List<String> migration = statements(Path.of(config.get("migrationPath")), MIGRATION_SHA, 3);
        phase = "SETUP";
        admin = connect(config.get("TIDB_SETUP_USERNAME"), config.get("TIDB_SETUP_PASSWORD"), false);
        require(scalar(admin, "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", schema) == 0, "FRESH_SCHEMA_REQUIRED");
        require(accountAbsent(runtimeAccount) && accountAbsent(cleanupAccount), "FRESH_ACCOUNTS_REQUIRED");
        schemaAttempted = true; writeLedger();
        execute(admin, "CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        schemaCreated = true; writeLedger(); admin.setCatalog(schema);
        for (String sql : baseline) { execute(admin, sql); createdTables.add(sql.split("\\s+")[2]); writeLedger(); }
        for (String sql : migration) { execute(admin, sql); if (sql.startsWith("CREATE TABLE")) createdTables.add(sql.split("\\s+")[2]); writeLedger(); }
        require(createdTables.equals(TABLES), "NINE_TABLES_CREATED");
        update(admin, "UPDATE demo_capacity SET max_visitors=2 WHERE id=1 AND resident_count=0 AND max_visitors=1000", 1);
        createAccount(runtimeAccount, config.get("runtimePassword")); createAccount(cleanupAccount, config.get("cleanupPassword"));
        grants(runtimeAccount, false); grants(cleanupAccount, true);
        runtimeA = connect(runtimeAccount, config.get("runtimePassword"), true);
        runtimeB = connect(runtimeAccount, config.get("runtimePassword"), true);
        cleaner = connect(cleanupAccount, config.get("cleanupPassword"), true);
        phase = "SCHEMA";
        DemoAdmissionSchema.verify(runtimeA);
        require(DemoAdmissionSchema.verifySnapshot(runtimeA, 2).visitCount() == 0, "INITIAL_COUNTER");
        var version = rows(runtimeA, "SELECT VERSION()");
        require(version.size() == 1 && version.getFirst().getFirst().toString().toLowerCase(Locale.ROOT).contains("tidb"), "ACTUAL_TIDB_REQUIRED");
        pass("FOUR_IDENTITY_VERIFIED_TLS_CONNECTIONS");
        require(scalar(cleaner, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'") == 9, "CLEANUP_NINE_TABLES_VISIBLE");
        require(scalar(cleaner, "SELECT COUNT(*) FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE() AND referenced_table_name IS NOT NULL") == 4, "CLEANUP_FOUR_FKS_VISIBLE");
        require(scalar(admin, "SELECT COUNT(*) FROM information_schema.key_column_usage WHERE referenced_table_schema=? AND constraint_schema<>?", schema, schema) == 0, "ADMIN_NO_EXTERNAL_FK");
        pass("SCHEMA_METADATA"); schemaVerified = true;
        phase = "NOWAIT"; nowait(); pass("NOWAIT_3572_HY000");
        phase = "ROLLBACK"; rollbackAdmission(); pass("BOUND_ADMISSION_ROLLBACK");
        phase = "DATASETS";
        Dataset safe = seed(runtimeA); Dataset unsafe = seed(runtimeA);
        unsafeUser = unsafe.user; writeLedger();
        require(DemoAdmissionSchema.verifySnapshot(runtimeA, 2).visitCount() == 2, "TWO_COMMITTED_VISITS");
        require(scalar(runtimeA, "SELECT COUNT(*) FROM transactions") == 480 && scalar(runtimeA, "SELECT COUNT(*) FROM budgets") == 144, "V1_COUNTS");
        require(scalar(runtimeA, "SELECT COUNT(*) FROM cards WHERE card_no IS NOT NULL OR cvc IS NOT NULL") == 0 && scalar(runtimeA, "SELECT COUNT(*) FROM analysis_job") == 0 && scalar(runtimeA, "SELECT COUNT(*) FROM budgets WHERE initial_amount IS NOT NULL OR initial_file_id IS NOT NULL OR predicted_at IS NOT NULL") == 0, "V1_NULL_INVARIANTS");
        phase = "CAPACITY_FULL";
        try { transaction(runtimeB, store -> { store.claimSlot(); return null; }, false); throw rejected("FULL_NOT_REJECTED"); }
        catch (DemoAdmissionException e) { require(e.code() == DemoAdmissionException.Code.DEMO_CAPACITY_FULL, "FULL_CLASSIFICATION"); }
        require(DemoAdmissionSchema.verifySnapshot(runtimeA, 2).visitCount() == 2, "FULL_COUNTER_UNCHANGED");
        admissionVerified = true; pass("COMMIT_FULL_COUNTER_EQUALITY");
        phase = "PRIVILEGES";
        update(runtimeA, "UPDATE transactions SET category=? WHERE id=?", 1, "마트 / 편의점", safe.practice);
        update(runtimeA, "UPDATE users SET age=37 WHERE id=?", 1, safe.user);
        update(runtimeA, "UPDATE budgets SET amount=amount+1,is_overridden=1 WHERE id=?", 1, safe.budget);
        long unrelated = insert(runtimeA, "INSERT INTO users(created_at,email,name) VALUES(UTC_TIMESTAMP(6),?,?)", "unmarked-fixture@moneytoad.invalid", "합성 비방문 행");
        ownedUsers.add(unrelated); writeLedger();
        List<List<Object>> unrelatedBefore = rows(runtimeA, "SELECT * FROM users WHERE id=?", unrelated);
        for (String sql : List.of("DELETE FROM users WHERE id=-1", "ALTER TABLE users COMMENT='capacity-denial-fixture'", "INSERT INTO analysis_job(user_id) VALUES(NULL)", "INSERT INTO peer_transaction_stats(age_group) VALUES(1)", "INSERT INTO dummy(category,merchant_name,min_amount,max_amount) VALUES('fixture','fixture',1,1)", "UPDATE demo_capacity SET max_visitors=max_visitors WHERE id=1")) denied(runtimeA, sql);
        for (String sql : List.of("INSERT INTO users(created_at,email,name) VALUES(UTC_TIMESTAMP(6),'denial-fixture@moneytoad.invalid','fixture')", "ALTER TABLE users COMMENT='capacity-denial-fixture'", "INSERT INTO analysis_job(user_id) VALUES(NULL)", "UPDATE demo_capacity SET max_visitors=max_visitors WHERE id=1")) denied(cleaner, sql);
        pass("LEAST_PRIVILEGE_DENIALS");
        phase = "AGE_OWNED_FIXTURES";
        agingAttempted = true; writeLedger();
        update(admin, "UPDATE demo_visit SET created_at=UTC_TIMESTAMP(6)-INTERVAL 25 HOUR,session_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 HOUR WHERE user_id IN (?,?)", 2, safe.user, unsafe.user);
        writeLedger();
        phase = "CLEANUP_VERIFY_DRY_RUN";
        var allBefore = snapshot(runtimeA); int dml = budget.dml;
        var verified = cleanupService(Mode.VERIFY);
        require(verified.countBefore() == 2 && verified.deleted() == 0 && budget.dml == dml, "VERIFY_DML_ZERO");
        var dry = cleanupService(Mode.DRY_RUN);
        require(dry.candidates() == 2 && dry.deleted() == 0 && budget.dml == dml && snapshot(runtimeA).equals(allBefore), "DRY_RUN_UNCHANGED");
        pass("VERIFY_AND_DRY_RUN_ZERO_DML"); pass("MUTABLE_CATEGORY_ACCEPTED");
        phase = "UNSAFE_BATCH";
        unsafeJobAttempted = true; writeLedger();
        long job = insert(admin, "INSERT INTO analysis_job(user_id,status,created_at,updated_at) VALUES(?,'QUEUED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", unsafe.user);
        unsafeJob = job; writeLedger();
        var unsafeBefore = snapshot(runtimeA); dml = budget.dml;
        try { cleanupService(Mode.APPLY); throw rejected("UNSAFE_BATCH_ACCEPTED"); }
        catch (CleanupFailure error) { require(error.code() == CleanupFailure.Code.UNSAFE_BATCH, "UNSAFE_CLASSIFICATION"); }
        require(budget.dml == dml && snapshot(runtimeA).equals(unsafeBefore), "UNSAFE_WHOLE_BATCH_UNCHANGED"); pass("UNSAFE_BATCH_ZERO_DML");
        phase = "ADMIN_UNSAFE_TEARDOWN";
        unsafeTeardownAttempted = true; writeLedger();
        admin.setAutoCommit(false);
        update(admin, "DELETE FROM analysis_job WHERE id=? AND user_id=?", 1, job, unsafe.user);
        deleteDatasetAdmin(unsafe);
        update(admin, "UPDATE demo_capacity SET resident_count=resident_count-1 WHERE id=1 AND resident_count=2", 1);
        require(DemoAdmissionSchema.verifySnapshot(admin, 2).visitCount() == 1, "UNSAFE_TEARDOWN_COUNTER");
        admin.commit(); admin.setAutoCommit(true); unsafeTeardownConfirmed = true; writeLedger(); pass("ADMIN_UNSAFE_DATASET_REMOVED");
        phase = "CLEANUP_APPLY";
        var applied = cleanupService(Mode.APPLY);
        require(applied.candidates() == 1 && applied.deleted() == 1 && applied.countBefore() == 1 && applied.countAfter() == 0, "CLEANUP_EXACT_ONE_VISIT");
        require(scalar(runtimeA, "SELECT COUNT(*) FROM transactions") == 0 && scalar(runtimeA, "SELECT COUNT(*) FROM budgets") == 0 && scalar(runtimeA, "SELECT COUNT(*) FROM cards") == 0 && scalar(runtimeA, "SELECT COUNT(*) FROM demo_visit") == 0 && scalar(runtimeA, "SELECT COUNT(*) FROM users WHERE id IN (?,?)", safe.user, unsafe.user) == 0, "DATASETS_ABSENT");
        require(unrelatedBefore.equals(rows(runtimeA, "SELECT * FROM users WHERE id=?", unrelated)), "UNRELATED_ALL_COLUMNS_UNCHANGED");
        require(DemoAdmissionSchema.verifySnapshot(runtimeA, 2).visitCount() == 0, "FINAL_COUNTER_EQUALITY");
        cleanupVerified = true; privilegesVerified = true; pass("REAL_RESTRICTED_CLEANUP_APPLY"); pass("UNRELATED_ROW_UNCHANGED");
    }
    private void nowait() throws Exception {
        runtimeA.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); runtimeA.setAutoCommit(false);
        try {
            DemoAdmissionSchema.verifyTransactionMode(runtimeA);
            scalar(runtimeA, "SELECT id FROM demo_capacity WHERE id=1 FOR UPDATE NOWAIT");
            try { transaction(runtimeB, store -> { store.claimSlot(); return null; }, false); throw rejected("NOWAIT_ACQUIRED"); }
            catch (DemoAdmissionException error) { require(error.code() == DemoAdmissionException.Code.DEMO_ADMISSION_BUSY, "NOWAIT_PRODUCT_MAPPING"); }
            require(budget.nowaitVendor == 3572 && "HY000".equals(budget.nowaitState), "NOWAIT_VENDOR_STATE");
        } finally { runtimeA.rollback(); runtimeA.setAutoCommit(true); }
    }
    private void rollbackAdmission() throws Exception {
        transaction(runtimeA, store -> {
            long initialCount = store.claimSlot(); long user = insertUnchecked(runtimeA, "INSERT INTO users(created_at,email,name) VALUES(UTC_TIMESTAMP(6),?,?)", "rollback-fixture@moneytoad.invalid", "합성 롤백");
            ownedUsers.add(user); ledgerUnchecked();
            store.recordVisit(user, Instant.now().plusSeconds(3600)); store.verifyIntegrity(initialCount); return null;
        }, true);
        require(DemoAdmissionSchema.verifySnapshot(runtimeA, 2).visitCount() == 0 && scalar(runtimeA, "SELECT COUNT(*) FROM users") == 0 && scalar(runtimeA, "SELECT COUNT(*) FROM demo_visit") == 0, "ROLLBACK_ALL_ABSENT");
    }
    private record Dataset(long user, long card, long practice, long budget) { }
    private Dataset seed(Connection c) {
        return transaction(c, store -> {
            long initialCount = store.claimSlot();
            long user = insertUnchecked(c, "INSERT INTO users(created_at,email,name) VALUES(UTC_TIMESTAMP(6),?,?)", "demo-fixture-" + ownedUsers.size() + "@moneytoad.invalid", "합성 검증 방문자");
            ownedUsers.add(user); ledgerUnchecked();
            long card = insertUnchecked(c, "INSERT INTO cards(created_at,user_id,card_no,cvc) VALUES(UTC_TIMESTAMP(6),?,NULL,NULL)", user);
            var scenario = DemoSeedScenario.generate(ANCHOR); long practice = 0, firstBudget = 0;
            for (var row : scenario.transactions()) {
                long id = insertUnchecked(c, "INSERT INTO transactions(created_at,card_id,transaction_date_time,amount,merchant_name,category) VALUES(UTC_TIMESTAMP(6),?,?,?,?,?)", card, row.dateTime(), row.amount(), row.merchantName(), row.category());
                if (row.merchantName().equals(DemoSeedScenario.PRACTICE_MERCHANT)) practice = id;
            }
            for (var row : scenario.budgets()) {
                long id = insertUnchecked(c, "INSERT INTO budgets(user_id,budget_date,amount,category,is_overridden) VALUES(?,?,?,?,0)", user, row.date(), row.amount(), row.category());
                if (firstBudget == 0) firstBudget = id;
            }
            require(practice > 0 && firstBudget > 0, "FIXTURE_IDENTITIES");
            store.recordVisit(user, Instant.now().plusSeconds(3600)); store.verifyIntegrity(initialCount);
            return new Dataset(user, card, practice, firstBudget);
        }, false);
    }
    private <T> T transaction(Connection c, java.util.function.Function<DemoAdmissionStore, T> work, boolean rollback) {
        SingleConnectionDataSource ds = new SingleConnectionDataSource(c, true);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status -> { T result = work.apply(new DemoAdmissionStore(new JdbcTemplate(ds), 2)); if (rollback) status.setRollbackOnly(); return result; });
    }
    private DemoCleanupService.Result cleanupService(Mode mode) throws SQLException {
        return withConfirmedCleanupReset(cleaner, () -> new DemoCleanupService().execute(cleaner, mode, 2, 2));
    }
    static <T> T withConfirmedCleanupReset(Connection connection, java.util.function.Supplier<T> work) throws SQLException {
        boolean reset = false;
        try { T result = work.get(); reset = true; return result; }
        catch (CleanupFailure error) {
            reset = error.code() != CleanupFailure.Code.COMMIT_UNKNOWN && error.code() != CleanupFailure.Code.ROLLBACK_UNKNOWN;
            throw error;
        } finally { if (reset) { connection.setAutoCommit(true); connection.setReadOnly(false); } }
    }
    static boolean closeOwnedClients(List<Connection> clients, Connection last) {
        boolean closed = true;
        for (Connection connection : clients) if (connection != last) {
            try { if (!connection.getAutoCommit()) connection.rollback(); } catch (Exception ignored) { closed = false; }
            finally { try { connection.close(); if (!connection.isClosed()) closed = false; } catch (Exception ignored) { closed = false; } }
        }
        return closed;
    }
    private void deleteDatasetAdmin(Dataset dataset) throws SQLException {
        update(admin, "DELETE FROM transactions WHERE card_id=?", 240, dataset.card);
        update(admin, "DELETE FROM budgets WHERE user_id=?", 72, dataset.user);
        update(admin, "DELETE FROM cards WHERE id=? AND user_id=?", 1, dataset.card, dataset.user);
        update(admin, "DELETE FROM demo_visit WHERE user_id=?", 1, dataset.user);
        update(admin, "DELETE FROM users WHERE id=?", 1, dataset.user);
    }
    private void grants(String account, boolean cleanupRole) throws SQLException {
        for (String table : TABLES) execute(admin, "GRANT SELECT ON `" + schema + "`." + table + " TO '" + account + "'@'%'");
        for (String table : List.of("users", "cards", "transactions", "budgets", "demo_visit")) execute(admin, "GRANT " + (cleanupRole ? "DELETE" : "INSERT") + " ON `" + schema + "`." + table + " TO '" + account + "'@'%'");
        if (!cleanupRole) for (String table : List.of("users", "transactions", "budgets")) execute(admin, "GRANT UPDATE ON `" + schema + "`." + table + " TO '" + account + "'@'%'");
        execute(admin, "GRANT UPDATE(resident_count) ON `" + schema + "`.demo_capacity TO '" + account + "'@'%'");
    }
    private void createAccount(String account, String password) throws Exception {
        attemptedAccounts.add(account); writeLedger();
        execute(admin, "CREATE USER '" + account + "'@'%' IDENTIFIED BY '" + password + "'");
        createdAccounts.add(account); writeLedger();
    }
    private Connection connect(String username, String password, boolean catalog) throws Exception {
        budget.opening();
        Properties properties = new Properties();
        properties.setProperty("user", username); properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "5000"); properties.setProperty("socketTimeout", "10000");
        properties.setProperty("autoReconnect", "false"); properties.setProperty("allowMultiQueries", "false");
        properties.setProperty("useServerPrepStmts", "false"); properties.setProperty("rewriteBatchedStatements", "false");
        properties.setProperty("logger", "com.mysql.cj.log.NullLogger");
        properties.setProperty("connectionTimeZone", "UTC"); properties.setProperty("forceConnectionTimeZoneToSession", "true");
        String url = "jdbc:mysql://" + config.get("TIDB_HOST") + ":" + config.get("TIDB_PORT") + "/" + (catalog ? schema : "") + "?sslMode=VERIFY_IDENTITY";
        Connection connection = budget.observe(DriverManager.getConnection(url, properties)); clients.add(connection);
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
            require(rows.next() && rows.getString(2) != null && !rows.getString(2).isBlank(), "TLS_CIPHER_REQUIRED");
        }
        return connection;
    }
    private boolean accountAbsent(String account) throws SQLException {
        try (var statement = admin.createStatement(); var ignored = statement.executeQuery("SHOW GRANTS FOR '" + account + "'@'%'") ) { return false; }
        catch (SQLException error) { if (error.getErrorCode() == 1141) return true; throw error; }
    }
    private void denied(Connection c, String sql) throws SQLException {
        try { execute(c, sql); }
        catch (SQLException error) { require(Set.of(1142, 1143).contains(error.getErrorCode()) && "42000".equals(error.getSQLState()), "EXPECTED_PRIVILEGE_DENIAL"); return; }
        throw rejected("EXCESS_PRIVILEGE");
    }
    private synchronized void cleanup() {
        if (cleaned) return; cleaned = true; budget.startCleanup();
        boolean datasets = !schemaAttempted, accounts = attemptedAccounts.isEmpty(), removedSchema = !schemaAttempted, closed = true;
        try { writeLedger(); } catch (Exception ignored) { result.put("ledgerFailure", true); }
        // Release owned transactions first, but retain all clients until resource removal finishes.
        for (Connection connection : clients) if (connection != admin) {
            try { if (!connection.getAutoCommit()) connection.rollback(); } catch (Exception ignored) { closed = false; }
        }
        if (admin != null) {
            try {
                if (!admin.getAutoCommit()) { admin.rollback(); admin.setAutoCommit(true); }
                if (schemaAttempted && !schemaCreated) {
                    long exists = scalar(admin, "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", schema);
                    require(exists == 0 || exists == 1, "OWNED_SCHEMA_EXISTENCE");
                    if (exists == 1) { schemaCreated = true; writeLedger(); }
                    else { datasets = true; removedSchema = true; }
                }
                if (schemaCreated) {
                    admin.setCatalog(schema);
                    var actual = rows(admin, "SELECT table_name FROM information_schema.tables WHERE table_schema=?", schema);
                    for (var row : actual) { require(TABLES.contains(row.getFirst().toString()), "OWNED_TABLE_ALLOWLIST"); createdTables.add(row.getFirst().toString()); }

                    for (String table : List.of("analysis_job", "transactions", "budgets", "cards", "demo_visit", "users", "peer_transaction_stats", "dummy", "demo_capacity")) {
                        if (!createdTables.contains(table)) continue;
                        String column = table.equals("demo_visit") ? "user_id" : "id";
                        List<List<Object>> ids = rows(admin, "SELECT " + column + " FROM " + table);
                        require(ids.size() <= 1000, "OWNED_CLEANUP_ROW_BOUND");
                        if (!ids.isEmpty()) {
                            String sql = "DELETE FROM " + table + " WHERE " + column + " IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")";
                            update(admin, sql, ids.size(), ids.stream().map(row -> row.getFirst()).toArray());
                        }
                        require(scalar(admin, "SELECT COUNT(*) FROM " + table) == 0, "OWNED_TABLE_EMPTY");
                    }
                    datasets = true;
                }
                accounts = true;
                for (String account : List.of(cleanupAccount, runtimeAccount)) if (attemptedAccounts.contains(account)) {
                    if (!accountAbsent(account)) execute(admin, "DROP USER '" + account + "'@'%'");
                    if (!accountAbsent(account)) accounts = false;
                }
                if (schemaCreated) {
                    execute(admin, "DROP DATABASE `" + schema + "`");
                    removedSchema = scalar(admin, "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", schema) == 0;
                }
            } catch (Exception ignored) { result.put("cleanupFailure", true); }
        }
        closed = closeOwnedClients(clients, null) && closed;
        result.put("ownedDataRemoved", datasets); result.put("ownedAccountsAbsent", accounts); result.put("ownedSchemaAbsent", removedSchema); result.put("clientsClosed", closed);
        cleanupComplete = datasets && accounts && removedSchema && closed && !Boolean.TRUE.equals(result.get("ledgerFailure")) && !Boolean.TRUE.equals(result.get("cleanupFailure"));
        if (!cleanupComplete) result.put("status", "FAIL");
        try { writeLedger(); } catch (Exception ignored) { cleanupComplete = false; result.put("status", "FAIL"); }
    }
    private void writeLedger() throws Exception {
        Map<String, Object> ledger = new LinkedHashMap<>();
        ledger.put("schema", schema); ledger.put("schemaAttempted", schemaAttempted); ledger.put("schemaCreated", schemaCreated);
        ledger.put("runtimeAccount", runtimeAccount); ledger.put("cleanupAccount", cleanupAccount);
        ledger.put("attemptedAccounts", attemptedAccounts); ledger.put("createdAccounts", createdAccounts);
        ledger.put("createdTables", createdTables); ledger.put("ownedUsers", ownedUsers);
        ledger.put("agingAttempted", agingAttempted); ledger.put("unsafeUser", unsafeUser);
        ledger.put("unsafeJobAttempted", unsafeJobAttempted); ledger.put("unsafeJob", unsafeJob);
        ledger.put("unsafeTeardownAttempted", unsafeTeardownAttempted); ledger.put("unsafeTeardownConfirmed", unsafeTeardownConfirmed);
        ledger.put("cleanupStarted", cleaned); ledger.put("cleanupComplete", cleanupComplete);
        durable(ledgerPath, ledger);
    }
    private void ledgerUnchecked() { try { writeLedger(); } catch (Exception ignored) { throw rejected("LEDGER_WRITE_FAILED"); } }
    private void save() {
        result.put("schemaVerified", schemaVerified); result.put("admissionVerified", admissionVerified); result.put("cleanupVerified", cleanupVerified); result.put("privilegesVerified", privilegesVerified);
        result.put("cleanupComplete", cleanupComplete); result.put("connectionAttempts", budget.connections); result.put("workCommands", budget.work); result.put("cleanupCommands", budget.cleanup);
        result.put("nowaitVendor", budget.nowaitVendor); result.put("nowaitSqlState", budget.nowaitState);
        try { durable(resultPath, result); } catch (Exception ignored) { }
    }
    private static void durable(Path path, Object document) throws Exception {
        byte[] bytes = JSON.writeValueAsBytes(document);
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) file.write(buffer); file.force(true);
        }
    }
    static List<String> statements(Path path, String expected, int count) throws Exception {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && Files.size(path) < 32768, "DDL_FILE");
        byte[] bytes = Files.readAllBytes(path);
        require(expected.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))), "DDL_DIGEST");
        var result = Arrays.stream(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).replaceAll("(?m)^\\s*--.*$", "").split(";")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        require(result.size() == count, "DDL_COUNT"); return result;
    }
    static Path privateFile(String value) throws Exception {
        require(value != null, "PRIVATE_PATH_REQUIRED"); Path path = Path.of(value);
        require(path.isAbsolute() && path.normalize().equals(path) && path.toRealPath().equals(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && Files.getPosixFilePermissions(path).equals(FILE_MODE) && ((Number) Files.getAttribute(path, "unix:nlink")).longValue() == 1 && ((Number) Files.getAttribute(path, "unix:uid")).longValue() == ((Number) Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid")).longValue(), "PRIVATE_FILE_SAFETY");
        require(Files.getPosixFilePermissions(path.getParent()).equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)) && Files.getOwner(path.getParent()).equals(Files.getOwner(path)), "PRIVATE_DIRECTORY_MODE"); return path;
    }
    private static List<List<Object>> rows(Connection c, String sql, Object... args) throws SQLException {
        try (var p = c.prepareStatement(sql)) { bind(p, args); try (var r = p.executeQuery()) {
            List<List<Object>> result = new ArrayList<>(); int width = r.getMetaData().getColumnCount();
            while (r.next()) { List<Object> row = new ArrayList<>(); for (int i = 1; i <= width; i++) row.add(r.getObject(i)); result.add(row); }
            return result;
        } }
    }
    private static Map<String, List<List<Object>>> snapshot(Connection c) throws SQLException {
        Map<String, List<List<Object>>> result = new TreeMap<>();
        for (String table : TABLES) result.put(table, rows(c, "SELECT * FROM " + table + " ORDER BY " + (table.equals("demo_visit") ? "user_id" : "id")));
        return result;
    }
    private static long scalar(Connection c, String sql, Object... args) throws SQLException { var result = rows(c, sql, args); require(result.size() == 1 && result.getFirst().size() == 1 && result.getFirst().getFirst() instanceof Number, "SCALAR_NUMBER"); return ((Number) result.getFirst().getFirst()).longValue(); }
    private static void execute(Connection c, String sql) throws SQLException { try (var p = c.createStatement()) { p.execute(sql); } }
    private static void update(Connection c, String sql, int expected, Object... args) throws SQLException { try (var p = c.prepareStatement(sql)) { bind(p, args); require(p.executeUpdate() == expected, "AFFECTED_ROWS"); } }
    private static long insert(Connection c, String sql, Object... args) throws SQLException { try (var p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) { bind(p, args); require(p.executeUpdate() == 1, "INSERT_ONE"); try (var r = p.getGeneratedKeys()) { require(r.next() && r.getLong(1) > 0, "GENERATED_KEY"); long id = r.getLong(1); require(!r.next(), "GENERATED_KEY_ONE"); return id; } } }
    private static long insertUnchecked(Connection c, String sql, Object... args) { try { return insert(c, sql, args); } catch (SQLException e) { throw new CapacitySqlBudget.Rejected("FIXTURE_SQL_FAILURE", e); } }
    private static void bind(PreparedStatement p, Object... args) throws SQLException { for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]); }
    private void pass(String code) { checks.put(code, true); }
    private static void require(boolean condition, String code) { if (!condition) throw rejected(code); }
    private static CapacitySqlBudget.Rejected rejected(String code) { return new CapacitySqlBudget.Rejected(code); }
    static Map<String, Object> safeSqlFailure(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable item = failure; item != null && seen.size() < 16 && seen.add(item); item = item.getCause()) {
            if (item instanceof SQLException sql) return Map.of("failureVendor", sql.getErrorCode(), "failureSqlState", CapacitySqlBudget.safeState(sql.getSQLState()));
        }
        return Map.of();
    }
    private static String fixedFailure(Exception e) {
        if (e instanceof CapacitySqlBudget.Rejected) return e.getMessage();
        if (e instanceof CleanupFailure known) return "CLEANUP_" + known.code().name();
        if (e instanceof DemoAdmissionException known) return known.code().name();
        if (e instanceof SQLException) return "SQL_EXCEPTION";
        return "UNEXPECTED_FAILURE";
    }
}
