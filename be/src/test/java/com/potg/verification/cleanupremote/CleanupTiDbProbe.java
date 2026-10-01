package com.potg.verification.cleanupremote;

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


import com.fasterxml.jackson.databind.ObjectMapper;
import com.potg.don.demo.admission.*;
import com.potg.don.demo.seed.DemoSeedScenario;
import com.potg.don.maintenance.*;
import com.potg.don.maintenance.CleanupOptions.Mode;

/** One owned schema, one restricted cleanup role and two bounded JDBC clients.
 * No Spring Boot context, HTTP, Redis, external AI or retry is initialized. */
public final class CleanupTiDbProbe {
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
    private final CleanupSqlBudget budget;
    private final Map<String, Object> result = new LinkedHashMap<>();
    private final Map<String, Boolean> checks = new LinkedHashMap<>();
    private final List<Connection> clients = new ArrayList<>();
    private final Set<String> createdTables = new LinkedHashSet<>();
    private final Set<Long> ownedUsers = new LinkedHashSet<>();
    private final String schema, cleanupAccount;
    private final Set<String> createdAccounts = new LinkedHashSet<>();
    private final Set<String> attemptedAccounts = new LinkedHashSet<>();
    private boolean schemaAttempted, schemaCreated, cleaned, cleanupComplete;
    private boolean agingAttempted;
    private boolean schemaVerified, verifyVerified, dryRunVerified, applyVerified, privilegesVerified;
    private final DriverInspector inspector;
    private final ProductBoundary product;
    private DriverObservation driver;
    private Connection admin, cleaner;
    private String phase = "INPUT";
    private String sqlFailurePhase, sqlFailureState;
    private int sqlFailureVendor;
    private static final LocalDate ANCHOR = LocalDate.of(2024, 2, 29);

