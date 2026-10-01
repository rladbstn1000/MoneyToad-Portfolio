package com.potg.verification.admissionlock;

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

/** One owned schema, two restricted roles and four bounded JDBC clients.
 * No Spring Boot context, HTTP, Redis, external AI or retry is initialized. */
public final class AdmissionLockTiDbProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<PosixFilePermission> FILE_MODE = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<String> TABLES = Set.of("users", "cards", "transactions", "budgets", "analysis_job", "peer_transaction_stats", "dummy", "demo_visit", "demo_capacity", "demo_admission_lock");
    private static final String BASELINE_SHA = "6f407517cea0879bf55cd4568a01128890b045d5e72565b855c546c8236c7c35";
    private static final String MIGRATION_SHA = "71271023a254777e12b730522b4f00d721b6664de8fecd5db5f400e6e1563c27";
    private static final String LOCK_SHA = "87f1f7d1130ecf41d530978433c9725ada6351f34d707779a88265fbd48f3a93";
    @FunctionalInterface interface ConnectionFactory { Connection open(String url, Properties properties) throws SQLException; }
    @FunctionalInterface interface DocumentWriter { void write(Path path,Object document) throws Exception; }
    private final ConnectionFactory connectionFactory;
    private final DocumentWriter documentWriter;
    private final Map<String, String> config;
    private final Path resultPath, ledgerPath;
    private final AdmissionLockSqlBudget budget;
    private final Map<String, Object> result = new LinkedHashMap<>();
    private final Map<String, Boolean> checks = new LinkedHashMap<>();
    private final List<Connection> clients = new ArrayList<>();
    private final Set<String> createdTables = new LinkedHashSet<>();
    private final Set<Long> ownedUsers = new LinkedHashSet<>();
    private final String schema, runtimeAccount, cleanupAccount;
    private final Set<String> createdAccounts = new LinkedHashSet<>();
    private final Set<String> attemptedAccounts = new LinkedHashSet<>();
    private boolean schemaAttempted, schemaCreated, cleaned, cleanupComplete;
    private boolean agingAttempted;
    private boolean schemaVerified, admissionVerified, cleanupVerified, privilegesVerified;
    private Connection admin, runtimeA, runtimeB, cleaner;
    private String phase = "INPUT";
    private String sqlFailurePhase, sqlFailureState;
    private int sqlFailureVendor;
    private static final LocalDate ANCHOR = LocalDate.of(2024, 2, 29);

    private AdmissionLockTiDbProbe(Map<String, String> config, Path resultPath, Path ledgerPath) throws Exception {
        this(config,resultPath,ledgerPath,DriverManager::getConnection,AdmissionLockTiDbProbe::durable);
    }
    AdmissionLockTiDbProbe(Map<String,String> config, Path resultPath,Path ledgerPath,
        ConnectionFactory connectionFactory,DocumentWriter documentWriter) throws Exception {
        validate(config);
        this.connectionFactory=connectionFactory; this.documentWriter=documentWriter;
        this.config = config; this.resultPath = resultPath; this.ledgerPath = ledgerPath;
        schema = config.get("schema"); runtimeAccount = config.get("runtimeAccount"); cleanupAccount = config.get("cleanupAccount");
        budget = new AdmissionLockSqlBudget(Long.parseLong(config.get("deadlineEpochMillis")));
        budget.sqlFailureObserver = (vendor, state) -> { sqlFailurePhase = phase; sqlFailureVendor = vendor; sqlFailureState = state; };
        result.put("status", "BLOCKED"); result.put("checks", checks);
        writeLedger();
    }
    public static void main(String[] args) {
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        AdmissionLockTiDbProbe probe = null;
        int exit = 2;
        try {
            require(args.length == 0, "ARGUMENTS_REJECTED");
            Path input = privateFile(System.getenv("ADMISSION_LOCK_CHECK_CONFIG"));
            Path result = privateFile(System.getenv("ADMISSION_LOCK_CHECK_RESULT"));
            Path ledger = privateFile(System.getenv("ADMISSION_LOCK_CHECK_LEDGER"));
            require(input.getParent().equals(result.getParent()) && input.getParent().equals(ledger.getParent()), "PRIVATE_DIRECTORY_MISMATCH");
            Map<String, String> config = new LinkedHashMap<>();
            var root = JSON.readTree(Files.readAllBytes(input));
            require(root.isObject(), "INPUT_OBJECT");
            root.fields().forEachRemaining(e -> config.put(e.getKey(), e.getValue().asText()));
            probe = new AdmissionLockTiDbProbe(config, result, ledger);
            AdmissionLockTiDbProbe running = probe;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.budget.cancelled = true;
                synchronized (running) { running.cleanup(); running.save(); }
            }, "admission-lock-owned-cleanup"));
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
        require(values.keySet().equals(Set.of("TIDB_HOST", "TIDB_PORT", "TIDB_SETUP_USERNAME", "TIDB_SETUP_PASSWORD", "schema", "runtimeAccount", "runtimePassword", "cleanupAccount", "cleanupPassword", "baselinePath", "migrationPath", "lockMigrationPath", "deadlineEpochMillis", "workLimit", "cleanupLimit", "connectionLimit")), "INPUT_KEYS");
        require(values.get("TIDB_HOST").matches("[A-Za-z0-9.-]{1,253}") && !values.get("TIDB_HOST").contains(".."), "HOST_FORMAT");
        int port = Integer.parseInt(values.get("TIDB_PORT")); require(port > 0 && port <= 65535, "PORT_RANGE");
        require(values.get("TIDB_SETUP_USERNAME").matches("[A-Za-z0-9]{1,16}\\.[A-Za-z0-9_]+"), "SETUP_PREFIX");
        String prefix = values.get("TIDB_SETUP_USERNAME").split("\\.", 2)[0] + ".m";
        for (String key : List.of("runtimeAccount", "cleanupAccount")) require(values.get(key).startsWith(prefix) && values.get(key).substring(prefix.length()).matches("[a-f0-9]{14}") && values.get(key).length() <= 32, "ACCOUNT_FORMAT");
        require(!values.get("runtimeAccount").equals(values.get("cleanupAccount")), "ACCOUNTS_DISTINCT");
        require(values.get("schema").matches("mtadmissionlock[a-f0-9]{16}"), "SCHEMA_FORMAT");
        for (String key : List.of("runtimePassword", "cleanupPassword")) require(values.get(key).matches("[A-Za-z0-9_-]{40,128}"), "GENERATED_PASSWORD_FORMAT");
        require(!values.get("TIDB_SETUP_PASSWORD").isBlank(), "SETUP_PASSWORD");
        require("2000".equals(values.get("workLimit")) && "500".equals(values.get("cleanupLimit")) && "4".equals(values.get("connectionLimit")), "FIXED_BUDGET");
        long remaining = Long.parseLong(values.get("deadlineEpochMillis")) - System.currentTimeMillis();
        require(remaining > 0 && remaining <= 900_000, "FINITE_DEADLINE");
    }
    AdmissionLockSqlBudget testBudget() { return budget; }
    Map<String,Object> syntheticExecute() {
        try { run(); result.put("status","PASS"); }
        catch(Exception error) { result.put("status","FAIL"); result.put("failedPhase",phase); result.put("failureCode",fixedFailure(error)); }
        finally { cleanup(); save(); }
        return Collections.unmodifiableMap(result);
    }
    private void run() throws Exception {
        phase = "REVIEWED_DDL";
        List<String> baseline = statements(Path.of(config.get("baselinePath")), BASELINE_SHA, 7);
        List<String> migration = statements(Path.of(config.get("migrationPath")), MIGRATION_SHA, 3);
        List<String> lockMigration = statements(Path.of(config.get("lockMigrationPath")), LOCK_SHA, 2);
        phase = "SETUP";
        admin = connect(config.get("TIDB_SETUP_USERNAME"), config.get("TIDB_SETUP_PASSWORD"), false);
        var initialVersion=rows(admin,"SELECT VERSION()");
        require(initialVersion.size()==1,"ACTUAL_TIDB_REQUIRED");
        result.put("databaseVersion",sanitizedVersion(initialVersion.getFirst().getFirst().toString()));
        require(scalar(admin, "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", schema) == 0, "FRESH_SCHEMA_REQUIRED");
        require(accountAbsent(runtimeAccount) && accountAbsent(cleanupAccount), "FRESH_ACCOUNTS_REQUIRED");
        schemaAttempted = true; writeLedger();
        execute(admin, "CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        schemaCreated = true; writeLedger(); admin.setCatalog(schema);
        for (List<String> group : List.of(baseline, migration, lockMigration)) for (String sql : group) {
            execute(admin, sql); if (sql.startsWith("CREATE TABLE")) createdTables.add(sql.split("\\s+")[2]); writeLedger();
        }
        require(createdTables.equals(TABLES), "TEN_TABLES_CREATED");
        update(admin, "UPDATE demo_capacity SET max_visitors=1 WHERE id=1 AND max_visitors=1000", 1);
        createAccount(runtimeAccount, config.get("runtimePassword")); createAccount(cleanupAccount, config.get("cleanupPassword"));
        grants(runtimeAccount, false); grants(cleanupAccount, true);
        runtimeA = connect(runtimeAccount, config.get("runtimePassword"), true);
        runtimeB = connect(runtimeAccount, config.get("runtimePassword"), true);
        cleaner = connect(cleanupAccount, config.get("cleanupPassword"), true);
        phase = "SCHEMA";
        DemoAdmissionSchema.verify(runtimeA);
        require(DemoAdmissionSchema.verifySnapshot(runtimeA, 1).visitCount() == 0, "INITIAL_COUNT_ZERO");
        var version = rows(runtimeA, "SELECT VERSION()");
        require(version.size() == 1, "ACTUAL_TIDB_REQUIRED");
        result.put("databaseVersion", sanitizedVersion(version.getFirst().getFirst().toString()));
        pass("FOUR_IDENTITY_VERIFIED_TLS_CONNECTIONS");
        require(scalar(cleaner, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'") == 10, "CLEANUP_TEN_TABLES_VISIBLE");
        require(scalar(cleaner, "SELECT COUNT(*) FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE() AND referenced_table_name IS NOT NULL") == 4, "CLEANUP_FOUR_FKS_VISIBLE");
        require(scalar(admin, "SELECT COUNT(*) FROM information_schema.key_column_usage WHERE referenced_table_schema=? AND constraint_schema<>?", schema, schema) == 0, "ADMIN_NO_EXTERNAL_FK");
        pass("SCHEMA_METADATA"); schemaVerified = true;
        phase = "PRIVILEGES";
        privileges(); privilegesVerified = true; pass("LEAST_PRIVILEGE_BEFORE_DATA");
        phase = "NOWAIT"; nowait(); pass("NOWAIT_3572_HY000"); pass("ROLLBACK_COMMIT_RELEASE_LOCK");
        phase = "ROLLBACK"; rollbackAdmission(); pass("BOUND_ADMISSION_ROLLBACK");
        phase = "DATASET";
        var unrelatedBefore = unrelatedSnapshot(runtimeA);
        Dataset safe = seed(runtimeA);
        require(DemoAdmissionSchema.verifySnapshot(runtimeA, 1).visitCount() == 1, "ONE_COMMITTED_VISIT");
        require(scalar(runtimeA,"SELECT COUNT(*) FROM users") == 1 && scalar(runtimeA,"SELECT COUNT(*) FROM cards") == 1
            && scalar(runtimeA,"SELECT COUNT(*) FROM transactions") == 240 && scalar(runtimeA,"SELECT COUNT(*) FROM budgets") == 72, "V1_COUNTS");
        require(scalar(runtimeA,"SELECT COUNT(*) FROM cards WHERE card_no IS NOT NULL OR cvc IS NOT NULL") == 0
            && scalar(runtimeA,"SELECT COUNT(*) FROM users WHERE file_id IS NOT NULL") == 0
            && scalar(runtimeA,"SELECT COUNT(*) FROM budgets WHERE initial_amount IS NOT NULL OR initial_file_id IS NOT NULL OR predicted_at IS NOT NULL") == 0, "V1_NULL_INVARIANTS");
        require(unrelatedBefore.equals(unrelatedSnapshot(runtimeA)), "UNRELATED_TABLES_UNCHANGED"); pass("ONE_V1_DATASET_315_ROWS");
        phase = "CAPACITY_FULL";
        var fullBefore = snapshot(runtimeA);
        try { transaction(runtimeB, store -> { store.claimSlot(); return null; }, false); throw rejected("FULL_NOT_REJECTED"); }
        catch (DemoAdmissionException e) { require(e.code() == DemoAdmissionException.Code.DEMO_CAPACITY_FULL, "FULL_CLASSIFICATION"); }
        require(fullBefore.equals(snapshot(runtimeA)), "FULL_NO_ADDITIONAL_ROWS");
        admissionVerified = true; pass("PRODUCT_COUNT_AND_FULL");
        phase = "MUTABLE_CATEGORY";
        update(runtimeA,"UPDATE transactions SET category=? WHERE id=? AND card_id=?",1,"마트 / 편의점",safe.practice,safe.card);
        phase = "AGE_OWNED_FIXTURE";
        agingAttempted = true; writeLedger();
        update(admin,"UPDATE demo_visit SET created_at=UTC_TIMESTAMP(6)-INTERVAL 25 HOUR,session_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 HOUR WHERE user_id=?",1,safe.user);
        phase = "CLEANUP_VERIFY_DRY_RUN";
        var allBefore = snapshot(runtimeA); int dml = budget.dml;
        var verified = cleanupService(Mode.VERIFY);
        require(verified.countBefore() == 1 && verified.deleted() == 0 && budget.dml == dml, "VERIFY_DML_ZERO");
        var dry = cleanupService(Mode.DRY_RUN);
        require(dry.candidates() == 1 && dry.deleted() == 0 && budget.dml == dml && snapshot(runtimeA).equals(allBefore), "DRY_RUN_UNCHANGED");
        pass("VERIFY_AND_DRY_RUN_ZERO_DML"); pass("MUTABLE_CATEGORY_ACCEPTED");
        phase = "CLEANUP_APPLY";
        int updates = budget.updates; int deletes = budget.deletes;
        var applied = cleanupService(Mode.APPLY);
        require(applied.candidates() == 1 && applied.deleted() == 1 && applied.countBefore() == 1 && applied.countAfter() == 0, "CLEANUP_EXACT_ONE_VISIT");
        require(budget.updates == updates && budget.deletes - deletes == 5, "CLEANUP_FIVE_EXACT_DELETES_NO_UPDATE");
        for (String table : List.of("users","cards","transactions","budgets","demo_visit")) require(scalar(runtimeA,"SELECT COUNT(*) FROM "+table) == 0,"DATASET_ABSENT");
        require(unrelatedBefore.equals(unrelatedSnapshot(runtimeA)), "UNRELATED_ALL_COLUMNS_UNCHANGED");
        require(DemoAdmissionSchema.verifySnapshot(runtimeA,1).visitCount() == 0,"FINAL_COUNT_ZERO");
        cleanupVerified = true; pass("REAL_RESTRICTED_CLEANUP_APPLY"); pass("UNRELATED_TABLES_SINGLETONS_UNCHANGED");
    }
    static String sanitizedVersion(String raw) {
        var match = java.util.regex.Pattern.compile("(?i)([0-9]+\\.[0-9]+\\.[0-9]+)-TiDB-v([0-9]+\\.[0-9]+\\.[0-9]+)").matcher(raw);
        require(match.find(),"ACTUAL_TIDB_REQUIRED");
        return match.group(1)+"-TiDB-v"+match.group(2);
    }
    private static Map<String,List<List<Object>>> unrelatedSnapshot(Connection c) throws SQLException {
        var result = new TreeMap<String,List<List<Object>>>();
        for (String table : List.of("analysis_job","peer_transaction_stats","dummy","demo_capacity","demo_admission_lock")) result.put(table,rows(c,"SELECT * FROM "+table+" ORDER BY id"));
        return result;
    }
    private void privileges() throws Exception {
        for (Connection c : List.of(runtimeA,cleaner)) {
            require(scalar(c,"SELECT max_visitors FROM demo_capacity WHERE id=1")==1,"CAPACITY_SELECT");
            require(scalar(c,"SELECT id FROM demo_admission_lock WHERE id=1")==1,"LOCK_SELECT");
            denied(c,"UPDATE demo_capacity SET max_visitors=max_visitors WHERE id=-1");
            denied(c,"INSERT INTO demo_admission_lock(id) SELECT 2 WHERE 1=0");
            denied(c,"DELETE FROM demo_admission_lock WHERE id=-1");
            denied(c,"ALTER TABLE users COMMENT='owned-privilege-probe'");
            for (String table : List.of("analysis_job","peer_transaction_stats","dummy")) {
                denied(c,emptyInsert(table));
                denied(c,"UPDATE "+table+" SET id=id WHERE id=-1");
                denied(c,"DELETE FROM "+table+" WHERE id=-1");
            }
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); c.setAutoCommit(false);
            try { DemoAdmissionSchema.verifyTransactionMode(c); require(scalar(c,"SELECT id FROM demo_admission_lock WHERE id=1 FOR UPDATE NOWAIT")==1,"ROLE_LOCK_SUCCESS"); }
            finally { c.rollback(); c.setAutoCommit(true); }
        }
        for (String table : List.of("users","cards","transactions","budgets","demo_visit")) {
            String column=table.equals("demo_visit")?"user_id":"id";
            update(runtimeA,emptyInsert(table),0);
            denied(runtimeA,"DELETE FROM "+table+" WHERE "+column+"=-1");
            require(scalar(cleaner,"SELECT COUNT(*) FROM "+table)==0,"CLEANUP_SELECT_EMPTY");
            update(cleaner,"DELETE FROM "+table+" WHERE "+column+"=-1",0);
            denied(cleaner,emptyInsert(table));
            denied(cleaner,"UPDATE "+table+" SET "+column+"="+column+" WHERE "+column+"=-1");
        }
        for (String table : List.of("users","transactions","budgets")) update(runtimeA,"UPDATE "+table+" SET id=id WHERE id=-1",0);
        for (String table : List.of("cards","demo_visit")) {
            String column=table.equals("demo_visit")?"user_id":"id";
            denied(runtimeA,"UPDATE "+table+" SET "+column+"="+column+" WHERE "+column+"=-1");
        }
        for (String table : List.of("users","cards","transactions","budgets","demo_visit","analysis_job","peer_transaction_stats","dummy")) require(scalar(admin,"SELECT COUNT(*) FROM "+table)==0,"PRIVILEGE_PROBE_ZERO_ROWS");
    }
    static String emptyInsert(String table) {
        return switch(table) {
            case "users" -> "INSERT INTO users(created_at,email,name) SELECT UTC_TIMESTAMP(6),'fixture@moneytoad.invalid','fixture' WHERE 1=0";
            case "cards" -> "INSERT INTO cards(created_at,user_id) SELECT UTC_TIMESTAMP(6),-1 WHERE 1=0";
            case "transactions" -> "INSERT INTO transactions(created_at,amount) SELECT UTC_TIMESTAMP(6),1 WHERE 1=0";
            case "budgets" -> "INSERT INTO budgets(amount) SELECT 1 WHERE 1=0";
            case "demo_visit" -> "INSERT INTO demo_visit(user_id,created_at,scenario_version,session_expires_at) SELECT -1,UTC_TIMESTAMP(6),'V1',UTC_TIMESTAMP(6) WHERE 1=0";
            case "analysis_job" -> "INSERT INTO analysis_job(user_id) SELECT -1 WHERE 1=0";
            case "peer_transaction_stats" -> "INSERT INTO peer_transaction_stats(age_group) SELECT 1 WHERE 1=0";
            case "dummy" -> "INSERT INTO dummy(category,merchant_name,min_amount,max_amount) SELECT 'fixture','fixture',1,1 WHERE 1=0";
            default -> throw rejected("TABLE_NOT_REVIEWED");
        };
    }
    private void nowait() throws Exception {
        runtimeA.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); runtimeA.setAutoCommit(false);
        try {
            DemoAdmissionSchema.verifyTransactionMode(runtimeA);
            scalar(runtimeA, "SELECT id FROM demo_admission_lock WHERE id=1 FOR UPDATE NOWAIT");
            long started=System.nanoTime();
            try { transaction(runtimeB, store -> { store.claimSlot(); return null; }, false); throw rejected("NOWAIT_ACQUIRED"); }
            catch (DemoAdmissionException error) { require(error.code() == DemoAdmissionException.Code.DEMO_ADMISSION_BUSY, "NOWAIT_PRODUCT_MAPPING"); }
            result.put("nowaitElapsedMillis",java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
            require(budget.nowaitVendor == 3572 && "HY000".equals(budget.nowaitState), "NOWAIT_VENDOR_STATE");
        } finally { runtimeA.rollback(); runtimeA.setAutoCommit(true); }
        transaction(runtimeB, store -> { require(store.claimSlot()==0,"ROLLBACK_RELEASE"); return null; },false);
        transaction(runtimeA, store -> { require(store.claimSlot()==0,"COMMIT_RELEASE"); return null; },false);
        transaction(runtimeB, store -> { require(store.claimSlot()==0,"A_COMMIT_RELEASE"); return null; },false);
    }
    private void rollbackAdmission() throws Exception {
        transaction(runtimeA, store -> {
            require(store.claimSlot()==0,"ROLLBACK_INITIAL_ZERO");
            long user = insertUnchecked(runtimeA,"INSERT INTO users(created_at,email,name) VALUES(UTC_TIMESTAMP(6),?,?)","rollback-fixture@moneytoad.invalid","합성 롤백");
            ownedUsers.add(user); ledgerUnchecked();
            store.recordVisit(user,Instant.now().plusSeconds(3600));
            // Intentionally stop before verifyIntegrity: rollback must not run beforeCommit.
            return null;
        },true);
        require(DemoAdmissionSchema.verifySnapshot(runtimeA,1).visitCount()==0 && scalar(runtimeA,"SELECT COUNT(*) FROM users")==0 && scalar(runtimeA,"SELECT COUNT(*) FROM demo_visit")==0,"ROLLBACK_ALL_ABSENT");
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
        return tx.execute(status -> { T result = work.apply(new DemoAdmissionStore(new JdbcTemplate(ds), 1)); if (rollback) status.setRollbackOnly(); return result; });
    }
    private DemoCleanupService.Result cleanupService(Mode mode) throws SQLException {
        return withConfirmedCleanupReset(cleaner, () -> new DemoCleanupService().execute(cleaner, mode, 1, 1));
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
    private void grants(String account, boolean cleanupRole) throws SQLException {
        for (String table : TABLES) execute(admin, "GRANT SELECT ON `" + schema + "`." + table + " TO '" + account + "'@'%'");
        for (String table : List.of("users", "cards", "transactions", "budgets", "demo_visit")) execute(admin, "GRANT " + (cleanupRole ? "DELETE" : "INSERT") + " ON `" + schema + "`." + table + " TO '" + account + "'@'%'");
        if (!cleanupRole) for (String table : List.of("users", "transactions", "budgets")) execute(admin, "GRANT UPDATE ON `" + schema + "`." + table + " TO '" + account + "'@'%'");
        execute(admin, "GRANT UPDATE ON `" + schema + "`.demo_admission_lock TO '" + account + "'@'%'");
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
        Connection connection = budget.observe(connectionFactory.open(url, properties)); clients.add(connection);
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
        String operation = negativeOperation(sql);
        try { requirePrivilegeDenied(c,sql); }
        catch (SQLException | RuntimeException failure) {
            result.put("failedNegativeRole",c==cleaner?"CLEANUP":"RUNTIME");
            result.put("failedNegativeOperation",operation);
            throw failure;
        }
    }
    static boolean expectedPrivilegeDenial(SQLException error) {
        return (Set.of(1142,1143).contains(error.getErrorCode()) && "42000".equals(error.getSQLState()))
            || (error.getErrorCode()==8121 && "HY000".equals(error.getSQLState()));
    }
    static void requirePrivilegeDenied(Connection c,String sql) throws SQLException {
        try { execute(c,sql); }
        catch (SQLException error) {
            if(expectedPrivilegeDenial(error)) return;
            throw new AdmissionLockSqlBudget.Rejected("EXPECTED_PRIVILEGE_DENIAL",error);
        }
        throw rejected("EXCESS_PRIVILEGE");
    }
    static String negativeOperation(String sql) {
        String normalized=sql.strip().toUpperCase(Locale.ROOT);
        if(normalized.startsWith("ALTER TABLE USERS ")) return "USERS_ALTER";
        for(String verb:List.of("INSERT","UPDATE","DELETE")) {
            String prefix=verb.equals("INSERT")?"INSERT INTO ":verb.equals("DELETE")?"DELETE FROM ":"UPDATE ";
            for(String table:TABLES) {
                String head=prefix+table.toUpperCase(Locale.ROOT);
                if(normalized.startsWith(head+" ") || normalized.startsWith(head+"(")) return table.toUpperCase(Locale.ROOT)+"_"+verb;
            }
        }
        throw rejected("NEGATIVE_PROBE_NOT_REVIEWED");
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

                    for (String table : List.of("analysis_job", "transactions", "budgets", "cards", "demo_visit", "users", "peer_transaction_stats", "dummy", "demo_capacity", "demo_admission_lock")) {
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
        ledger.put("agingAttempted", agingAttempted);
        ledger.put("cleanupStarted", cleaned); ledger.put("cleanupComplete", cleanupComplete);
        documentWriter.write(ledgerPath, ledger);
    }
    private void ledgerUnchecked() { try { writeLedger(); } catch (Exception ignored) { throw rejected("LEDGER_WRITE_FAILED"); } }
    private void save() {
        result.put("schemaVerified", schemaVerified); result.put("admissionVerified", admissionVerified); result.put("cleanupVerified", cleanupVerified); result.put("privilegesVerified", privilegesVerified);
        result.put("cleanupComplete", cleanupComplete); result.put("connectionAttempts", budget.connections); result.put("workCommands", budget.work); result.put("cleanupCommands", budget.cleanup);
        result.put("nowaitVendor", budget.nowaitVendor); result.put("nowaitSqlState", budget.nowaitState);
        try { documentWriter.write(resultPath, result); } catch (Exception ignored) { }
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
    private static long insertUnchecked(Connection c, String sql, Object... args) { try { return insert(c, sql, args); } catch (SQLException e) { throw new AdmissionLockSqlBudget.Rejected("FIXTURE_SQL_FAILURE", e); } }
    private static void bind(PreparedStatement p, Object... args) throws SQLException { for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]); }
    private void pass(String code) { checks.put(code, true); }
    private static void require(boolean condition, String code) { if (!condition) throw rejected(code); }
    private static AdmissionLockSqlBudget.Rejected rejected(String code) { return new AdmissionLockSqlBudget.Rejected(code); }
    static Map<String, Object> safeSqlFailure(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable item = failure; item != null && seen.size() < 16 && seen.add(item); item = item.getCause()) {
            if (item instanceof SQLException sql) return Map.of("failureVendor", sql.getErrorCode(), "failureSqlState", AdmissionLockSqlBudget.safeState(sql.getSQLState()));
        }
        return Map.of();
    }
    private static String fixedFailure(Exception e) {
        if (e instanceof AdmissionLockSqlBudget.Rejected) return e.getMessage();
        if (e instanceof CleanupFailure known) return "CLEANUP_" + known.code().name();
        if (e instanceof DemoAdmissionException known) return known.code().name();
        if (e instanceof SQLException) return "SQL_EXCEPTION";
        return "UNEXPECTED_FAILURE";
    }
}
