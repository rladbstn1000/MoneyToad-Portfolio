package com.potg.verification.admissionlock;

import static org.assertj.core.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AdmissionLockTiDbProbeSafetyTest {
    @Test void fixedInputBudgetAndNamesRejectBroaderScope() {
        var input = valid(); AdmissionLockTiDbProbe.validate(input);
        for (var entry : Map.of("workLimit", "2001", "cleanupLimit", "499", "connectionLimit", "9", "schema", "existing_schema", "runtimeAccount", "other.m12345678901234").entrySet()) {
            var changed = new HashMap<>(input); changed.put(entry.getKey(), entry.getValue());
            assertThatThrownBy(() -> AdmissionLockTiDbProbe.validate(changed)).isInstanceOf(AdmissionLockSqlBudget.Rejected.class);
        }
        input.put("REDIS_HOST", "forbidden.invalid");
        assertThatThrownBy(() -> AdmissionLockTiDbProbe.validate(input)).isInstanceOf(AdmissionLockSqlBudget.Rejected.class);
    }
    @Test void boundStopsBeforeNextDelegateAndCleanupIsNotRefund() {
        var budget = budget(); budget.spend(2000);
        assertThatThrownBy(() -> budget.spend(1)).hasMessage("WORK_BUDGET_OR_DEADLINE");
        budget.startCleanup(); budget.spend(500);
        assertThatThrownBy(() -> budget.spend(1)).hasMessage("CLEANUP_BUDGET_OR_DEADLINE");
        assertThat(budget.work).isEqualTo(2000); assertThat(budget.cleanup).isEqualTo(500);
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
        assertThat(AdmissionLockTiDbProbe.closeOwnedClients(List.of(unassigned),null)).isFalse();
        assertThat(closed.get()).isEqualTo(1);
    }
    @Test void unknownCleanupOutcomeNeverImplicitlyCommitsOrRecyclesConnection() {
        AtomicInteger resets = new AtomicInteger();
        Connection c = proxy(Connection.class,(name,args) -> { if(name.equals("setAutoCommit")) resets.incrementAndGet(); return null; });
        for (var code : List.of(com.potg.don.maintenance.CleanupFailure.Code.COMMIT_UNKNOWN,com.potg.don.maintenance.CleanupFailure.Code.ROLLBACK_UNKNOWN)) {
            assertThatThrownBy(() -> AdmissionLockTiDbProbe.withConfirmedCleanupReset(c, () -> { throw new com.potg.don.maintenance.CleanupFailure(code); })).isInstanceOf(com.potg.don.maintenance.CleanupFailure.class);
        }
        assertThat(resets.get()).isZero();
    }
    @Test void confirmedCleanupRollbackRestoresConnectionForNextBoundedStage() {
        AtomicInteger resets = new AtomicInteger();
        Connection c = proxy(Connection.class,(name,args) -> { if(name.equals("setAutoCommit")) resets.incrementAndGet(); return null; });
        assertThatThrownBy(() -> AdmissionLockTiDbProbe.withConfirmedCleanupReset(c, () -> { throw new com.potg.don.maintenance.CleanupFailure(com.potg.don.maintenance.CleanupFailure.Code.UNSAFE_BATCH); })).isInstanceOf(com.potg.don.maintenance.CleanupFailure.class);
        assertThat(resets.get()).isEqualTo(1);
    }
    @Test void sqlFailureProjectionContainsOnlyVendorAndStateNeverMessageOrIdentity() {
        SQLException sql = new SQLException("sensitive-canary-identity", "42000", 1142);
        var result = AdmissionLockTiDbProbe.safeSqlFailure(new AdmissionLockSqlBudget.Rejected("FIXTURE_SQL_FAILURE", sql));
        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of("failureVendor",1142,"failureSqlState","42000"));
        assertThat(result.toString()).doesNotContain("sensitive-canary");
        assertThat(AdmissionLockTiDbProbe.safeSqlFailure(new SQLException("private", "private-state", 0))).containsEntry("failureSqlState","NOT_OBSERVED");
    }
    @Test void sanitizedVersionDropsProviderIdentityAndRejectsOtherDatabases() {
        assertThat(AdmissionLockTiDbProbe.sanitizedVersion("8.0.11-TiDB-v8.5.4-secret-instance")).isEqualTo("8.0.11-TiDB-v8.5.4");
        assertThatThrownBy(()->AdmissionLockTiDbProbe.sanitizedVersion("8.4.0-MySQL")).hasMessage("ACTUAL_TIDB_REQUIRED");
    }
    @Test void privilegeFixturesCannotCreateDatasetRows() {
        for(String table:List.of("users","cards","transactions","budgets","demo_visit","analysis_job","peer_transaction_stats","dummy"))
            assertThat(AdmissionLockTiDbProbe.emptyInsert(table)).endsWith("WHERE 1=0");
        assertThatThrownBy(()->AdmissionLockTiDbProbe.emptyInsert("other_schema.users")).hasMessage("TABLE_NOT_REVIEWED");
    }
    record Run(java.util.Map<String,Object> result,AdmissionLockSqlBudget budget,AdmissionLockSyntheticJdbc jdbc,int workCalls,int cleanupCalls,boolean faultFired,boolean transactionResetFault) { }
    private Run exercise(int faultAt,boolean faultCleanup,boolean interrupt) throws Exception {
        var input=valid();
        var root=java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();
        input.put("baselinePath",root.resolve("scripts/verification/fixtures/managed-provider-schema.sql").toString());
        input.put("migrationPath",root.resolve("be/src/main/resources/db/demo/V001__demo_admission.sql").toString());
        input.put("lockMigrationPath",root.resolve("be/src/main/resources/db/demo/V002__demo_admission_lock.sql").toString());
        var jdbc=new AdmissionLockSyntheticJdbc(input);
        var probe=new AdmissionLockTiDbProbe(input,java.nio.file.Path.of("result"),java.nio.file.Path.of("ledger"),jdbc,(path,document)->{});
        AtomicInteger work=new AtomicInteger(),cleanup=new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean fired=new java.util.concurrent.atomic.AtomicBoolean(),resetFault=new java.util.concurrent.atomic.AtomicBoolean();
        probe.testBudget().charged=(cleaning,count)->{
            int current=(cleaning?cleanup:work).incrementAndGet();
            if(cleaning==faultCleanup&&current==faultAt){
                fired.set(true);
                resetFault.set(StackWalker.getInstance().walk(frames->frames.anyMatch(f->
                    f.getClassName().equals("org.springframework.jdbc.datasource.DataSourceUtils")&&f.getMethodName().equals("resetConnectionAfterTransaction")
                    ||f.getClassName().equals("org.springframework.jdbc.datasource.DataSourceTransactionManager")&&f.getMethodName().equals("doCleanupAfterCompletion"))));
                if(interrupt)probe.testBudget().cancelled=true;
                throw new AdmissionLockSqlBudget.Rejected(interrupt?"SYNTHETIC_INTERRUPT":"SYNTHETIC_EARLY_FAILURE");
            }
        };
        var result=probe.syntheticExecute();
        return new Run(result,probe.testBudget(),jdbc,work.get(),cleanup.get(),fired.get(),resetFault.get());
    }
    @Test void actualNormalWorkflowFitsFiniteBudgetAndUsesFourClients() throws Exception {
        Run run=exercise(-1,false,false);
        assertThat(run.result()).as("actual synthetic workflow: %s",run.result()).containsEntry("status","PASS").containsEntry("cleanupComplete",true)
            .containsEntry("schemaVerified",true).containsEntry("privilegesVerified",true).containsEntry("admissionVerified",true).containsEntry("cleanupVerified",true);
        assertThat(run.budget().connections).isEqualTo(4);assertThat(run.budget().work).isLessThanOrEqualTo(2000);assertThat(run.budget().cleanup).isLessThanOrEqualTo(500);
        assertThat(run.jdbc().schemaExists).isFalse();assertThat(run.jdbc().accounts).isEmpty();assertThat(run.jdbc().clients).allMatch(c->c.closed);
        System.out.println("SYNTHETIC_NORMAL work="+run.budget().work+" cleanup="+run.budget().cleanup+" charged_work_positions="+run.workCalls()+" cleanup_positions="+run.cleanupCalls()+" connections="+run.budget().connections);
    }
    @Test void everyWorkCommandEarlyFailureStillRunsBoundedOwnedCleanup() throws Exception {
        Run normal=exercise(-1,false,false);assertThat(normal.result()).containsEntry("status","PASS");
        int maxWork=0,maxCleanup=0,maxConnections=0,recoveredResets=0;
        for(int fault=1;fault<=normal.workCalls();fault++) {
            Run run=exercise(fault,false,false);
            assertThat(run.faultFired()).as("failure position %d was actually injected",fault).isTrue();
            assertThat(run.result()).as("failure position %d",fault).containsEntry("cleanupComplete",true);
            if("PASS".equals(run.result().get("status"))) {
                // Spring deliberately ignores connection restoration failures after a confirmed TX end.
                // Only that exact framework cleanup path may recover; all real product checks still run.
                assertThat(run.transactionResetFault()).as("only confirmed TX cleanup may recover at %d",fault).isTrue();
                assertThat(run.result()).containsEntry("schemaVerified",true).containsEntry("privilegesVerified",true)
                    .containsEntry("admissionVerified",true).containsEntry("cleanupVerified",true);
                recoveredResets++;
            } else assertThat(run.result()).containsEntry("status","FAIL");
            assertThat(run.budget().work).isLessThanOrEqualTo(2000);assertThat(run.budget().cleanup).isLessThanOrEqualTo(500);assertThat(run.budget().connections).isLessThanOrEqualTo(4);
            assertThat(run.jdbc().schemaExists).isFalse();assertThat(run.jdbc().accounts).isEmpty();assertThat(run.jdbc().clients).allMatch(c->c.closed);
            maxWork=Math.max(maxWork,run.budget().work);maxCleanup=Math.max(maxCleanup,run.budget().cleanup);maxConnections=Math.max(maxConnections,run.budget().connections);
        }
        System.out.println("SYNTHETIC_EARLY_FAILURE positions="+normal.workCalls()+" max_work="+maxWork+" max_cleanup="+maxCleanup+" max_connections="+maxConnections+" confirmed_tx_reset_recoveries="+recoveredResets);
    }
    @Test void cleanupFailureCannotBorrowWorkOrOpenFifthClient() throws Exception {
        Run normal=exercise(-1,false,false);assertThat(normal.result()).containsEntry("status","PASS");
        for(int fault=1;fault<=normal.cleanupCalls();fault++) {
            Run run=exercise(fault,true,false);
            assertThat(run.result()).as("cleanup failure %d",fault).containsEntry("status","FAIL").containsEntry("cleanupComplete",false);
            assertThat(run.budget().work).isEqualTo(normal.budget().work);assertThat(run.budget().cleanup).isLessThanOrEqualTo(500);assertThat(run.budget().connections).isEqualTo(4);
            assertThat(run.jdbc().clients).allMatch(c->c.closed);
        }
    }
    @Test void cancellationFromEveryWorkBoundaryStillClosesOwnedResources() throws Exception {
        Run normal=exercise(-1,false,false);assertThat(normal.result()).containsEntry("status","PASS");
        for(int fault=1;fault<=normal.workCalls();fault++){
            Run run=exercise(fault,false,true);
            assertThat(run.result()).as("interrupt position %d",fault).containsEntry("status","FAIL").containsEntry("cleanupComplete",true);
            assertThat(run.budget().work+run.budget().cleanup).isLessThanOrEqualTo(2500);assertThat(run.budget().connections).isLessThanOrEqualTo(4);
            assertThat(run.jdbc().clients).allMatch(c->c.closed);
        }
    }
    @Test void negativePrivilegeCodesAreProviderSpecificAndPositiveLockMappingIsUnchanged() {
        for(SQLException e:List.of(new SQLException("private", "42000",1142),new SQLException("private","42000",1143),new SQLException("private","HY000",8121)))
            assertThat(AdmissionLockTiDbProbe.expectedPrivilegeDenial(e)).isTrue();
        for(SQLException e:List.of(new SQLException("private","42000",8121),new SQLException("private","HY000",1142),new SQLException("private",null,8121),new SQLException("private","HY000",3572),new SQLException("private","40001",1213)))
            assertThat(AdmissionLockTiDbProbe.expectedPrivilegeDenial(e)).isFalse();
        assertThat(com.potg.don.demo.admission.DemoAdmissionSchema.isNowaitConflict(new SQLException("private","HY000",8121))).isFalse();
        assertThat(com.potg.don.demo.admission.DemoAdmissionSchema.isNowaitConflict(new SQLException("private","HY000",3572))).isTrue();
    }
    @Test void negativeAssertionStillRejectsSuccessfulWritesAndUnknownSqlStates() throws Exception {
        for(SQLException error:List.of(new SQLException("private","HY000",8121),new SQLException("private","42000",1142),new SQLException("private","42000",1143))) {
            Statement statement=proxy(Statement.class,(name,args)->{if(name.equals("execute"))throw error;return null;});
            Connection c=proxy(Connection.class,(name,args)->name.equals("createStatement")?statement:null);
            AdmissionLockTiDbProbe.requirePrivilegeDenied(c,"DELETE FROM users WHERE id=-1");
        }
        Statement successful=proxy(Statement.class,(name,args)->name.equals("execute")?false:null);
        Connection writable=proxy(Connection.class,(name,args)->name.equals("createStatement")?successful:null);
        assertThatThrownBy(()->AdmissionLockTiDbProbe.requirePrivilegeDenied(writable,"DELETE FROM users WHERE id=-1")).hasMessage("EXCESS_PRIVILEGE");
        Statement unknown=proxy(Statement.class,(name,args)->{if(name.equals("execute"))throw new SQLException("private","42000",8121);return null;});
        Connection unrecognized=proxy(Connection.class,(name,args)->name.equals("createStatement")?unknown:null);
        assertThatThrownBy(()->AdmissionLockTiDbProbe.requirePrivilegeDenied(unrecognized,"DELETE FROM users WHERE id=-1")).hasMessage("EXPECTED_PRIVILEGE_DENIAL");
    }
    @Test void negativeProbeProjectionUsesOnlyReviewedRoleOperationTags() {
        assertThat(AdmissionLockTiDbProbe.negativeOperation("UPDATE demo_capacity SET max_visitors=max_visitors WHERE id=-1")).isEqualTo("DEMO_CAPACITY_UPDATE");
        assertThat(AdmissionLockTiDbProbe.negativeOperation("ALTER TABLE users COMMENT='private-canary'")).isEqualTo("USERS_ALTER");
        assertThat(AdmissionLockTiDbProbe.negativeOperation("INSERT INTO demo_admission_lock(id) SELECT 2 WHERE 1=0")).isEqualTo("DEMO_ADMISSION_LOCK_INSERT");
        assertThatThrownBy(()->AdmissionLockTiDbProbe.negativeOperation("DELETE FROM outside.private_table WHERE id=-1")).hasMessage("NEGATIVE_PROBE_NOT_REVIEWED");
    }
    static Map<String,String> valid() {
        Map<String,String> v = new HashMap<>();
        v.put("TIDB_HOST","fixture.invalid");v.put("TIDB_PORT","4000");v.put("TIDB_SETUP_USERNAME","fixture.root");v.put("TIDB_SETUP_PASSWORD","synthetic-input");
        v.put("schema","mtadmissionlock0123456789abcdef");v.put("runtimeAccount","fixture.m0123456789abcd");v.put("cleanupAccount","fixture.mfedcba98765432");
        v.put("runtimePassword","r".repeat(43));v.put("cleanupPassword","c".repeat(43));v.put("baselinePath","synthetic");v.put("migrationPath","synthetic");
        v.put("lockMigrationPath","synthetic");v.put("deadlineEpochMillis",Long.toString(System.currentTimeMillis()+600_000));v.put("workLimit","2000");v.put("cleanupLimit","500");v.put("connectionLimit","4");return v;
    }
    private static AdmissionLockSqlBudget budget() { return new AdmissionLockSqlBudget(System.currentTimeMillis()+600_000); }
    @FunctionalInterface interface Call { Object call(String method,Object[] args) throws Throwable; }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type,Call body) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,m,args)->body.call(m.getName(),args));
    }
}