    private CleanupTiDbProbe(Map<String, String> config, Path resultPath, Path ledgerPath) throws Exception {
        this(config,resultPath,ledgerPath,DriverManager::getConnection,CleanupTiDbProbe::durable,DriverObservation::install,new ProductBoundary() {});
    }
    CleanupTiDbProbe(Map<String,String> config, Path resultPath,Path ledgerPath,
        ConnectionFactory connectionFactory,DocumentWriter documentWriter,DriverInspector inspector,ProductBoundary product) throws Exception {
        validate(config);
        this.connectionFactory=connectionFactory; this.documentWriter=documentWriter;this.inspector=inspector;this.product=product;
        this.config = config; this.resultPath = resultPath; this.ledgerPath = ledgerPath;
        schema = config.get("schema"); cleanupAccount = config.get("cleanupAccount");
        budget = new CleanupSqlBudget(Long.parseLong(config.get("deadlineEpochMillis")));
        budget.sqlFailureObserver = (vendor, state) -> { sqlFailurePhase = phase; sqlFailureVendor = vendor; sqlFailureState = state; };
        result.put("status", "BLOCKED"); result.put("checks", checks);result.put("packagedServiceVerified",false);
        writeLedger();
    }
    public static void main(String[] args) {
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        CleanupTiDbProbe probe = null;
        int exit = 2;
        try {
            require(args.length == 0, "ARGUMENTS_REJECTED");
            Path input = privateFile(System.getenv("CLEANUP_CHECK_CONFIG"));
            Path result = privateFile(System.getenv("CLEANUP_CHECK_RESULT"));
            Path ledger = privateFile(System.getenv("CLEANUP_CHECK_LEDGER"));
            require(input.getParent().equals(result.getParent()) && input.getParent().equals(ledger.getParent()), "PRIVATE_DIRECTORY_MISMATCH");
            Map<String, String> config = new LinkedHashMap<>();
            var root = JSON.readTree(Files.readAllBytes(input));
            require(root.isObject(), "INPUT_OBJECT");
            root.fields().forEachRemaining(e -> config.put(e.getKey(), e.getValue().asText()));
            probe = new CleanupTiDbProbe(config, result, ledger);
            CleanupTiDbProbe running = probe;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.budget.cancelled = true;
                synchronized (running) { running.cleanup(); running.save(); }
            }, "cleanup-only-owned-cleanup"));
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
        require(values.keySet().equals(Set.of("TIDB_HOST", "TIDB_PORT", "TIDB_SETUP_USERNAME", "TIDB_SETUP_PASSWORD", "schema", "cleanupAccount", "cleanupPassword", "baselinePath", "migrationPath", "lockMigrationPath", "cleanupConfigPath", "maintenanceClassesPath", "deadlineEpochMillis", "workLimit", "cleanupLimit", "connectionLimit")), "INPUT_KEYS");
        require(values.get("TIDB_HOST").matches("[A-Za-z0-9.-]{1,253}") && !values.get("TIDB_HOST").contains(".."), "HOST_FORMAT");
        int port = Integer.parseInt(values.get("TIDB_PORT")); require(port > 0 && port <= 65535, "PORT_RANGE");
        require(values.get("TIDB_SETUP_USERNAME").matches("[A-Za-z0-9]{1,16}\\.[A-Za-z0-9_]+"), "SETUP_PREFIX");
        String prefix = values.get("TIDB_SETUP_USERNAME").split("\\.", 2)[0] + ".m";
        for (String key : List.of("cleanupAccount")) require(values.get(key).startsWith(prefix) && values.get(key).substring(prefix.length()).matches("[a-f0-9]{14}") && values.get(key).length() <= 32, "ACCOUNT_FORMAT");
        require(values.get("schema").matches("mtcleanup[a-f0-9]{16}"), "SCHEMA_FORMAT");
        for (String key : List.of("cleanupPassword")) require(values.get(key).matches("[A-Za-z0-9_-]{40,128}"), "GENERATED_PASSWORD_FORMAT");
        require(!values.get("TIDB_SETUP_PASSWORD").isBlank(), "SETUP_PASSWORD");
        require("1200".equals(values.get("workLimit")) && "300".equals(values.get("cleanupLimit")) && "2".equals(values.get("connectionLimit")), "FIXED_BUDGET");
        long remaining = Long.parseLong(values.get("deadlineEpochMillis")) - System.currentTimeMillis();
        require(remaining > 0 && remaining <= 900_000, "FINITE_DEADLINE");
    }
    CleanupSqlBudget testBudget() { return budget; }
    Map<String,Object> syntheticExecute() {
        try { run(); result.put("status","PASS"); }
        catch(Exception error) { result.put("status","FAIL"); result.put("failedPhase",phase); result.put("failureCode",fixedFailure(error)); }
        finally { cleanup(); save(); }
        return Collections.unmodifiableMap(result);
    }
    private void run() throws Exception {
        phase="REVIEWED_INPUT";
        product.verifyPackage(config.get("maintenanceClassesPath"));
        CleanupCredentials credentials=product.readCredentials(Path.of(config.get("cleanupConfigPath")),schema);
        require(credentials.username().equals(cleanupAccount) && credentials.password().equals(config.get("cleanupPassword"))
            && credentials.expectedMax()==1000,"CLEANUP_CREDENTIAL_MATCH");
        String expectedUrl="jdbc:mysql://"+config.get("TIDB_HOST")+":"+config.get("TIDB_PORT")+"/"+schema
            +"?sslMode=VERIFY_IDENTITY&connectTimeout=5000&socketTimeout=10000&readOnlyPropagatesToServer=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        require(credentials.url().equals(expectedUrl),"CLEANUP_URL_MATCH");
        List<String> baseline=statements(Path.of(config.get("baselinePath")),BASELINE_SHA,7);
        List<String> migration=statements(Path.of(config.get("migrationPath")),MIGRATION_SHA,3);
        List<String> lockMigration=statements(Path.of(config.get("lockMigrationPath")),LOCK_SHA,2);
        result.put("packagedServiceVerified",true);pass("PACKAGED_PRODUCT_AND_CLEANUP_CONFIG");
        phase="SETUP";
        admin=connect(config.get("TIDB_SETUP_USERNAME"),config.get("TIDB_SETUP_PASSWORD"),null);
        var initialVersion=rows(admin,"SELECT VERSION()");require(initialVersion.size()==1,"ACTUAL_TIDB_REQUIRED");
        result.put("databaseVersion",sanitizedVersion(initialVersion.getFirst().getFirst().toString()));
        require(scalar(admin,"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",schema)==0,"FRESH_SCHEMA_REQUIRED");
        require(accountAbsent(cleanupAccount),"FRESH_ACCOUNT_REQUIRED");
        schemaAttempted=true;writeLedger();
        execute(admin,"CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        schemaCreated=true;writeLedger();admin.setCatalog(schema);
        for(List<String> group:List.of(baseline,migration,lockMigration))for(String sql:group){
            execute(admin,sql);if(sql.startsWith("CREATE TABLE"))createdTables.add(sql.split("\\s+")[2]);writeLedger();
        }
        require(createdTables.equals(TABLES),"TEN_TABLES_CREATED");
        createAccount(cleanupAccount,config.get("cleanupPassword"));grants(cleanupAccount);
        cleaner=connect(credentials.username(),credentials.password(),credentials.url());
        require(driver.propagationDisabled,"EFFECTIVE_PROPAGATION_FALSE");pass("TWO_TLS_CONNECTIONS_PROPAGATION_FALSE");
        phase="SCHEMA_AND_GRANTS";
        DemoAdmissionSchema.verify(cleaner);
        require(DemoAdmissionSchema.verifySnapshot(cleaner,1000).visitCount()==0,"INITIAL_COUNT_ZERO");
        require(scalar(cleaner,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'")==10,"CLEANUP_TEN_TABLES_VISIBLE");
        require(scalar(cleaner,"SELECT COUNT(*) FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE() AND referenced_table_name IS NOT NULL")==4,"CLEANUP_FOUR_FKS_VISIBLE");
        require(scalar(admin,"SELECT COUNT(*) FROM information_schema.key_column_usage WHERE referenced_table_schema=? AND constraint_schema<>?",schema,schema)==0,"ADMIN_NO_EXTERNAL_FK");
        List<String> grantLines=rows(cleaner,"SHOW GRANTS FOR CURRENT_USER()").stream().map(row->row.getFirst().toString()).toList();
        verifyGrants(grantLines,schema,cleanupAccount);
        schemaVerified=true;privilegesVerified=true;pass("EXACT_CLEANUP_GRANTS");pass("SCHEMA_METADATA");
        phase="ADMIN_FIXTURE";
        var unrelatedBefore=unrelatedSnapshot(admin);Dataset safe=seed();
        require(scalar(admin,"SELECT COUNT(*) FROM users")==1&&scalar(admin,"SELECT COUNT(*) FROM cards")==1
            &&scalar(admin,"SELECT COUNT(*) FROM transactions")==240&&scalar(admin,"SELECT COUNT(*) FROM budgets")==72
            &&scalar(admin,"SELECT COUNT(*) FROM demo_visit")==1,"V1_COUNTS");
        require(scalar(admin,"SELECT COUNT(*) FROM cards WHERE card_no IS NOT NULL OR cvc IS NOT NULL")==0
            &&scalar(admin,"SELECT COUNT(*) FROM users WHERE file_id IS NOT NULL")==0
            &&scalar(admin,"SELECT COUNT(*) FROM budgets WHERE initial_amount IS NOT NULL OR initial_file_id IS NOT NULL OR predicted_at IS NOT NULL")==0,"V1_NULL_INVARIANTS");
        update(admin,"UPDATE transactions SET category=? WHERE id=? AND card_id=?",1,"마트 / 편의점",safe.practice,safe.card);
        agingAttempted=true;writeLedger();
        update(admin,"UPDATE demo_visit SET created_at=UTC_TIMESTAMP(6)-INTERVAL 25 HOUR,session_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 HOUR WHERE user_id=?",1,safe.user);
        require(unrelatedBefore.equals(unrelatedSnapshot(admin)),"UNRELATED_UNCHANGED");pass("ONE_MUTABLE_V1_DATASET_315");
        var allBefore=snapshot(admin);
        phase="VERIFY";
        var verified=runMode(Mode.VERIFY);
        require(verified.candidates()==0&&verified.deleted()==0&&verified.countBefore()==1&&verified.countAfter()==1,"VERIFY_RESULT");
        require(allBefore.equals(snapshot(admin)),"VERIFY_ROWS_UNCHANGED");verifyVerified=true;pass("VERIFY_READ_ONLY_RR_ROLLBACK_DML_ZERO");
        normalizeCleaner();
        phase="DRY_RUN";
        var dry=runMode(Mode.DRY_RUN);
        require(dry.candidates()==1&&dry.deleted()==0&&dry.countBefore()==1&&dry.countAfter()==1,"DRY_RUN_RESULT");
        require(allBefore.equals(snapshot(admin)),"DRY_RUN_ROWS_UNCHANGED");dryRunVerified=true;pass("DRY_RUN_READ_ONLY_RR_ROLLBACK_DML_ZERO");
        normalizeCleaner();
        phase="APPLY";
        var applied=runMode(Mode.APPLY);
        require(applied.candidates()==1&&applied.deleted()==1&&applied.countBefore()==1&&applied.countAfter()==0,"APPLY_RESULT");
        for(String table:List.of("users","cards","transactions","budgets","demo_visit"))require(scalar(admin,"SELECT COUNT(*) FROM "+table)==0,"DATASET_ABSENT");
        require(unrelatedBefore.equals(unrelatedSnapshot(admin)),"UNRELATED_UNCHANGED");
        require(DemoAdmissionSchema.verifySnapshot(admin,1000).visitCount()==0,"FINAL_COUNT_ZERO");
        applyVerified=true;pass("APPLY_RC_EXACT_DELETES_COMMIT");pass("SINGLETONS_UNRELATED_UNCHANGED");
    }
    interface ProductBoundary {
        default void verifyPackage(String classesPath) throws Exception {
            Path expected=Path.of(classesPath).toRealPath();
            for(Class<?> type:List.of(DemoCleanupService.class,CleanupCredentials.class,DemoAdmissionSchema.class,DemoSeedScenario.class)){
                Path actual=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
                require(actual.equals(expected),"PACKAGED_PRODUCT_ORIGIN");
            }
        }
        default CleanupCredentials readCredentials(Path path,String schema) {return CleanupCredentials.read(path,schema);}
    }
    @FunctionalInterface interface DriverInspector {DriverObservation install(Connection connection) throws Exception;}
    static final class DriverObservation {
        boolean propagationDisabled;
        int readOnlyStatements,commits,rollbacks,dml;
        static DriverObservation install(Connection connection) throws Exception {
            DriverObservation counters=new DriverObservation();
            Class<?> propertyKey=Class.forName("com.mysql.cj.conf.PropertyKey");
            Object key=java.lang.Enum.valueOf(propertyKey.asSubclass(Enum.class),"readOnlyPropagatesToServer");
            Object propertySet=connection.getClass().getMethod("getPropertySet").invoke(connection);
            Object property=Class.forName("com.mysql.cj.conf.PropertySet").getMethod("getBooleanProperty",propertyKey).invoke(propertySet,key);
            counters.propagationDisabled=Boolean.FALSE.equals(Class.forName("com.mysql.cj.conf.RuntimeProperty").getMethod("getValue").invoke(property));
            Class<?> api=Class.forName("com.mysql.cj.interceptors.QueryInterceptor");
            Object interceptor=java.lang.reflect.Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(proxy,method,args)->{
                String name=method.getName();
                if(name.equals("executeTopLevelOnly"))return false;
                if(name.equals("init"))return proxy;
                if(name.equals("hashCode"))return System.identityHashCode(proxy);
                if(name.equals("equals"))return proxy==args[0];
                if(name.equals("toString"))return "CleanupRemoteDriverObserver";
                if(name.equals("preProcess")&&args.length==2&&args[0] instanceof java.util.function.Supplier<?> supplier){
                    String sql=supplier.get().toString().strip().replaceAll("\\s+"," ").toUpperCase(Locale.ROOT);
                    if(sql.equals("SET SESSION TRANSACTION READ ONLY"))counters.readOnlyStatements++;
                    if(sql.equals("COMMIT"))counters.commits++;
                    if(sql.equals("ROLLBACK"))counters.rollbacks++;
                    if(sql.matches("^(INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE) .*"))counters.dml++;
                }
                return null;
            });
            Object session=connection.getClass().getMethod("getSession").invoke(connection);
            session.getClass().getMethod("setQueryInterceptors",List.class).invoke(session,List.of(interceptor));
            return counters;
        }
    }
    private DemoCleanupService.Result runMode(Mode mode) throws Exception {
        int dml=budget.dml,ddl=budget.ddl,updates=budget.updates,deletes=budget.deletes;
        int driverDml=driver.dml,readOnly=driver.readOnlyStatements,driverCommit=driver.commits,driverRollback=driver.rollbacks;
        int commits=budget.commits,rollbacks=budget.rollbacks,unsupported=budget.unsupported1235;
        var modes=(Map<String,Object>)result.computeIfAbsent("modeObservations",ignored->new LinkedHashMap<String,Object>());
        Map<String,Object> observed=new LinkedHashMap<>();modes.put(mode.name(),observed);
        try {
            var value=new DemoCleanupService().execute(cleaner,mode,1,1000);
            boolean expectedReadOnly=mode!=Mode.APPLY;
            require(cleaner.isReadOnly()==expectedReadOnly&&!cleaner.getAutoCommit(),"MODE_CONNECTION_STATE");
            int expectedIsolation=mode==Mode.APPLY?Connection.TRANSACTION_READ_COMMITTED:Connection.TRANSACTION_REPEATABLE_READ;
            require(cleaner.getTransactionIsolation()==expectedIsolation,"MODE_ISOLATION");
            observed.put("stateObserved",true);observed.put("readOnly",expectedReadOnly);observed.put("autoCommit",false);
            observed.put("isolation",mode==Mode.APPLY?"READ_COMMITTED":"REPEATABLE_READ");
            observed.put("serverReadOnlyStatements",driver.readOnlyStatements-readOnly);
            observed.put("delegateDml",budget.dml-dml);observed.put("delegateDdl",budget.ddl-ddl);
            observed.put("driverDml",driver.dml-driverDml);observed.put("commits",budget.commits-commits);observed.put("rollbacks",budget.rollbacks-rollbacks);
            observed.put("unsupported1235",budget.unsupported1235-unsupported);observed.put("guardViolations",0);
            require(budget.unsupported1235==unsupported,"NO_UNSUPPORTED_READ_ONLY_FAILURE");
            require(driver.readOnlyStatements==readOnly,"NO_SERVER_READ_ONLY_STATEMENT");
            if(mode==Mode.APPLY){
                require(budget.updates==updates&&budget.deletes-deletes==5&&budget.dml-dml==5&&budget.ddl==ddl,"APPLY_EXACT_FIVE_DELETES");
                require(budget.commits-commits==1&&budget.rollbacks==rollbacks&&driver.commits-driverCommit==1,"APPLY_COMMIT_CONFIRMED");
            } else {
                require(budget.dml==dml&&budget.ddl==ddl&&driver.dml==driverDml,"READ_ONLY_DML_DDL_ZERO");
                require(budget.rollbacks-rollbacks==1&&budget.commits==commits&&driver.rollbacks-driverRollback==1,"READ_ONLY_ROLLBACK_CONFIRMED");
            }
            observed.put("completed",true);return value;
        } catch(Exception error){
            try { observed.put("readOnly",cleaner.isReadOnly());observed.put("autoCommit",cleaner.getAutoCommit());
                int isolation=cleaner.getTransactionIsolation();
                observed.put("isolation",isolation==Connection.TRANSACTION_READ_COMMITTED?"READ_COMMITTED":isolation==Connection.TRANSACTION_REPEATABLE_READ?"REPEATABLE_READ":"OTHER");
                observed.put("stateObserved",true);
            } catch(Exception unavailable) {observed.put("stateObserved",false);}
            observed.put("completed",false);observed.put("serverReadOnlyStatements",driver.readOnlyStatements-readOnly);
            observed.put("delegateDml",budget.dml-dml);observed.put("delegateDdl",budget.ddl-ddl);
            observed.put("unsupported1235",budget.unsupported1235-unsupported);
            observed.put("guardViolations",error instanceof CleanupFailure failure&&failure.code()==CleanupFailure.Code.READ_ONLY_GUARD_REJECTED?1:0);
            throw error;
        }
    }
    private void normalizeCleaner() throws Exception {
        // runMode returned only after confirmed rollback; never use autocommit to finish an unknown TX.
        cleaner.setAutoCommit(true);cleaner.setReadOnly(false);cleaner.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        require(cleaner.getAutoCommit()&&!cleaner.isReadOnly()&&cleaner.getTransactionIsolation()==Connection.TRANSACTION_REPEATABLE_READ,"CONFIRMED_REUSE_STATE");
        int count=((Number)result.getOrDefault("confirmedReuseResets",0)).intValue();result.put("confirmedReuseResets",count+1);
    }
    static void verifyGrants(List<String> lines,String schema,String account) {
        Map<String,Set<String>> actual=new TreeMap<>();boolean usage=false;
        Set<String> suffixes=Set.of(" TO '"+account+"'@'%'"," TO `"+account+"`@`%`"," TO '"+account+"'@`%`"," TO `"+account+"`@'%'");
        for(String line:lines){
            String normalized=line.strip().replaceAll("\\s+"," ");
            String suffix=suffixes.stream().filter(normalized::endsWith).findFirst().orElse("");
            require(!suffix.isEmpty()&&!normalized.contains("WITH GRANT OPTION"),"EXACT_GRANTS_REQUIRED");
            String grant=normalized.substring(0,normalized.length()-suffix.length());
            if(grant.equals("GRANT USAGE ON *.*")){require(!usage,"EXACT_GRANTS_REQUIRED");usage=true;continue;}
            String prefix="GRANT ";int on=grant.indexOf(" ON ");require(grant.startsWith(prefix)&&on>prefix.length(),"EXACT_GRANTS_REQUIRED");
            String object=grant.substring(on+4);String schemaPrefix=object.startsWith("`"+schema+"`.")?"`"+schema+"`.":schema+".";
            require(object.startsWith(schemaPrefix),"EXACT_GRANTS_REQUIRED");
            String table=object.substring(schemaPrefix.length());
            if(table.startsWith("`")&&table.endsWith("`"))table=table.substring(1,table.length()-1);
            require(TABLES.contains(table)&&!actual.containsKey(table),"EXACT_GRANTS_REQUIRED");
            List<String> tokens=Arrays.stream(grant.substring(prefix.length(),on).split(",",-1)).map(String::strip).toList();
            Set<String> permissions=new HashSet<>(tokens);
            require(tokens.stream().noneMatch(String::isEmpty)&&permissions.size()==tokens.size(),"EXACT_GRANTS_REQUIRED");
            Set<String> expected=new HashSet<>(Set.of("SELECT"));
            if(Set.of("users","cards","transactions","budgets","demo_visit").contains(table))expected.add("DELETE");
            if(table.equals("demo_admission_lock"))expected.add("UPDATE");
            require(permissions.equals(expected),"EXACT_GRANTS_REQUIRED");actual.put(table,permissions);
        }
        require(usage&&actual.keySet().equals(TABLES),"EXACT_GRANTS_REQUIRED");
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
    private record Dataset(long user,long card,long practice) {}
    private Dataset seed() throws Exception {
        admin.setAutoCommit(false);
        try {
            long user=insert(admin,"INSERT INTO users(created_at,email,name) VALUES(UTC_TIMESTAMP(6),?,?)","cleanup-fixture@moneytoad.invalid","합성 정리 검증");
            ownedUsers.add(user);writeLedger();
            long card=insert(admin,"INSERT INTO cards(created_at,user_id,card_no,cvc) VALUES(UTC_TIMESTAMP(6),?,NULL,NULL)",user);
            var scenario=DemoSeedScenario.generate(ANCHOR);long practice=0;
            for(var row:scenario.transactions()){
                long id=insert(admin,"INSERT INTO transactions(created_at,card_id,transaction_date_time,amount,merchant_name,category) VALUES(UTC_TIMESTAMP(6),?,?,?,?,?)",card,row.dateTime(),row.amount(),row.merchantName(),row.category());
                if(row.merchantName().equals(DemoSeedScenario.PRACTICE_MERCHANT))practice=id;
            }
            for(var row:scenario.budgets())insert(admin,"INSERT INTO budgets(user_id,budget_date,amount,category,is_overridden) VALUES(?,?,?,?,0)",user,row.date(),row.amount(),row.category());
            update(admin,"INSERT INTO demo_visit(user_id,created_at,scenario_version,session_expires_at) VALUES(?,UTC_TIMESTAMP(6),?,?)",1,user,DemoSeedScenario.VERSION,LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
            require(practice>0,"FIXTURE_IDENTITIES");admin.commit();admin.setAutoCommit(true);return new Dataset(user,card,practice);
        } catch(Exception error){admin.rollback();throw error;}
    }
    static boolean closeOwnedClients(List<Connection> clients, Connection last) {
        boolean closed = true;
        for (Connection connection : clients) if (connection != last) {
            try { if (!connection.getAutoCommit()) connection.rollback(); } catch (Exception ignored) { closed = false; }
            finally { try { connection.close(); if (!connection.isClosed()) closed = false; } catch (Exception ignored) { closed = false; } }
        }
        return closed;
    }
    private void grants(String account) throws SQLException {
        for(String table:TABLES)execute(admin,"GRANT SELECT ON `"+schema+"`."+table+" TO '"+account+"'@'%'");
        for(String table:List.of("users","cards","transactions","budgets","demo_visit"))execute(admin,"GRANT DELETE ON `"+schema+"`."+table+" TO '"+account+"'@'%'");
        execute(admin,"GRANT UPDATE ON `"+schema+"`.demo_admission_lock TO '"+account+"'@'%'");
    }
    private void createAccount(String account, String password) throws Exception {
        attemptedAccounts.add(account); writeLedger();
        execute(admin, "CREATE USER '" + account + "'@'%' IDENTIFIED BY '" + password + "'");
        createdAccounts.add(account); writeLedger();
    }
    private Connection connect(String username, String password, String cleanupUrl) throws Exception {
        budget.opening();
        Properties properties = new Properties();
        properties.setProperty("user", username); properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "5000"); properties.setProperty("socketTimeout", "10000");
        properties.setProperty("autoReconnect", "false"); properties.setProperty("allowMultiQueries", "false");
        properties.setProperty("useServerPrepStmts", "false"); properties.setProperty("rewriteBatchedStatements", "false");
        properties.setProperty("logger", "com.mysql.cj.log.NullLogger");
        properties.setProperty("connectionTimeZone", "UTC"); properties.setProperty("forceConnectionTimeZoneToSession", "true");
        String url=cleanupUrl==null?"jdbc:mysql://"+config.get("TIDB_HOST")+":"+config.get("TIDB_PORT")+"/?sslMode=VERIFY_IDENTITY":cleanupUrl;
        Connection raw=connectionFactory.open(url,properties);
        Connection connection=budget.observe(raw);clients.add(connection);
        if(cleanupUrl!=null)driver=inspector.install(raw);
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
            require(rows.next() && rows.getString(2) != null && !rows.getString(2).isBlank(), "TLS_CIPHER_REQUIRED");
        }
        return connection;
    }
    private boolean accountAbsent(String account) throws SQLException {
        try (var statement = admin.createStatement(); var ignored = statement.executeQuery("SHOW GRANTS FOR '" + account + "'@'%'") ) { return false; }
        catch (SQLException error) { if (error.getErrorCode() == 1141) return true; throw error; }
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
                for (String account : List.of(cleanupAccount)) if (attemptedAccounts.contains(account)) {
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
        ledger.put("cleanupAccount", cleanupAccount);
        ledger.put("attemptedAccounts", attemptedAccounts); ledger.put("createdAccounts", createdAccounts);
        ledger.put("createdTables", createdTables); ledger.put("ownedUsers", ownedUsers);
        ledger.put("agingAttempted", agingAttempted);
        ledger.put("cleanupStarted", cleaned); ledger.put("cleanupComplete", cleanupComplete);
        documentWriter.write(ledgerPath, ledger);
    }
    private void save() {
        result.put("schemaVerified",schemaVerified);result.put("verifyVerified",verifyVerified);result.put("dryRunVerified",dryRunVerified);result.put("applyVerified",applyVerified);result.put("privilegesVerified",privilegesVerified);
        result.put("cleanupComplete", cleanupComplete); result.put("connectionAttempts", budget.connections); result.put("workCommands", budget.work); result.put("cleanupCommands", budget.cleanup);
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
    private static void bind(PreparedStatement p, Object... args) throws SQLException { for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]); }
    private void pass(String code) { checks.put(code, true); }
    private static void require(boolean condition, String code) { if (!condition) throw rejected(code); }
    private static CleanupSqlBudget.Rejected rejected(String code) { return new CleanupSqlBudget.Rejected(code); }
    static Map<String, Object> safeSqlFailure(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable item = failure; item != null && seen.size() < 16 && seen.add(item); item = item.getCause()) {
            if (item instanceof SQLException sql) return Map.of("failureVendor", sql.getErrorCode(), "failureSqlState", CleanupSqlBudget.safeState(sql.getSQLState()));
        }
        return Map.of();
    }
    private static String fixedFailure(Exception e) {
        if (e instanceof CleanupSqlBudget.Rejected) return e.getMessage();
        if (e instanceof CleanupFailure known) return "CLEANUP_" + known.code().name();
        if (e instanceof DemoAdmissionException known) return known.code().name();
        if (e instanceof SQLException) return "SQL_EXCEPTION";
        return "UNEXPECTED_FAILURE";
    }
}
