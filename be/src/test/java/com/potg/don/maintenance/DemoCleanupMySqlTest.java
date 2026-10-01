package com.potg.don.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import com.potg.don.auth.DemoAuthHttpTestSupport;
import com.potg.don.demo.seed.DemoSeedScenario;
import com.potg.don.maintenance.CleanupOptions.Mode;

/** Real owned MySQL rows; no Redis requests or Spring startup occur inside the cleanup executable. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class DemoCleanupMySqlTest {
    DemoAuthHttpTestSupport support;
    DemoAuthHttpTestSupport.Started started;
    DataSource dataSource;
    JdbcTemplate jdbc;
    DemoCleanupService service = new DemoCleanupService();

    @BeforeAll void startOwnedSchema() throws Exception {
        support = new DemoAuthHttpTestSupport();
        started = support.startDemo();
        assertThat(started.failure() == null).as("Owned validate-only demo fixture starts").isTrue();
        dataSource = started.context().getBean(DataSource.class);
        jdbc = new JdbcTemplate(dataSource);
    }
    @BeforeEach void resetBefore() { reset(); }
    @AfterEach void resetAfter() { reset(); }
    @AfterAll void closeOwnedContext() {
        try { if (started != null) started.close(); }
        finally { if (support != null) support.close(); }
    }

    @Test void dryRunAndVerifyIssueNoDmlAndPreserveEveryColumn() throws Exception {
        seed();
        var before = snapshot();
        AtomicInteger dml = new AtomicInteger();
        try (var real = dataSource.getConnection()) {
            var dry = service.execute(observe(real, sql -> { if (isDml(sql)) dml.incrementAndGet(); }, null), Mode.DRY_RUN, 10, 1000);
            assertThat(dry.candidates()).isEqualTo(1); assertThat(dry.deleted()).isZero();
        }
        try (var real = dataSource.getConnection()) {
            var verify = service.execute(observe(real, sql -> { if (isDml(sql)) dml.incrementAndGet(); }, null), Mode.VERIFY, 10, 1000);
            assertThat(verify.countBefore()).isEqualTo(1); assertThat(verify.deleted()).isZero();
        }
        assertThat(dml.get()).isZero(); assertThat(snapshot().equals(before)).isTrue();
    }

    @Test void youngVisitAndUnexpiredSessionRemainUntouched() throws Exception {
        long young = seed(); long active = seed();
        jdbc.update("UPDATE demo_visit SET created_at=UTC_TIMESTAMP(6)-INTERVAL 23 HOUR WHERE user_id=?", young);
        jdbc.update("UPDATE demo_visit SET session_expires_at=UTC_TIMESTAMP(6)+INTERVAL 1 MINUTE WHERE user_id=?", active);
        var before = snapshot();
        assertThat(run(Mode.APPLY).deleted()).isZero();
        assertThat(snapshot().equals(before)).isTrue();
    }

    @Test void candidateAt24HourBoundaryUsesDatabaseUtcAndIsEligible() throws Exception {
        long visitor = seed();
        jdbc.update("UPDATE demo_visit SET created_at=UTC_TIMESTAMP(6)-INTERVAL 24 HOUR WHERE user_id=?", visitor);
        assertThat(run(Mode.APPLY).deleted()).isEqualTo(1);
        assertThat(count("users")).isZero();
    }

    @Test void oldSafeDatasetDeletesExactRowsAndMarkerCountWithoutFurtherDeleteOnRepeat() throws Exception {
        seed();
        AtomicInteger forbiddenWrites = new AtomicInteger();
        DemoCleanupService.Result result;
        try (Connection real = dataSource.getConnection()) {
            result = service.execute(observe(real, sql -> {
                if (sql.matches("(?is)\\s*(INSERT|UPDATE).*?")) forbiddenWrites.incrementAndGet();
            }, null), Mode.APPLY, 10, 1000);
        }
        assertThat(forbiddenWrites.get()).isZero();
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.countBefore()).isEqualTo(1); assertThat(result.countAfter()).isZero();
        for (String table : List.of("users", "cards", "transactions", "budgets", "demo_visit")) assertThat(count(table)).isZero();
        var again = run(Mode.APPLY);
        assertThat(again.deleted()).isZero(); assertThat(again.countAfter()).isZero();
    }

    @Test void categoryBudgetAndProfileEditsRemainSafeWithoutUsingEmailForProvenance() throws Exception {
        long visitor = seed();
        jdbc.update("UPDATE transactions SET category='마트 / 편의점' WHERE card_id=(SELECT id FROM cards WHERE user_id=?)", visitor);
        jdbc.update("UPDATE budgets SET amount=12345,is_overridden=1,overridden_at=UTC_TIMESTAMP(6) WHERE user_id=?", visitor);
        jdbc.update("UPDATE users SET name='Renamed fixture',email=?,age=42,gender='other' WHERE id=?", "changed-" + UUID.randomUUID() + "@example.invalid", visitor);
        assertThat(run(Mode.APPLY).deleted()).isEqualTo(1);
    }

    static String financeSql(String field) {
        return switch (field) {
            case "card_no", "cvc" -> "UPDATE cards SET " + field + "='synthetic-nonfinancial-marker' WHERE user_id=?";
            case "user_file" -> "UPDATE users SET file_id='synthetic-invalid-file' WHERE id=?";
            case "budget_file" -> "UPDATE budgets SET initial_file_id='synthetic-invalid-file' WHERE user_id=? LIMIT 1";
            case "prediction" -> "UPDATE budgets SET predicted_at=UTC_TIMESTAMP(6) WHERE user_id=? LIMIT 1";
            default -> throw new AssertionError();
        };
    }
    @ParameterizedTest @ValueSource(strings = {"card_no", "cvc", "user_file", "budget_file", "prediction"})
    void anyFinancialSourceFieldAbortsWholeBatchBeforeDelete(String field) throws Exception {
        seed(); long unsafe = seed();
        jdbc.update(financeSql(field), unsafe);
        assertRejectedUnchanged("UNSAFE_BATCH");
    }

    @Test void analysisJobWithoutForeignKeyAbortsWholeBatch() throws Exception {
        seed(); long unsafe = seed();
        jdbc.update("INSERT INTO analysis_job(user_id,status) VALUES (?,'QUEUED')", unsafe);
        assertRejectedUnchanged("UNSAFE_BATCH");
    }

    @Test void unsupportedVersionAbortsInsteadOfSkippingOldCandidate() throws Exception {
        long unsafe = seed(); seed();
        jdbc.update("UPDATE demo_visit SET scenario_version='FUTURE' WHERE user_id=?", unsafe);
        assertRejectedUnchanged("UNSAFE_BATCH");
    }

    @ParameterizedTest @ValueSource(strings = {"transactions", "budgets", "immutable"})
    void partialOrAlteredImmutableSeedAbortsEntireBatch(String change) throws Exception {
        seed(); long unsafe = seed();
        if (change.equals("transactions")) jdbc.update("DELETE FROM transactions WHERE card_id=(SELECT id FROM cards WHERE user_id=?) LIMIT 1", unsafe);
        else if (change.equals("budgets")) jdbc.update("DELETE FROM budgets WHERE user_id=? LIMIT 1", unsafe);
        else jdbc.update("UPDATE transactions SET amount=amount+1 WHERE card_id=(SELECT id FROM cards WHERE user_id=?) LIMIT 1", unsafe);
        assertRejectedUnchanged("UNSAFE_BATCH");
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0})
    void nonPositiveMaximumIsRejectedBeforeAnyDelete(int invalidMaximum) throws Exception {
        seed(); jdbc.update("UPDATE demo_capacity SET max_visitors=? WHERE id=1", invalidMaximum);
        assertRejectedUnchanged("INTEGRITY_REJECTED");
    }

    @Test void markerCountAboveMatchingMaximumIsRejectedBeforeAnyDelete() throws Exception {
        seed(); seed();
        jdbc.update("UPDATE demo_capacity SET max_visitors=1 WHERE id=1");
        var before = snapshot(); AtomicInteger dml = new AtomicInteger();
        try (Connection real = dataSource.getConnection()) {
            assertThatThrownBy(() -> service.execute(observe(real, sql -> { if (isDml(sql)) dml.incrementAndGet(); }, null), Mode.APPLY, 10, 1))
                .hasMessage("INTEGRITY_REJECTED");
        }
        assertThat(dml.get()).isZero();
        assertThat(snapshot().equals(before)).isTrue();
    }

    @Test void maxMismatchMissingAndMultipleCapacityRowsFailClosed() throws Exception {
        seed(); jdbc.update("UPDATE demo_capacity SET max_visitors=999 WHERE id=1");
        assertRejectedUnchanged("INTEGRITY_REJECTED");
        jdbc.update("UPDATE demo_capacity SET max_visitors=1000 WHERE id=1");
        jdbc.update("INSERT INTO demo_capacity(id,max_visitors) VALUES (2,1000)");
        assertRejectedUnchanged("INTEGRITY_REJECTED");
        jdbc.update("DELETE FROM demo_capacity");
        assertRejectedUnchanged("INTEGRITY_REJECTED");
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "extra", "changed"})
    void alteredAdmissionLockFailsClosedWithoutRepairOrDelete(String change) throws Exception {
        seed();
        switch (change) {
            case "missing" -> jdbc.update("DELETE FROM demo_admission_lock");
            case "extra" -> jdbc.update("INSERT INTO demo_admission_lock(id) VALUES (2)");
            case "changed" -> jdbc.update("UPDATE demo_admission_lock SET id=2 WHERE id=1");
            default -> throw new AssertionError();
        }
        var before = snapshot();
        for (Mode mode : Mode.values()) {
            AtomicInteger dml = new AtomicInteger();
            try (Connection real = dataSource.getConnection()) {
                assertThatThrownBy(() -> service.execute(observe(real, sql -> {
                    if (isDml(sql)) dml.incrementAndGet();
                }, null), mode, 10, 1000)).hasMessage("INTEGRITY_REJECTED");
            }
            assertThat(dml.get()).isZero();
            assertThat(snapshot().equals(before)).isTrue();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"commit", "rollback"})
    void admissionLockAutomaticallyReleasesAtTransactionEnd(String completion) throws Exception {
        seed();
        var before = snapshot();
        try (Connection owner = dataSource.getConnection(); Connection contender = dataSource.getConnection()) {
            owner.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            owner.setAutoCommit(false);
            try (var statement = owner.prepareStatement("SELECT id FROM demo_admission_lock WHERE id=1 FOR UPDATE NOWAIT");
                 var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1);
            }
            contender.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            contender.setAutoCommit(false);
            try (var statement = contender.prepareStatement("SELECT id FROM demo_admission_lock WHERE id=1 FOR UPDATE NOWAIT")) {
                assertThatThrownBy(statement::executeQuery).isInstanceOfSatisfying(SQLException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(3572);
                    assertThat(error.getSQLState()).isEqualTo("HY000");
                    assertThat(DemoCleanupService.isExplicitNowait(error)).isTrue();
                });
            }
            contender.rollback(); contender.setAutoCommit(true);
            assertThatThrownBy(() -> run(Mode.APPLY)).hasMessage("LOCK_BUSY");
            assertThat(snapshot().equals(before)).isTrue();
            if (completion.equals("commit")) owner.commit(); else owner.rollback();
            assertThat(run(Mode.APPLY).deleted()).isEqualTo(1);
            assertThat(count("demo_visit")).isZero();
        }
    }

    @Test void realMidDeleteSqlFailureRollsBackAlreadyDeletedTransactions() throws Exception {
        seed();
        jdbc.execute("CREATE TRIGGER cleanup_reject_budget BEFORE DELETE ON budgets FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic cleanup failure'");
        try { assertRejectedUnchanged("SQL_FAILURE"); }
        finally { jdbc.execute("DROP TRIGGER cleanup_reject_budget"); }
    }

    @Test void affectedRowMismatchAfterActualDeleteRollsBackAllRowsAndMarkers() throws Exception {
        seed(); var before = snapshot();
        try (Connection real = dataSource.getConnection()) {
            Connection boundary = faultStatement(real, "DELETE FROM budgets", false);
            assertThatThrownBy(() -> service.execute(boundary, Mode.APPLY, 10, 1000)).hasMessage("ROW_COUNT_MISMATCH");
        }
        assertThat(snapshot().equals(before)).isTrue();
    }

    @Test void unexpectedMarkerDeletionDetectedByFinalCountRollsBackWholeBatch() throws Exception {
        seed(); long young = seed();
        jdbc.update("UPDATE demo_visit SET created_at=UTC_TIMESTAMP(6)-INTERVAL 23 HOUR WHERE user_id=?", young);
        var before = snapshot();
        jdbc.execute("CREATE TRIGGER cleanup_extra_marker BEFORE DELETE ON users FOR EACH ROW DELETE FROM demo_visit WHERE created_at>UTC_TIMESTAMP(6)-INTERVAL 24 HOUR");
        try {
            try (Connection real = dataSource.getConnection()) {
                assertThatThrownBy(() -> service.execute(real, Mode.APPLY, 10, 1000)).hasMessage("INTEGRITY_REJECTED");
            }
            assertThat(snapshot().equals(before)).isTrue();
        } finally { jdbc.execute("DROP TRIGGER cleanup_extra_marker"); }
    }

    @Test void commitAcknowledgementLossIsUnknownAndIsNeverAutomaticallyRetriedOrRefunded() throws Exception {
        seed(); AtomicInteger commits = new AtomicInteger();
        try (Connection real = dataSource.getConnection()) {
            Connection lost = observe(real, ignored -> { }, () -> {
                commits.incrementAndGet(); real.commit(); throw new SQLException("synthetic response loss", "08006");
            });
            assertThatThrownBy(() -> service.execute(lost, Mode.APPLY, 10, 1000)).hasMessage("COMMIT_UNKNOWN");
        }
        assertThat(commits.get()).isEqualTo(1);
        assertThat(count("demo_visit")).isZero();
        assertThat(run(Mode.VERIFY).countAfter()).isZero();
    }

    @Test void legacyUserAndCompleteUnmarkedDatasetRemainEveryColumnUnchanged() throws Exception {
        seed(); long legacy = seed();
        jdbc.update("DELETE FROM demo_visit WHERE user_id=?", legacy);
        var legacyBefore = ownedSnapshot(legacy);
        assertThat(run(Mode.APPLY).deleted()).isEqualTo(1);
        assertThat(ownedSnapshot(legacy).equals(legacyBefore)).isTrue();
        assertThat(count("users")).isEqualTo(1);
    }

    @Test void oldestFirstLimitOnlyDeletesSelectedVisitAndNeverMoreThan100() throws Exception {
        long older = seed(); long younger = seed();
        jdbc.update("UPDATE demo_visit SET created_at=UTC_TIMESTAMP(6)-INTERVAL 72 HOUR WHERE user_id=?", older);
        try (Connection connection = dataSource.getConnection()) {
            assertThat(service.execute(connection, Mode.APPLY, 1, 1000).deleted()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=?", Long.class, older)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=?", Long.class, younger)).isEqualTo(1);
        try (Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> service.execute(connection, Mode.APPLY, 101, 1000)).hasMessage("INPUT_REJECTED");
        }
        assertThat(count("demo_visit")).isEqualTo(1);
    }

    @Test void cleanupVersusCleanupNowaitLoserPerformsNoWrites() throws Exception {
        seed();
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        AtomicInteger loserDml = new AtomicInteger();
        try {
            var winner = executor.submit(() -> {
                try (Connection real = dataSource.getConnection()) {
                    return service.execute(observe(real, sql -> {
                        if (sql.startsWith("SELECT user_id,created_at")) {
                            locked.countDown();
                            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test latch"); }
                            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
                        }
                    }, null), Mode.APPLY, 10, 1000);
                }
            });
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            try (Connection real = dataSource.getConnection()) {
                assertThatThrownBy(() -> service.execute(observe(real, sql -> { if (isDml(sql)) loserDml.incrementAndGet(); }, null), Mode.APPLY, 10, 1000)).hasMessage("LOCK_BUSY");
            }
            assertThat(loserDml.get()).isZero();
            release.countDown();
            assertThat(winner.get(10, TimeUnit.SECONDS).deleted()).isEqualTo(1);
            assertThat(count("demo_visit")).isZero();
        } finally { release.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void actualCleanupTransactionBlocksActualLoginWithoutUserOrRedisCreation() throws Exception {
        seed();
        var before = snapshot();
        var redis = started.context().getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
        var keysBefore = redis.keys("demo:session:*");
        var auth = started.context().getBean(com.potg.don.auth.demo.DemoAuthService.class);
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var cleanup = executor.submit(() -> {
                try (Connection real = dataSource.getConnection()) {
                    return service.execute(observe(real, sql -> {
                        if (sql.startsWith("SELECT user_id,created_at")) {
                            locked.countDown();
                            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test latch"); }
                            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
                        }
                    }, null), Mode.APPLY, 10, 1000);
                }
            });
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> auth.login(null)).hasMessage("DEMO_ADMISSION_BUSY");
            assertThat(snapshot().equals(before)).isTrue();
            assertThat(redis.keys("demo:session:*").equals(keysBefore)).isTrue();
            release.countDown();
            assertThat(cleanup.get(10, TimeUnit.SECONDS).deleted()).isEqualTo(1);
            assertThat(run(Mode.VERIFY).countAfter()).isZero();
        } finally { release.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void visibleCrossSchemaCascadeIsRejectedWithoutReadingOrDeletingOtherSchemaRows() throws Exception {
        long visitor = seed();
        String url = System.getenv("A1_DB_URL");
        java.net.URI target = java.net.URI.create(url.substring(5));
        assertThat(url.startsWith("jdbc:mysql://") && "127.0.0.1".equals(target.getHost()) && target.getPort() > 0
            && target.getPort() != 3306 && target.getPath().matches("/moneytoad_a1_[a-z0-9_]+") && target.getUserInfo() == null).isTrue();
        String second = "moneytoad_a1_cleanup_fk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String source = target.getPath().substring(1);
        boolean created = false;
        try (Connection admin = java.sql.DriverManager.getConnection(url, System.getenv("A1_ADMIN_DB_USERNAME"), System.getenv("A1_ADMIN_DB_PASSWORD"))) {
            try {
                grant(admin, "CREATE DATABASE `" + second + "`"); created = true;
                grant(admin, "CREATE TABLE `" + second + "`.owned_child(id BIGINT PRIMARY KEY,user_id BIGINT,FOREIGN KEY(user_id) REFERENCES `" + source + "`.users(id) ON DELETE CASCADE)");
                try (var insert = admin.prepareStatement("INSERT INTO `" + second + "`.owned_child(id,user_id) VALUES (1,?)")) { insert.setLong(1, visitor); insert.executeUpdate(); }
                var before = snapshot();
                assertThatThrownBy(() -> service.execute(admin, Mode.APPLY, 10, 1000)).hasMessage("SCHEMA_REJECTED");
                assertThat(snapshot().equals(before)).isTrue();
                try (var query = admin.prepareStatement("SELECT COUNT(*) FROM `" + second + "`.owned_child"); var rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(1);
                }
            } finally {
                // Exact generated schema only; never drop or inspect any pre-existing schema.
                if (created) { admin.rollback(); admin.setAutoCommit(true); grant(admin, "DROP DATABASE `" + second + "`"); }
            }
        } catch (SQLException error) { throw new AssertionError("OWNED_CROSS_SCHEMA_CONTRACT_FAILED"); }
    }

    @Test void unexpectedForeignKeyOrExtraTableIsRejectedBeforeDelete() throws Exception {
        seed();
        jdbc.execute("CREATE TABLE cleanup_unreviewed (id BIGINT PRIMARY KEY, user_id BIGINT, FOREIGN KEY(user_id) REFERENCES users(id))");
        try { assertRejectedUnchanged("SCHEMA_REJECTED"); }
        finally { jdbc.execute("DROP TABLE cleanup_unreviewed"); }
    }

    @ParameterizedTest @ValueSource(strings = {"runtime", "cleanup"})
    void restrictedRuntimeAndCleanupRolesCannotCrossWriteOrDdlBoundaries(String selectedRole) throws Exception {
        String url = System.getenv("A1_DB_URL");
        java.net.URI target = java.net.URI.create(url.substring("jdbc:".length()));
        assertThat(url.startsWith("jdbc:mysql://") && "127.0.0.1".equals(target.getHost())
            && target.getPort() > 0 && target.getPort() != 3306
            && target.getUserInfo() == null && target.getPath().matches("/moneytoad_a1_[a-z0-9_]+"))
            .as("Owned non-default local MySQL only").isTrue();
        String adminUser = System.getenv("A1_ADMIN_DB_USERNAME"), adminPassword = System.getenv("A1_ADMIN_DB_PASSWORD");
        String password = System.getenv("A1_DB_PASSWORD");
        assertThat(adminUser != null && adminPassword != null && password != null).as("Explicit owned-admin runner inputs").isTrue();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String runtime = "mt_runtime_" + suffix, cleanup = "mt_cleanup_" + suffix;
        String schema = target.getPath().substring(1);
        String quotedSchema = "`" + schema + "`";
        String grantSchema = "`" + schema.replace("_", "\\_") + "`";
        List<String> created = new ArrayList<>();
        String phase = "ADMIN_CONNECT";
        try (Connection admin = java.sql.DriverManager.getConnection(url, adminUser, adminPassword)) {
            try {
                phase = "CREATE_AND_GRANT_ROLES";
                for (String account : List.of(runtime, cleanup)) {
                    try (PreparedStatement create = admin.prepareStatement("CREATE USER ?@'%' IDENTIFIED BY ?")) {
                        create.setString(1, account); create.setString(2, password); create.executeUpdate(); created.add(account);
                    }
                    grant(admin, "GRANT SELECT ON " + grantSchema + ".* TO '" + account + "'@'%'");
                    grant(admin, "GRANT UPDATE ON " + quotedSchema + ".demo_admission_lock TO '" + account + "'@'%'");
                }
                for (String table : List.of("users", "cards", "transactions", "budgets", "demo_visit")) {
                    grant(admin, "GRANT INSERT ON " + quotedSchema + "." + table + " TO '" + runtime + "'@'%'");
                    grant(admin, "GRANT DELETE ON " + quotedSchema + "." + table + " TO '" + cleanup + "'@'%'");
                }
                for (String table : List.of("users", "transactions", "budgets")) {
                    grant(admin, "GRANT UPDATE ON " + quotedSchema + "." + table + " TO '" + runtime + "'@'%'");
                }
                if (selectedRole.equals("runtime")) {
                    phase = "RUNTIME_CONNECT";
                    try (Connection restricted = java.sql.DriverManager.getConnection(url, runtime, password)) {
                        phase = "RUNTIME_CAPACITY_SELECT";
                        assertCapacitySelect(restricted);
                        phase = "RUNTIME_DENIALS";
                        denied(restricted, "DELETE FROM users WHERE id=-1");
                        denied(restricted, "UPDATE demo_visit SET scenario_version='FUTURE' WHERE user_id=-1");
                        denied(restricted, "CREATE TABLE cleanup_forbidden_runtime(id BIGINT)");
                        denied(restricted, "INSERT INTO analysis_job(user_id) VALUES (1)");
                        denied(restricted, "INSERT INTO peer_transaction_stats(id) VALUES (-1)");
                        denied(restricted, "INSERT INTO dummy(id) VALUES (-1)");
                        denied(restricted, "UPDATE demo_capacity SET max_visitors=1 WHERE id=1");
                        assertLockSelectAndWriteDenials(restricted);
                        phase = "RUNTIME_LOCK_NOWAIT_AFTER_SELECT_AND_DENIALS_PASS";
                        assertAdmissionNowaitLock(restricted);
                        phase = "RUNTIME_ALLOWED_BUSINESS_WRITES";
                        assertRuntimeWritesUnderAdmissionLock(restricted);
                    }
                } else {
                    phase = "CLEANUP_CONNECT";
                    try (Connection restricted = java.sql.DriverManager.getConnection(url, cleanup, password)) {
                        phase = "CLEANUP_CAPACITY_SELECT";
                        assertCapacitySelect(restricted);
                        phase = "CLEANUP_DENIALS";
                        for (String table : List.of("users", "cards", "transactions", "budgets", "analysis_job", "peer_transaction_stats", "dummy")) {
                            denied(restricted, "UPDATE " + table + " SET id=id WHERE id=-1");
                        }
                        denied(restricted, "UPDATE demo_visit SET scenario_version='FUTURE' WHERE user_id=-1");
                        denied(restricted, "INSERT INTO users(created_at,email,name) VALUES (UTC_TIMESTAMP(6),'unused@example.invalid','Unused')");
                        denied(restricted, "CREATE TABLE cleanup_forbidden_admin(id BIGINT)");
                        denied(restricted, "INSERT INTO analysis_job(user_id) VALUES (1)");
                        denied(restricted, "INSERT INTO peer_transaction_stats(id) VALUES (-1)");
                        denied(restricted, "INSERT INTO dummy(id) VALUES (-1)");
                        denied(restricted, "UPDATE demo_capacity SET max_visitors=1 WHERE id=1");
                        assertLockSelectAndWriteDenials(restricted);
                        phase = "CLEANUP_LOCK_NOWAIT_AFTER_SELECT_AND_DENIALS_PASS";
                        assertAdmissionNowaitLock(restricted);
                        phase = "CLEANUP_VERIFY";
                        assertThat(service.execute(restricted, Mode.VERIFY, 10, 1000).countAfter()).isZero();
                    }
                    phase = "DATASET_PREPARATION";
                    seed();
                    phase = "CLEANUP_APPLY_CONNECT";
                    try (Connection restricted = java.sql.DriverManager.getConnection(url, cleanup, password)) {
                        phase = "CLEANUP_APPLY";
                        assertThat(service.execute(restricted, Mode.APPLY, 10, 1000).deleted()).isEqualTo(1);
                    }
                }
            } finally {
                boolean removed = true;
                for (String account : created) {
                    try { grant(admin, "DROP USER '" + account + "'@'%'"); }
                    catch (SQLException error) { removed = false; }
                }
                assertThat(removed).as("Only the two accounts created by this test are removed").isTrue();
            }
        } catch (SQLException error) {
            String state = error.getSQLState();
            String safeState = state != null && state.matches("[A-Z0-9]{5}") ? state : "UNCLASSIFIED";
            String category = error.getErrorCode() == 1142 && "42000".equals(safeState)
                ? "DEMO_TABLE_PRIVILEGE_DENIED"
                : error.getErrorCode() == 1143 && "42000".equals(safeState)
                    ? "DEMO_COLUMN_PRIVILEGE_DENIED" : "DEMO_UNEXPECTED_PRIVILEGE_SQL_FAILURE";
            throw new AssertionError("DEMO_" + phase + "_SQL_FAILURE:" + category
                + ":VENDOR=" + error.getErrorCode() + ":STATE=" + safeState);
        }
    }
    private static void assertCapacitySelect(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT id,max_visitors FROM demo_capacity WHERE id=1");
             var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(1);
            assertThat(rows.getInt(2)).isEqualTo(1000);
            assertThat(rows.next()).isFalse();
        }
    }
    private static void assertLockSelectAndWriteDenials(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT id FROM demo_admission_lock");
             var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1);
            assertThat(rows.next()).isFalse();
        }
        denied(connection, "INSERT INTO demo_admission_lock(id) VALUES (2)");
        denied(connection, "DELETE FROM demo_admission_lock WHERE id=-1");
    }
    private static void assertRuntimeWritesUnderAdmissionLock(Connection connection) throws SQLException {
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        connection.setAutoCommit(false);
        try {
            try (var statement = connection.prepareStatement("SELECT id FROM demo_admission_lock WHERE id=1 FOR UPDATE NOWAIT");
                 var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1);
            }
            long user;
            try (var statement = connection.prepareStatement("INSERT INTO users(created_at,email,name) VALUES (UTC_TIMESTAMP(6),?,'Restricted fixture')", Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, "restricted-" + UUID.randomUUID() + "@example.invalid");
                assertThat(statement.executeUpdate()).isEqualTo(1);
                try (var keys = statement.getGeneratedKeys()) { assertThat(keys.next()).isTrue(); user = keys.getLong(1); }
            }
            long card;
            try (var statement = connection.prepareStatement("INSERT INTO cards(created_at,user_id) VALUES (UTC_TIMESTAMP(6),?)", Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, user); assertThat(statement.executeUpdate()).isEqualTo(1);
                try (var keys = statement.getGeneratedKeys()) { assertThat(keys.next()).isTrue(); card = keys.getLong(1); }
            }
            try (var statement = connection.prepareStatement("INSERT INTO transactions(created_at,card_id,transaction_date_time,amount,merchant_name,category) VALUES (UTC_TIMESTAMP(6),?,UTC_TIMESTAMP(6),1000,'Restricted fixture','카페')")) {
                statement.setLong(1, card); assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            try (var statement = connection.prepareStatement("INSERT INTO budgets(user_id,budget_date,category,amount,is_overridden) VALUES (?,CURRENT_DATE(),'카페',1000,0)")) {
                statement.setLong(1, user); assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            try (var statement = connection.prepareStatement("INSERT INTO demo_visit(user_id,created_at,scenario_version,session_expires_at) VALUES (?,UTC_TIMESTAMP(6),'V1',UTC_TIMESTAMP(6)+INTERVAL 1 HOUR)")) {
                statement.setLong(1, user); assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            for (String sql : List.of("UPDATE users SET name='Updated fixture' WHERE id=?",
                "UPDATE budgets SET amount=2000 WHERE user_id=?")) {
                try (var statement = connection.prepareStatement(sql)) {
                    statement.setLong(1, user); assertThat(statement.executeUpdate()).isEqualTo(1);
                }
            }
            try (var statement = connection.prepareStatement("UPDATE transactions SET category='마트 / 편의점' WHERE card_id=?")) {
                statement.setLong(1, card); assertThat(statement.executeUpdate()).isEqualTo(1);
            }
        } finally { connection.rollback(); connection.setAutoCommit(true); }
        for (String table : List.of("users", "cards", "transactions", "budgets", "demo_visit")) {
            try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table); var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isZero();
            }
        }
    }
    private static void assertAdmissionNowaitLock(Connection connection) throws SQLException {
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement("SELECT id FROM demo_admission_lock WHERE id=1 FOR UPDATE NOWAIT");
             var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(1);
            assertThat(rows.next()).isFalse();
        } finally { connection.rollback(); connection.setAutoCommit(true); }
    }
    private static void grant(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private static void denied(Connection connection, String sql) throws SQLException {
        int code = 0;
        try (var statement = connection.createStatement()) { statement.execute(sql); }
        catch (SQLException error) { code = error.getErrorCode(); }
        assertThat(code == 1142 || code == 1143).as("Table/column privilege denial").isTrue();
    }

    private DemoCleanupService.Result run(Mode mode) throws SQLException {
        try (Connection connection = dataSource.getConnection()) { return service.execute(connection, mode, 10, 1000); }
    }
    private void assertRejectedUnchanged(String code) throws Exception {
        var before = snapshot(); AtomicInteger dml = new AtomicInteger();
        try (Connection real = dataSource.getConnection()) {
            assertThatThrownBy(() -> service.execute(observe(real, sql -> { if (isDml(sql)) dml.incrementAndGet(); }, null), Mode.APPLY, 10, 1000)).hasMessage(code);
        }
        assertThat(snapshot().equals(before)).isTrue();
        if (!code.equals("SQL_FAILURE")) assertThat(dml.get()).isZero();
    }
    private void reset() {
        for (String table : List.of("analysis_job", "transactions", "budgets", "cards", "demo_visit", "users", "demo_capacity", "demo_admission_lock")) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO demo_capacity(id,max_visitors) VALUES (1,1000)");
        jdbc.update("INSERT INTO demo_admission_lock(id) VALUES (1)");
    }
    private long seed() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT id FROM demo_admission_lock WHERE id=1 FOR UPDATE NOWAIT"); var rows = lock.executeQuery()) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1); assertThat(rows.next()).isFalse();
            }
            long user;
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO users(created_at,email,name) VALUES (UTC_TIMESTAMP(6),?,'Synthetic cleanup fixture')", Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, "cleanup-" + UUID.randomUUID() + "@example.invalid"); insert.executeUpdate();
                try (var keys = insert.getGeneratedKeys()) { assertThat(keys.next()).isTrue(); user = keys.getLong(1); }
            }
            long card;
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO cards(created_at,user_id) VALUES (UTC_TIMESTAMP(6),?)", Statement.RETURN_GENERATED_KEYS)) {
                insert.setLong(1, user); insert.executeUpdate();
                try (var keys = insert.getGeneratedKeys()) { assertThat(keys.next()).isTrue(); card = keys.getLong(1); }
            }
            var scenario = DemoSeedScenario.generate(LocalDate.of(2026, 3, 15));
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO transactions(created_at,card_id,transaction_date_time,amount,merchant_name,category) VALUES (UTC_TIMESTAMP(6),?,?,?,?,?)")) {
                for (var row : scenario.transactions()) {
                    insert.setLong(1, card); insert.setObject(2, row.dateTime()); insert.setInt(3, row.amount()); insert.setString(4, row.merchantName()); insert.setString(5, row.category()); insert.addBatch();
                }
                assertThat(insert.executeBatch()).hasSize(240);
            }
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO budgets(user_id,budget_date,category,amount,is_overridden) VALUES (?,?,?,?,0)")) {
                for (var row : scenario.budgets()) {
                    insert.setLong(1, user); insert.setObject(2, row.date()); insert.setString(3, row.category()); insert.setInt(4, row.amount()); insert.addBatch();
                }
                assertThat(insert.executeBatch()).hasSize(72);
            }
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO demo_visit(user_id,created_at,scenario_version,session_expires_at) VALUES (?,UTC_TIMESTAMP(6)-INTERVAL 48 HOUR,'V1',UTC_TIMESTAMP(6)-INTERVAL 47 HOUR)")) {
                insert.setLong(1, user); insert.executeUpdate();
            }
            connection.commit(); return user;
        }
    }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private List<List<Map<String, Object>>> snapshot() {
        List<List<Map<String, Object>>> snapshot = new ArrayList<>();
        for (String table : List.of("users", "cards", "transactions", "budgets", "analysis_job", "demo_visit", "demo_capacity", "demo_admission_lock")) {
            snapshot.add(jdbc.queryForList("SELECT * FROM " + table + " ORDER BY " + (table.equals("demo_visit") ? "user_id" : "id")));
        }
        return snapshot;
    }
    private List<List<Map<String, Object>>> ownedSnapshot(long user) {
        return List.of(jdbc.queryForList("SELECT * FROM users WHERE id=?", user),
            jdbc.queryForList("SELECT * FROM cards WHERE user_id=? ORDER BY id", user),
            jdbc.queryForList("SELECT t.* FROM transactions t JOIN cards c ON c.id=t.card_id WHERE c.user_id=? ORDER BY t.id", user),
            jdbc.queryForList("SELECT * FROM budgets WHERE user_id=? ORDER BY id", user));
    }
    static boolean isDml(String sql) { return sql.matches("(?is)\\s*(INSERT|UPDATE|DELETE).*?"); }
    interface CommitAction { void commit() throws SQLException; }
    static Connection observe(Connection actual, Consumer<String> observer, CommitAction commit) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql) observer.accept(sql);
            if (method.getName().equals("commit") && commit != null) { commit.commit(); return null; }
            try { return method.invoke(actual, args); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        });
    }
    static Connection faultStatement(Connection actual, String prefix, boolean fail) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            try {
                Object value = method.invoke(actual, args);
                if (method.getName().equals("prepareStatement") && args[0] instanceof String sql && sql.startsWith(prefix)) {
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class}, (statement, action, parameters) -> {
                        try {
                            Object result = action.invoke(value, parameters);
                            if (action.getName().equals("executeUpdate")) {
                                if (fail) throw new SQLException("synthetic boundary", "45000");
                                return ((Integer) result) - 1;
                            }
                            return result;
                        } catch (InvocationTargetException error) { throw error.getCause(); }
                    });
                }
                return value;
            } catch (InvocationTargetException error) { throw error.getCause(); }
        });
    }
}
