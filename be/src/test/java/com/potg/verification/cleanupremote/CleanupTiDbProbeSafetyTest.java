package com.potg.verification.cleanupremote;

import static org.assertj.core.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.potg.don.maintenance.CleanupCredentials;

class CleanupTiDbProbeSafetyTest {
    @Test void exactInputScopeAndBudgetCannotGrow() {
        Map<String,String> input=valid();CleanupTiDbProbe.validate(input);
        for(var entry:Map.of("workLimit","1201","cleanupLimit","301","connectionLimit","3","schema","existing","cleanupAccount","different.m0123456789abcd").entrySet()) {
            var changed=new HashMap<>(input);changed.put(entry.getKey(),entry.getValue());assertThatThrownBy(()->CleanupTiDbProbe.validate(changed)).isInstanceOf(CleanupSqlBudget.Rejected.class);
        }
        input.put("REDIS_HOST","fixture.invalid");assertThatThrownBy(()->CleanupTiDbProbe.validate(input)).hasMessage("INPUT_KEYS");
    }
    @Test void budgetChargesBeforeDelegationAndCleanupCannotBorrowOrRefund() {
        var budget=budget();budget.spend(1200);assertThatThrownBy(()->budget.spend(1)).hasMessage("WORK_BUDGET_OR_DEADLINE");
        budget.startCleanup();budget.spend(300);assertThatThrownBy(()->budget.spend(1)).hasMessage("CLEANUP_BUDGET_OR_DEADLINE");
        assertThat(budget.work).isEqualTo(1200);assertThat(budget.cleanup).isEqualTo(300);
    }
    @Test void twoConnectionsIncludeFailedOpeningAndNeverAllowThird() {
        var budget=budget();budget.opening();budget.opening();assertThat(budget.work).isEqualTo(64);
        assertThatThrownBy(budget::opening).hasMessage("CONNECTION_LIMIT");assertThat(budget.connections).isEqualTo(2);
    }
    @Test void batchUnwrapAndUnknownMetadataCannotBypassAccounting() throws Exception {
        var budget=budget();PreparedStatement statement=proxy(PreparedStatement.class,(method,args)->null);
        DatabaseMetaData metadata=proxy(DatabaseMetaData.class,(method,args)->null);
        Connection c=budget.observe(proxy(Connection.class,(method,args)->method.equals("prepareStatement")?statement:method.equals("getMetaData")?metadata:null));
        assertThatThrownBy(()->c.unwrap(Connection.class)).isInstanceOf(SQLException.class);
        var prepared=c.prepareStatement("INSERT INTO owned(id) VALUES(1)");assertThat(prepared.getConnection()).isSameAs(c);
        assertThatThrownBy(prepared::executeBatch).hasMessage("BATCH_DISABLED");
        assertThatThrownBy(()->c.getMetaData().getTables(null,null,null,null)).hasMessage("UNREVIEWED_METADATA");
        c.getMetaData().getColumns("fixture",null,"demo_visit",null);assertThat(budget.work).isEqualTo(8);
    }
    @Test void delegatesAreClosedEvenIfRollbackFails() {
        AtomicInteger close=new AtomicInteger();Connection c=proxy(Connection.class,(name,args)->switch(name){
            case "getAutoCommit"->false;case "rollback"->throw new SQLException("private");case "close"->{close.incrementAndGet();yield null;}case "isClosed"->true;default->null;
        });
        assertThat(CleanupTiDbProbe.closeOwnedClients(List.of(c),null)).isFalse();assertThat(close.get()).isEqualTo(1);
    }
    @Test void safeVersionAndErrorProjectionNeverEmitRawIdentity() {
        assertThat(CleanupTiDbProbe.sanitizedVersion("8.0.11-TiDB-v8.5.3-private-instance")).isEqualTo("8.0.11-TiDB-v8.5.3");
        assertThatThrownBy(()->CleanupTiDbProbe.sanitizedVersion("8.4.0-MySQL")).hasMessage("ACTUAL_TIDB_REQUIRED");
        assertThat(CleanupTiDbProbe.safeSqlFailure(new SQLException("private-canary","42000",1235)))
            .containsExactlyInAnyOrderEntriesOf(Map.of("failureVendor",1235,"failureSqlState","42000"));
    }
    private static List<String> grantLines(String quote) {
        List<String> lines=new ArrayList<>();String target=" TO "+quote+"fixture.m0123456789abcd"+quote+"@"+quote+"%"+quote;
        lines.add("GRANT USAGE ON *.*"+target);
        for(String table:List.of("users","cards","transactions","budgets","demo_visit","demo_capacity","demo_admission_lock","analysis_job","peer_transaction_stats","dummy")){
            String privileges=Set.of("users","cards","transactions","budgets","demo_visit").contains(table)?"DELETE, SELECT":table.equals("demo_admission_lock")?"UPDATE, SELECT":"SELECT";
            lines.add("GRANT "+privileges+" ON `owned_schema`.`"+table+"`"+target);
        }
        return lines;
    }
    @Test void exactShowGrantsAcceptsQuoteAndPrivilegeOrderVariationsOnly() {
        for(String quote:List.of("'","`"))CleanupTiDbProbe.verifyGrants(grantLines(quote),"owned_schema","fixture.m0123456789abcd");
        CleanupTiDbProbe.verifyGrants(grantLines("'").stream().map(line->line.replace(", ",",")).toList(),"owned_schema","fixture.m0123456789abcd");
        var unquoted=grantLines("'").stream().map(line->line.replace("`owned_schema`.","owned_schema.")).toList();
        CleanupTiDbProbe.verifyGrants(unquoted,"owned_schema","fixture.m0123456789abcd");
    }
    @Test void extraMissingGlobalSchemaRoleAndGrantOptionFailClosed() {
        for(String bad:List.of("GRANT ALL PRIVILEGES ON *.* TO 'fixture.m0123456789abcd'@'%'",
            "GRANT SELECT ON `owned_schema`.* TO 'fixture.m0123456789abcd'@'%'",
            "GRANT SELECT ON `outside`.`users` TO 'fixture.m0123456789abcd'@'%'",
            "GRANT SELECT, INSERT ON `owned_schema`.`users` TO 'fixture.m0123456789abcd'@'%'",
            "GRANT SELECT ON `owned_schema`.`users` TO 'fixture.m0123456789abcd'@'%' WITH GRANT OPTION",
            "GRANT 'privileged_role'@'%' TO 'fixture.m0123456789abcd'@'%'")){
            var lines=grantLines("'");lines.add(bad);assertThatThrownBy(()->CleanupTiDbProbe.verifyGrants(lines,"owned_schema","fixture.m0123456789abcd")).hasMessage("EXACT_GRANTS_REQUIRED");
        }
        for(String privileges:List.of("SELECT,SELECT","SELECT,","SELECT,,DELETE")) {
            var malformed=grantLines("'");malformed.set(1,"GRANT "+privileges+" ON `owned_schema`.`users` TO 'fixture.m0123456789abcd'@'%'");
            assertThatThrownBy(()->CleanupTiDbProbe.verifyGrants(malformed,"owned_schema","fixture.m0123456789abcd")).hasMessage("EXACT_GRANTS_REQUIRED");
        }
        var missing=grantLines("'");missing.removeLast();assertThatThrownBy(()->CleanupTiDbProbe.verifyGrants(missing,"owned_schema","fixture.m0123456789abcd")).hasMessage("EXACT_GRANTS_REQUIRED");
    }
    record Run(Map<String,Object> result,CleanupSqlBudget budget,CleanupSyntheticJdbc jdbc,int workCalls,int cleanupCalls,boolean faultFired) { }
    private Run exercise(int faultAt,boolean faultCleanup,boolean interrupt) throws Exception {
        var input=valid();Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();
        input.put("baselinePath",root.resolve("scripts/verification/fixtures/managed-provider-schema.sql").toString());
        input.put("migrationPath",root.resolve("be/src/main/resources/db/demo/V001__demo_admission.sql").toString());
        input.put("lockMigrationPath",root.resolve("be/src/main/resources/db/demo/V002__demo_admission_lock.sql").toString());
        var jdbc=new CleanupSyntheticJdbc(input);
        var boundary=new CleanupTiDbProbe.ProductBoundary(){
            @Override public void verifyPackage(String path) { }
            @Override public CleanupCredentials readCredentials(Path path,String schema){return new CleanupCredentials(
                "jdbc:mysql://"+input.get("TIDB_HOST")+":"+input.get("TIDB_PORT")+"/"+schema+"?sslMode=VERIFY_IDENTITY&connectTimeout=5000&socketTimeout=10000&readOnlyPropagatesToServer=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                input.get("cleanupAccount"),input.get("cleanupPassword"),1000);}
        };
        var probe=new CleanupTiDbProbe(input,Path.of("result"),Path.of("ledger"),jdbc,(path,document)->{},connection->jdbc.observation,boundary);
        AtomicInteger work=new AtomicInteger(),cleanup=new AtomicInteger();java.util.concurrent.atomic.AtomicBoolean fired=new java.util.concurrent.atomic.AtomicBoolean();
        probe.testBudget().charged=(cleaning,count)->{
            int current=(cleaning?cleanup:work).incrementAndGet();
            if(cleaning==faultCleanup&&current==faultAt){fired.set(true);if(interrupt)probe.testBudget().cancelled=true;throw new CleanupSqlBudget.Rejected(interrupt?"SYNTHETIC_INTERRUPT":"SYNTHETIC_EARLY_FAILURE");}
        };
        var result=probe.syntheticExecute();return new Run(result,probe.testBudget(),jdbc,work.get(),cleanup.get(),fired.get());
    }
    @Test void actualNormalWorkflowFitsFiniteBudgetAndUsesTwoClients() throws Exception {
        Run run=exercise(-1,false,false);
        assertThat(run.result()).as("actual synthetic cleanup %s",run.result()).containsEntry("status","PASS").containsEntry("cleanupComplete",true)
            .containsEntry("verifyVerified",true).containsEntry("dryRunVerified",true).containsEntry("applyVerified",true).containsEntry("confirmedReuseResets",2);
        assertThat(run.budget().connections).isEqualTo(2);assertThat(run.budget().work).isLessThanOrEqualTo(1200);assertThat(run.budget().cleanup).isLessThanOrEqualTo(300);
        assertThat(run.jdbc().schemaExists).isFalse();assertThat(run.jdbc().accounts).isEmpty();assertThat(run.jdbc().clients).allMatch(c->c.closed);
        proof(Map.of("kind","NORMAL","work",run.budget().work,"cleanup",run.budget().cleanup,"work_positions",run.workCalls(),"cleanup_positions",run.cleanupCalls(),"connections",run.budget().connections,"metadata_calls",run.budget().metadataCalls));
    }
    @Test void everyWorkCommandEarlyFailureStillRunsBoundedOwnedCleanup() throws Exception {
        Run normal=exercise(-1,false,false);assertThat(normal.result()).containsEntry("status","PASS");int maxWork=0,maxCleanup=0;
        for(int fault=1;fault<=normal.workCalls();fault++){
            Run run=exercise(fault,false,false);assertThat(run.faultFired()).isTrue();
            assertThat(run.result()).as("work failure %d",fault).containsEntry("status","FAIL").containsEntry("cleanupComplete",true);
            assertThat(run.budget().work).isLessThanOrEqualTo(1200);assertThat(run.budget().cleanup).isLessThanOrEqualTo(300);assertThat(run.budget().connections).isLessThanOrEqualTo(2);
            assertThat(run.jdbc().schemaExists).isFalse();assertThat(run.jdbc().accounts).isEmpty();assertThat(run.jdbc().clients).allMatch(c->c.closed);
            maxWork=Math.max(maxWork,run.budget().work);maxCleanup=Math.max(maxCleanup,run.budget().cleanup);
        }
        proof(Map.of("kind","EARLY_FAILURE","positions",normal.workCalls(),"max_work",maxWork,"max_cleanup",maxCleanup,"max_connections",2));
    }
    @Test void cleanupFailureCannotBorrowWorkOrOpenThirdClient() throws Exception {
        Run normal=exercise(-1,false,false);assertThat(normal.result()).containsEntry("status","PASS");int maxCleanup=0;
        for(int fault=1;fault<=normal.cleanupCalls();fault++){
            Run run=exercise(fault,true,false);assertThat(run.faultFired()).isTrue();assertThat(run.result()).containsEntry("status","FAIL").containsEntry("cleanupComplete",false);
            assertThat(run.budget().work).isEqualTo(normal.budget().work);assertThat(run.budget().cleanup).isLessThanOrEqualTo(300);assertThat(run.budget().connections).isEqualTo(2);assertThat(run.jdbc().clients).allMatch(c->c.closed);
            maxCleanup=Math.max(maxCleanup,run.budget().cleanup);
        }
        proof(Map.of("kind","FINALLY","positions",normal.cleanupCalls(),"max_work",normal.budget().work,"max_cleanup",maxCleanup,"max_connections",2));
    }
    @Test void cancellationFromEveryWorkBoundaryStillClosesOwnedResources() throws Exception {
        Run normal=exercise(-1,false,false);assertThat(normal.result()).containsEntry("status","PASS");int maxWork=0,maxCleanup=0;
        for(int fault=1;fault<=normal.workCalls();fault++){
            Run run=exercise(fault,false,true);assertThat(run.faultFired()).isTrue();assertThat(run.result()).containsEntry("status","FAIL").containsEntry("cleanupComplete",true);
            assertThat(run.budget().work).isLessThanOrEqualTo(1200);assertThat(run.budget().cleanup).isLessThanOrEqualTo(300);assertThat(run.budget().connections).isLessThanOrEqualTo(2);assertThat(run.jdbc().clients).allMatch(c->c.closed);
            maxWork=Math.max(maxWork,run.budget().work);maxCleanup=Math.max(maxCleanup,run.budget().cleanup);
        }
        proof(Map.of("kind","INTERRUPT","positions",normal.workCalls(),"max_work",maxWork,"max_cleanup",maxCleanup,"max_connections",2));
    }
    private static void proof(Map<String,Object> value) throws Exception {System.out.println("CLEANUP_SYNTHETIC_PROOF "+new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value));}
    static Map<String,String> valid(){
        Map<String,String> v=new HashMap<>();v.put("TIDB_HOST","fixture.invalid");v.put("TIDB_PORT","4000");v.put("TIDB_SETUP_USERNAME","fixture.root");v.put("TIDB_SETUP_PASSWORD","synthetic-input");
        v.put("schema","mtcleanup0123456789abcdef");v.put("cleanupAccount","fixture.m0123456789abcd");v.put("cleanupPassword","c".repeat(43));
        for(String key:List.of("baselinePath","migrationPath","lockMigrationPath","cleanupConfigPath","maintenanceClassesPath"))v.put(key,"synthetic");
        v.put("deadlineEpochMillis",Long.toString(System.currentTimeMillis()+600_000));v.put("workLimit","1200");v.put("cleanupLimit","300");v.put("connectionLimit","2");return v;
    }
    private static CleanupSqlBudget budget(){return new CleanupSqlBudget(System.currentTimeMillis()+600_000);}
    @FunctionalInterface interface Call{Object call(String name,Object[] args)throws Throwable;}
    @SuppressWarnings("unchecked") private static <T>T proxy(Class<T>type,Call body){return(T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->body.call(m.getName(),a));}
}
