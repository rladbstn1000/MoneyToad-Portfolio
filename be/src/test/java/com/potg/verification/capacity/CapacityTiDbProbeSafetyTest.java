package com.potg.verification.capacity;

import static org.assertj.core.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CapacityTiDbProbeSafetyTest {
    @Test void fixedInputBudgetAndNamesRejectBroaderScope() {
        var input = valid(); CapacityTiDbProbe.validate(input);
        for (var entry : Map.of("workLimit", "1501", "cleanupLimit", "499", "connectionLimit", "9", "schema", "existing_schema", "runtimeAccount", "other.m12345678901234").entrySet()) {
            var changed = new HashMap<>(input); changed.put(entry.getKey(), entry.getValue());
            assertThatThrownBy(() -> CapacityTiDbProbe.validate(changed)).isInstanceOf(CapacitySqlBudget.Rejected.class);
        }
        input.put("REDIS_HOST", "forbidden.invalid");
        assertThatThrownBy(() -> CapacityTiDbProbe.validate(input)).isInstanceOf(CapacitySqlBudget.Rejected.class);
    }
    @Test void boundStopsBeforeNextDelegateAndCleanupIsNotRefund() {
        var budget = budget(); budget.spend(1500);
        assertThatThrownBy(() -> budget.spend(1)).hasMessage("WORK_BUDGET_OR_DEADLINE");
        budget.startCleanup(); budget.spend(500);
        assertThatThrownBy(() -> budget.spend(1)).hasMessage("CLEANUP_BUDGET_OR_DEADLINE");
        assertThat(budget.work).isEqualTo(1500); assertThat(budget.cleanup).isEqualTo(500);
    }
    @Test void fourConnectionAttemptsIncludeFailures() {
        var budget = budget(); for (int i=0;i<4;i++) budget.opening();
        assertThat(budget.work).isEqualTo(128);
        assertThatThrownBy(budget::opening).hasMessage("CONNECTION_LIMIT");
    }
    @Test void metadataHasExplicitReserveAndUnknownEnumerationIsDenied() throws Exception {
        var budget = budget(); AtomicInteger calls = new AtomicInteger();
        DatabaseMetaData raw = proxy(DatabaseMetaData.class, (name, args) -> { calls.incrementAndGet(); return null; });
        Connection c = budget.observe(proxy(Connection.class, (name,args) -> name.equals("getMetaData") ? raw : null));
        c.getMetaData().getColumns("owned",null,"demo_visit",null);
        assertThat(budget.work).isEqualTo(8); assertThat(calls.get()).isEqualTo(1);
        assertThatThrownBy(() -> c.getMetaData().getTables(null,null,null,null)).hasMessage("UNREVIEWED_METADATA");
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void statementsCountDeniedDmlAndClassifyOnlySafeSqlErrorFields() throws Exception {
        var budget = budget(); AtomicInteger calls = new AtomicInteger();
        PreparedStatement stmt = proxy(PreparedStatement.class, (name,args) -> {
            if (name.equals("executeUpdate")) { calls.incrementAndGet(); throw new SQLException("sensitive-canary", "42000",1142); }
            if (name.equals("executeQuery")) throw new SQLException("sensitive-canary", "HY000",3572);
            return null;
        });
        Connection c = budget.observe(proxy(Connection.class,(name,args)->name.equals("prepareStatement")?stmt:null));
        assertThatThrownBy(() -> c.prepareStatement("DELETE FROM users WHERE id=-1").executeUpdate()).isInstanceOf(SQLException.class);
        assertThat(budget.dml).isEqualTo(1); assertThat(budget.deletes).isEqualTo(1);
        assertThatThrownBy(() -> c.prepareStatement("SELECT id FROM demo_capacity FOR UPDATE NOWAIT").executeQuery()).isInstanceOf(SQLException.class);
        assertThat(budget.nowaitVendor).isEqualTo(3572); assertThat(budget.nowaitState).isEqualTo("HY000");
        assertThat(budget.work).isEqualTo(2); assertThat(calls.get()).isEqualTo(1);
    }
    @Test void batchAndUnwrapCannotBypassAccounting() throws Exception {
        var budget = budget(); PreparedStatement stmt = proxy(PreparedStatement.class,(name,args)->null);
        Connection c = budget.observe(proxy(Connection.class,(name,args)->name.equals("prepareStatement")?stmt:null));
        assertThatThrownBy(() -> c.unwrap(Connection.class)).isInstanceOf(SQLException.class);
        var p = c.prepareStatement("INSERT INTO users(id) VALUES(1)");
        assertThat(p.getConnection()).isSameAs(c);
        assertThatThrownBy(p::addBatch).hasMessage("BATCH_DISABLED");
        assertThatThrownBy(p::executeBatch).hasMessage("BATCH_DISABLED");
    }
    @Test void cancellationStopsWorkButAllowsFiniteOwnedCleanup() {
        var budget = budget(); budget.cancelled=true;
        assertThatThrownBy(() -> budget.spend(1)).hasMessage("WORK_BUDGET_OR_DEADLINE");
        budget.startCleanup(); budget.spend(1); assertThat(budget.cleanup).isEqualTo(1);
    }
    @Test void trackedUnassignedClientClosesEvenWhenRollbackFails() {
        AtomicInteger closed = new AtomicInteger();
        Connection unassigned = proxy(Connection.class,(name,args) -> switch(name) {
            case "getAutoCommit" -> false;
            case "rollback" -> throw new SQLException("private-canary");
            case "close" -> { closed.incrementAndGet(); yield null; }
            case "isClosed" -> true;
            default -> null;
        });
        assertThat(CapacityTiDbProbe.closeOwnedClients(List.of(unassigned),null)).isFalse();
        assertThat(closed.get()).isEqualTo(1);
    }
    @Test void unknownCleanupOutcomeNeverImplicitlyCommitsOrRecyclesConnection() {
        AtomicInteger resets = new AtomicInteger();
        Connection c = proxy(Connection.class,(name,args) -> { if(name.equals("setAutoCommit")) resets.incrementAndGet(); return null; });
        for (var code : List.of(com.potg.don.maintenance.CleanupFailure.Code.COMMIT_UNKNOWN,com.potg.don.maintenance.CleanupFailure.Code.ROLLBACK_UNKNOWN)) {
            assertThatThrownBy(() -> CapacityTiDbProbe.withConfirmedCleanupReset(c, () -> { throw new com.potg.don.maintenance.CleanupFailure(code); })).isInstanceOf(com.potg.don.maintenance.CleanupFailure.class);
        }
        assertThat(resets.get()).isZero();
    }
    @Test void confirmedCleanupRollbackRestoresConnectionForNextBoundedStage() {
        AtomicInteger resets = new AtomicInteger();
        Connection c = proxy(Connection.class,(name,args) -> { if(name.equals("setAutoCommit")) resets.incrementAndGet(); return null; });
        assertThatThrownBy(() -> CapacityTiDbProbe.withConfirmedCleanupReset(c, () -> { throw new com.potg.don.maintenance.CleanupFailure(com.potg.don.maintenance.CleanupFailure.Code.UNSAFE_BATCH); })).isInstanceOf(com.potg.don.maintenance.CleanupFailure.class);
        assertThat(resets.get()).isEqualTo(1);
    }
    @Test void sqlFailureProjectionContainsOnlyVendorAndStateNeverMessageOrIdentity() {
        SQLException sql = new SQLException("sensitive-canary-identity", "42000", 1142);
        var result = CapacityTiDbProbe.safeSqlFailure(new CapacitySqlBudget.Rejected("FIXTURE_SQL_FAILURE", sql));
        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of("failureVendor",1142,"failureSqlState","42000"));
        assertThat(result.toString()).doesNotContain("sensitive-canary");
        assertThat(CapacityTiDbProbe.safeSqlFailure(new SQLException("private", "private-state", 0))).containsEntry("failureSqlState","NOT_OBSERVED");
    }
    static Map<String,String> valid() {
        Map<String,String> v = new HashMap<>();
        v.put("TIDB_HOST","fixture.invalid");v.put("TIDB_PORT","4000");v.put("TIDB_SETUP_USERNAME","fixture.root");v.put("TIDB_SETUP_PASSWORD","synthetic-input");
        v.put("schema","mtcapacity0123456789abcdef");v.put("runtimeAccount","fixture.m0123456789abcd");v.put("cleanupAccount","fixture.mfedcba98765432");
        v.put("runtimePassword","r".repeat(43));v.put("cleanupPassword","c".repeat(43));v.put("baselinePath","synthetic");v.put("migrationPath","synthetic");
        v.put("deadlineEpochMillis",Long.toString(System.currentTimeMillis()+600_000));v.put("workLimit","1500");v.put("cleanupLimit","500");v.put("connectionLimit","8");return v;
    }
    private static CapacitySqlBudget budget() { return new CapacitySqlBudget(System.currentTimeMillis()+600_000); }
    @FunctionalInterface interface Call { Object call(String method,Object[] args) throws Throwable; }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type,Call body) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,m,args)->body.call(m.getName(),args));
    }
}
