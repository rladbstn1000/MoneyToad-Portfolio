package com.potg.don.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static com.potg.don.auth.DemoAuthHttpIntegrationTest.postRequest;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import jakarta.servlet.http.Cookie;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;

import com.potg.don.auth.demo.DemoSessionStore;
import com.potg.don.auth.jwt.JwtUtil;
import com.potg.don.demo.admission.DemoAdmissionSchema;
import com.potg.don.demo.admission.DemoAdmissionStore;
import com.potg.don.demo.admission.DemoAdmissionException;

/** Actual product HTTP/JPA/JDBC/Redis. Only the test observer delays a connection at a named barrier. */
@Execution(ExecutionMode.SAME_THREAD)
class DemoAdmissionIntegrationTest {
    @Test
    void capacityTwoAllowsTwoLoginsThenRejectsWithoutDataAndExistingVisitorRemainsUsable() throws Exception {
        try (var fixture = new Fixture("normal")) {
            var a = fixture.login();
            fixture.login();
            fixture.assertCounts(2);
            var before = fixture.snapshot();
            int creates = fixture.observer.creates.get();
            var full = fixture.mvc.perform(postRequest("login", "{}")).andReturn();
            error(full, "DEMO_CAPACITY_FULL");
            assertThat(before.equals(fixture.snapshot())).isTrue();
            assertThat(fixture.observer.creates.get()).isEqualTo(creates);
            assertThat(fixture.mvc.perform(get("/api/auth/demo/ready").contextPath("/api")).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
            assertThat(fixture.mvc.perform(get("/api/auth/demo/session").contextPath("/api")
                .header("Authorization", "Bearer " + a.access())).andReturn().getResponse().getStatus()).isEqualTo(200);
            var refreshed = fixture.mvc.perform(postRequest("reissue", "{}")
                .cookie(new Cookie("demoRefreshToken", a.refresh()))).andReturn();
            assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
            var b = fixture.credentials(refreshed);
            assertThat(fixture.mvc.perform(postRequest("logout", "{}")
                .header("Authorization", "Bearer " + b.access())).andReturn().getResponse().getStatus()).isEqualTo(204);
            fixture.assertCounts(2);
            assertThat(before.equals(fixture.snapshot())).isTrue();
        }
    }

    @Test
    void lastSlotConcurrentLoginHasOneNowaitLoserWithNoRedisOrSqlSideEffects() throws Exception {
        try (var fixture = new Fixture("normal")) {
            fixture.login();
            var reached = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            fixture.observer.entered = reached;
            fixture.observer.release = release;
            var executor = Executors.newSingleThreadExecutor();
            try {
                var winner = executor.submit(() -> fixture.mvc.perform(postRequest("login", "{}")).andReturn());
                assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue();
                int creates = fixture.observer.creates.get();
                var busy = fixture.mvc.perform(postRequest("login", "{}")).andReturn();
                error(busy, "DEMO_ADMISSION_BUSY");
                assertThat(fixture.observer.creates.get()).isEqualTo(creates);
                // Another connection still sees only the first committed visit.
                fixture.assertCounts(1);
                release.countDown();
                assertThat(winner.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
                fixture.assertCounts(2);
                assertThat(fixture.observer.creates.get()).isEqualTo(2);
                error(fixture.mvc.perform(postRequest("login", "{}")).andReturn(), "DEMO_CAPACITY_FULL");
            } finally {
                release.countDown();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cleanupLockRejectsLoginAndCommitOrRollbackReleasesIt(boolean commit) throws Exception {
        try (var fixture = new Fixture("normal");
             Connection admin = fixture.dataSource.getConnection()) {
            admin.setAutoCommit(false);
            admin.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try (var statement = admin.prepareStatement("SELECT id FROM demo_admission_lock WHERE id=1 FOR UPDATE NOWAIT")) {
                statement.executeQuery().close();
            }
            var before = fixture.snapshot();
            error(fixture.mvc.perform(postRequest("login", "{}")).andReturn(), "DEMO_ADMISSION_BUSY");
            assertThat(fixture.observer.creates.get()).isZero();
            assertThat(before.equals(fixture.snapshot())).isTrue();
            if (commit) admin.commit(); else admin.rollback();
            fixture.login();
            fixture.assertCounts(1);
        }
    }

    @Test
    void markerSqlFailureRollsBackEveryDatasetRowAndRevokesCreatedRedisSession() throws Exception {
        try (var fixture = new Fixture("normal")) {
            var before = fixture.snapshot();
            fixture.jdbc.execute("CREATE TRIGGER admission_marker_failure BEFORE INSERT ON demo_visit "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TEST_MARKER_FAILURE'");
            try {
                error(fixture.mvc.perform(postRequest("login", "{}")).andReturn(), "DEMO_ADMISSION_UNAVAILABLE");
                assertThat(before.equals(fixture.snapshot())).isTrue();
                assertThat(fixture.observer.creates.get()).isEqualTo(1);
                assertThat(fixture.observer.revokes.get()).isEqualTo(1);
                fixture.assertObservedSessionsAbsent();
            } finally { fixture.jdbc.execute("DROP TRIGGER admission_marker_failure"); }
        }
    }

    @Test
    void postConditionDetectsMissingMarkerBeforeCommitAndCompensatesRedis() throws Exception {
        try (var fixture = new Fixture("normal")) {
            var before = fixture.snapshot();
            // A test-only fault removes this transaction's marker after the real INSERT.
            fixture.observer.eraseRecordedMarker = true;
            error(fixture.mvc.perform(postRequest("login", "{}")).andReturn(), "DEMO_ADMISSION_UNAVAILABLE");
            assertThat(before.equals(fixture.snapshot())).isTrue();
            assertThat(fixture.observer.creates.get()).isEqualTo(1);
            assertThat(fixture.observer.revokes.get()).isEqualTo(1);
            fixture.assertObservedSessionsAbsent();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"over_capacity", "maximum", "zero_maximum", "missing", "multiple", "invalid_id",
        "lock_missing", "lock_multiple", "lock_invalid_id"})
    void runtimeIntegrityMismatchCreatesNeitherDatasetNorSession(String corruption) throws Exception {
        try (var fixture = new Fixture("normal")) {
            corrupt(fixture.jdbc, corruption);
            var before = fixture.snapshot();
            error(fixture.mvc.perform(postRequest("login", "{}")).andReturn(), "DEMO_ADMISSION_UNAVAILABLE");
            assertThat(before.equals(fixture.snapshot())).isTrue();
            assertThat(fixture.observer.creates.get()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"over_capacity", "maximum", "zero_maximum", "missing", "multiple", "invalid_id", "no_visit", "cascade", "timestamp",
        "lock_missing", "lock_multiple", "lock_invalid_id", "lock_table_missing", "lock_extra_column", "lock_wrong_type"})
    void startupRejectsMalformedProvenanceSchemaAndIntegrity(String corruption) throws Exception {
        try (var support = new DemoAuthHttpTestSupport();
             var started = support.start("demo", "", "true", "local-demo", false,
                Map.of("app.demo.max-visitors", 2, "test.admission.corruption", corruption), FixtureConfiguration.class)) {
            assertThat(started.context() == null).isTrue();
            assertThat(started.failure() != null).isTrue();
            boolean safeFailure = false;
            for (Throwable cause = started.failure(); cause != null; cause = cause.getCause()) {
                safeFailure |= "DEMO_ADMISSION_SCHEMA_OR_INTEGRITY_INVALID".equals(cause.getMessage());
            }
            assertThat(safeFailure).isTrue();
            assertThat(support.noOutbound(started.probe())).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transactionCompletionCannotLeaveReusableAdmissionClaim(boolean rollback) throws Exception {
        try (var fixture = new Fixture("normal")) {
            var admission = fixture.started.context().getBean(DemoAdmissionStore.class);
            var tx = transaction(fixture);
            var before = fixture.snapshot();
            tx.executeWithoutResult(status -> {
                unavailable(() -> admission.recordVisit(1L, Instant.now().plusSeconds(3600)));
                unavailable(() -> admission.verifyIntegrity(0L));
                assertThat(admission.claimSlot()).isZero();
                unavailable(admission::claimSlot);
                if (rollback) status.setRollbackOnly();
            });
            tx.executeWithoutResult(status -> {
                unavailable(() -> admission.recordVisit(1L, Instant.now().plusSeconds(3600)));
                unavailable(() -> admission.verifyIntegrity(0L));
                // A completed claim neither supplies authorization nor retains a database lock.
                assertThat(admission.claimSlot()).isZero();
            });
            assertThat(before.equals(fixture.snapshot())).isTrue();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        }
    }

    @Test
    void requiresNewCannotBorrowOuterClaimAndRestoredOuterClaimStillWorks() throws Exception {
        try (var fixture = new Fixture("normal")) {
            var admission = fixture.started.context().getBean(DemoAdmissionStore.class);
            var tx = transaction(fixture);
            var before = fixture.snapshot();
            tx.executeWithoutResult(outer -> {
                long count = admission.claimSlot();
                tx.executeWithoutResult(inner -> {
                    unavailable(() -> admission.recordVisit(1L, Instant.now().plusSeconds(3600)));
                    unavailable(() -> admission.verifyIntegrity(count));
                });
                long fixtureUser = insertFixtureUser(fixture.jdbc);
                admission.recordVisit(fixtureUser, Instant.now().plusSeconds(3600));
                admission.verifyIntegrity(count);
                outer.setRollbackOnly();
            });
            assertThat(before.equals(fixture.snapshot())).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"omit", "wrong_initial", "second_marker"})
    void markerCannotCommitWithoutTheClaimedFinalCount(String misuse) throws Exception {
        try (var fixture = new Fixture("normal")) {
            var admission = fixture.started.context().getBean(DemoAdmissionStore.class);
            var before = fixture.snapshot();
            unavailable(() -> transaction(fixture).executeWithoutResult(status -> {
                long initial = admission.claimSlot();
                long fixtureUser = insertFixtureUser(fixture.jdbc);
                admission.recordVisit(fixtureUser, Instant.now().plusSeconds(3600));
                if ("wrong_initial".equals(misuse)) admission.verifyIntegrity(initial + 1);
                if ("second_marker".equals(misuse)) admission.recordVisit(fixtureUser, Instant.now().plusSeconds(3600));
                // "omit" reaches actual beforeCommit and must roll the inserted marker and User back.
            }));
            assertThat(before.equals(fixture.snapshot())).isTrue();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        }
    }

    @Test
    void changedBoundConnectionCannotReuseClaim() throws Exception {
        try (var fixture = new Fixture("normal"); var other = fixture.dataSource.getConnection()) {
            var admission = fixture.started.context().getBean(DemoAdmissionStore.class);
            var before = fixture.snapshot();
            transaction(fixture).executeWithoutResult(status -> {
                admission.claimSlot();
                Object actualHolder = TransactionSynchronizationManager.unbindResource(fixture.dataSource);
                try {
                    TransactionSynchronizationManager.bindResource(fixture.dataSource, new ConnectionHolder(other));
                    unavailable(() -> admission.recordVisit(1L, Instant.now().plusSeconds(3600)));
                    unavailable(() -> admission.verifyIntegrity(0L));
                } finally {
                    TransactionSynchronizationManager.unbindResource(fixture.dataSource);
                    TransactionSynchronizationManager.bindResource(fixture.dataSource, actualHolder);
                }
            });
            assertThat(before.equals(fixture.snapshot())).isTrue();
        }
    }

    private static TransactionTemplate transaction(Fixture fixture) {
        var tx = new TransactionTemplate(fixture.started.context().getBean(PlatformTransactionManager.class));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    private static long insertFixtureUser(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO users(created_at,email,name) "
            + "VALUES(UTC_TIMESTAMP(6),'lock-contract@moneytoad.invalid','합성 검증')");
        return jdbc.queryForObject("SELECT id FROM users WHERE email='lock-contract@moneytoad.invalid'", Long.class);
    }

    private static void unavailable(Runnable operation) {
        var failure = org.junit.jupiter.api.Assertions.assertThrows(DemoAdmissionException.class, operation::run);
        assertThat(failure.code()).isEqualTo(DemoAdmissionException.Code.DEMO_ADMISSION_UNAVAILABLE);
    }

    private static void corrupt(JdbcTemplate jdbc, String kind) {
        switch (kind) {
            case "over_capacity" -> {
                for (int i = 0; i < 3; i++) {
                    jdbc.update("INSERT INTO users(created_at,email,name) VALUES(UTC_TIMESTAMP(6), "
                        + "CONCAT('capacity-fixture-',?,'@moneytoad.invalid'),'합성 검증')", i);
                }
                jdbc.update("INSERT INTO demo_visit(user_id,created_at,scenario_version,session_expires_at) "
                    + "SELECT id,UTC_TIMESTAMP(6),'V1',UTC_TIMESTAMP(6)+INTERVAL 1 HOUR FROM users");
            }
            case "maximum" -> jdbc.update("UPDATE demo_capacity SET max_visitors=3");
            case "zero_maximum" -> jdbc.update("UPDATE demo_capacity SET max_visitors=0");
            case "invalid_id" -> jdbc.update("UPDATE demo_capacity SET id=2");
            case "missing" -> jdbc.update("DELETE FROM demo_capacity");
            case "multiple" -> jdbc.update("INSERT INTO demo_capacity VALUES (2,2)");
            case "lock_missing" -> jdbc.update("DELETE FROM demo_admission_lock");
            case "lock_multiple" -> jdbc.update("INSERT INTO demo_admission_lock VALUES (2)");
            case "lock_invalid_id" -> jdbc.update("UPDATE demo_admission_lock SET id=2");
            case "lock_table_missing" -> jdbc.execute("DROP TABLE demo_admission_lock");
            case "lock_extra_column" -> jdbc.execute("ALTER TABLE demo_admission_lock ADD unwanted INT NOT NULL DEFAULT 0");
            case "lock_wrong_type" -> jdbc.execute("ALTER TABLE demo_admission_lock MODIFY id INT NOT NULL");
            case "no_visit" -> jdbc.execute("DROP TABLE demo_visit");
            case "cascade" -> {
                jdbc.execute("ALTER TABLE demo_visit DROP FOREIGN KEY fk_demo_visit_user");
                jdbc.execute("ALTER TABLE demo_visit ADD CONSTRAINT fk_demo_visit_user FOREIGN KEY (user_id) "
                    + "REFERENCES users(id) ON DELETE CASCADE");
            }
            case "timestamp" -> jdbc.execute("ALTER TABLE demo_visit MODIFY created_at DATETIME(3) NOT NULL");
            case "normal" -> { }
            default -> throw new IllegalArgumentException("TEST_SCENARIO_INVALID");
        }
    }

    private static String safeFailure(Throwable failure) {
        List<String> codes = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            codes.add(cause.getClass().getSimpleName());
            if (cause.getMessage() != null && cause.getMessage().matches("DEMO_[A-Z_]{1,100}")) codes.add(cause.getMessage());
        }
        return String.join("/", codes);
    }

    private static void error(MvcResult result, String code) throws Exception {
        var response = result.getResponse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        var json = DemoAuthHttpTestSupport.JSON.readTree(response.getContentAsString());
        assertThat(json.path("code").asText()).isEqualTo(code);
        Set<String> fields = new java.util.HashSet<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).isEqualTo(Set.of("status", "error", "message", "code"));
    }

    private record Credentials(String access, String refresh) {
        @Override public String toString() { return "Credentials[redacted]"; }
    }

    private static final class Fixture implements AutoCloseable {
        final DemoAuthHttpTestSupport support = new DemoAuthHttpTestSupport();
        final DemoAuthHttpTestSupport.Started started;
        final DataSource dataSource;
        final JdbcTemplate jdbc;
        final MockMvc mvc;
        final Observations observer;
        final DemoSessionStore store;
        Fixture(String kind) throws Exception {
            started = support.start("demo", "", "true", "local-demo", false,
                Map.of("app.demo.max-visitors", 2, "test.admission.corruption", kind), FixtureConfiguration.class);
            assertThat(started.failure() == null).as("actual product validate context: " + safeFailure(started.failure())).isTrue();
            dataSource = started.context().getBean(DataSource.class);
            jdbc = new JdbcTemplate(dataSource);
            mvc = DemoAuthHttpIntegrationTest.mvc(started);
            observer = started.context().getBean(Observations.class);
            store = started.context().getBean(DemoSessionStore.class);
            try (var connection = dataSource.getConnection()) {
                DemoAdmissionSchema.verify(connection);
                assertThat(DemoAdmissionSchema.verifySnapshot(connection, 2).visitCount()).isZero();
            }
        }
        Credentials login() throws Exception {
            var result = mvc.perform(postRequest("login", "{}")).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            return credentials(result);
        }
        Credentials credentials(MvcResult result) throws Exception {
            String access = DemoAuthHttpTestSupport.JSON.readTree(result.getResponse().getContentAsString())
                .path("accessToken").asText();
            String header = result.getResponse().getHeader("Set-Cookie");
            assertThat(header != null && header.startsWith("demoRefreshToken=")).isTrue();
            String refresh = header.substring("demoRefreshToken=".length(), header.indexOf(';'));
            var claims = started.context().getBean(JwtUtil.class).validateDemoRefreshToken(
                started.context().getBean(JwtUtil.class).parse(refresh), Instant.now());
            var marker = jdbc.queryForMap("SELECT scenario_version, session_expires_at, "
                + "CAST(created_at<=UTC_TIMESTAMP(6) AND created_at>UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE AS UNSIGNED) AS recent "
                + "FROM demo_visit WHERE user_id=?", claims.userId());
            assertThat(marker.get("scenario_version")).isEqualTo("V1");
            java.time.LocalDateTime expiry = jdbc.queryForObject("SELECT session_expires_at FROM demo_visit WHERE user_id=?",
                java.time.LocalDateTime.class, claims.userId());
            assertThat(expiry.equals(java.time.LocalDateTime.ofInstant(claims.expiresAt(), java.time.ZoneOffset.UTC))).isTrue();
            assertThat(((Number) marker.get("recent")).intValue()).isEqualTo(1);
            return new Credentials(access, refresh);
        }
        Map<String, List<Map<String, Object>>> snapshot() { return DemoAuthHttpTestSupport.snapshot(jdbc); }
        void assertCounts(int visitors) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(visitors);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cards", Integer.class)).isEqualTo(visitors);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class)).isEqualTo(visitors * 240);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budgets", Integer.class)).isEqualTo(visitors * 72);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM demo_visit", Integer.class)).isEqualTo(visitors);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM demo_capacity WHERE id=1 AND max_visitors=2", Integer.class)).isEqualTo(1);
        }
        void assertObservedSessionsAbsent() {
            assertThat(observer.sids.stream().allMatch(sid -> store.findActive(sid).isEmpty())).isTrue();
        }
        @Override public void close() {
            try {
                for (String sid : observer.sids) store.revoke(sid);
                assertObservedSessionsAbsent();
                assertThat(support.noOutbound(started.probe())).isTrue();
            } finally { started.close(); support.close(); }
        }
    }

    static final class Observations {
        final AtomicInteger creates = new AtomicInteger();
        final AtomicInteger revokes = new AtomicInteger();
        final List<String> sids = new CopyOnWriteArrayList<>();
        volatile boolean eraseRecordedMarker;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean Observations admissionObservations() { return new Observations(); }
        @Bean static BeanPostProcessor admissionFixture(Environment environment) {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof JdbcTemplate jdbc) {
                        jdbc.update("UPDATE demo_capacity SET max_visitors=2");
                        corrupt(jdbc, environment.getProperty("test.admission.corruption", "normal"));
                    }
                    return bean;
                }
            };
        }
        @Bean static BeanPostProcessor sessionObservation(Observations observations, ObjectProvider<JdbcTemplate> jdbc) {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DemoSessionStore) && !(bean instanceof DemoAdmissionStore)) return bean;
                    ProxyFactory proxy = new ProxyFactory(bean);
                    proxy.setProxyTargetClass(true);
                    proxy.addAdvice((MethodInterceptor) invocation -> {
                        if ("recordVisit".equals(invocation.getMethod().getName())) {
                            Object result = invocation.proceed();
                            if (observations.eraseRecordedMarker) {
                                assertThat(jdbc.getObject().update("DELETE FROM demo_visit WHERE user_id=?",
                                    invocation.getArguments()[0])).isEqualTo(1);
                            }
                            return result;
                        }
                        if ("create".equals(invocation.getMethod().getName())) {
                            observations.creates.incrementAndGet();
                            observations.sids.add((String) invocation.getArguments()[0]);
                            if (observations.entered != null) {
                                observations.entered.countDown();
                                if (!observations.release.await(20, TimeUnit.SECONDS)) {
                                    throw new AssertionError("TEST_ADMISSION_BARRIER_TIMEOUT");
                                }
                            }
                        } else if ("revoke".equals(invocation.getMethod().getName())) observations.revokes.incrementAndGet();
                        return invocation.proceed();
                    });
                    return proxy.getProxy();
                }
            };
        }
    }
}
