package com.potg.verification.managed;

import static com.potg.verification.managed.ManagedSqlContracts.require;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potg.don.DonApplication;
import com.potg.don.analysisJob.scheduler.AnalysisJobScheduler;
import com.potg.don.auth.demo.DemoAuthException;
import com.potg.don.auth.demo.DemoRefreshCookie;
import com.potg.don.auth.demo.DemoSessionService;
import com.potg.don.auth.demo.DemoSessionStore;
import com.potg.don.auth.jwt.JwtAuthenticationFilter;
import com.potg.don.auth.jwt.JwtUtil;
import com.potg.don.demo.seed.DemoSeedScenario;
import com.potg.don.transaction.client.CsvClient;

import io.lettuce.core.event.command.CommandListener;
import io.lettuce.core.event.command.CommandStartedEvent;
import io.lettuce.core.resource.DefaultClientResources;
import jakarta.servlet.http.Cookie;
import reactor.core.publisher.Mono;

/**
 * A finite opt-in probe, never a JUnit-discovered remote test. The Python launcher
 * owns the credential-file preflight and global invocation budget. All values and
 * recovery identity live only in 0600 private files. Normal logging is discarded
 * before any connection is created; public output contains fixed checks only.
 * Product HTTP/security/SQL/Lua are real. Only external WebClient is fail-closed.
 */
public final class ManagedProviderProbe {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Set<String> INPUTS = Set.of("TIDB_HOST", "TIDB_PORT", "TIDB_SETUP_USERNAME", "TIDB_SETUP_PASSWORD",
        "REDIS_HOST", "REDIS_PORT", "REDIS_USERNAME", "REDIS_PASSWORD", "REDIS_SSL_ENABLED",
        "mode", "schema", "ddlPath", "appPort", "deadlineEpochMillis");
    static final Set<PosixFilePermission> PRIVATE_FILE = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    static final String ORIGIN = "https://managed-check.example.invalid";
    private final Map<String, String> config;
    private final Path resultPath;
    private final Path ledgerPath;
    private final Path budgetPath;
    private final int verificationBudgetLimit;
    private final Set<String> ownedKeys = new LinkedHashSet<>();
    private final List<Map<String, Object>> checks = new ArrayList<>();
    private final Map<String, Object> summary = new LinkedHashMap<>();
    private final Map<String, Object> timings = new LinkedHashMap<>();
    private final List<Integer> loginHttpStatuses = new ArrayList<>();
    private int observedLoginSuccesses;
    private final AtomicInteger externalAttempts = new AtomicInteger();
    private final AtomicInteger wireCommands = new AtomicInteger();
    private final AtomicInteger noScriptFailures = new AtomicInteger();
    private final ThreadLocal<String> ownershipExistenceRead = new ThreadLocal<>();
    private final ManagedRedisCommandGate redisCommandGate;
    private final Set<String> wireCommandKinds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private List<List<String>> schemaBeforeStart;
    private final AtomicBoolean injectUnavailable = new AtomicBoolean();
    private final AtomicBoolean loseRotationResult = new AtomicBoolean();
    private final AtomicInteger rotations = new AtomicInteger();
    private final AtomicInteger confirmedSessionCreates = new AtomicInteger();
    private final List<LettuceConnectionFactory> auxiliaryFactories = new ArrayList<>();
    private final long deadline;
    private final String schema;
    private final String setupUrl;
    private final String runtimeUrl;
    private ConfigurableApplicationContext context;
    private DefaultClientResources resources;
    private MockMvc mvc;
    private JdbcTemplate jdbc;
    private StringRedisTemplate redis;
    private JwtUtil jwt;
    private boolean schemaCreated;
    private boolean schemaCreateAttempted;
    private boolean accountCreated;
    private boolean accountCreateAttempted;
    private boolean cleanupStarted;
    private String account;
    private String runtimeUsername;
    private String runtimePassword;
    private String phase = "INPUT_VALIDATION";

    private ManagedProviderProbe(Map<String, String> config, Path resultPath, Path ledgerPath, Path budgetPath) throws Exception {
        validateInputs(config);
        this.config = config;
        this.redisCommandGate = new ManagedRedisCommandGate(this::permittedRedisKey);
        this.resultPath = resultPath;
        this.ledgerPath = ledgerPath;
        this.budgetPath = budgetPath;
        this.verificationBudgetLimit = verificationBudgetLimit(config);
        requireApprovedStartingBudget(verificationBudgetLimit, JSON.readTree(Files.readAllBytes(budgetPath)));
        this.schema = config.get("schema");
        this.deadline = Long.parseLong(config.get("deadlineEpochMillis"));
        this.setupUrl = "jdbc:mysql://" + config.get("TIDB_HOST") + ":" + config.get("TIDB_PORT") + "/?sslMode=VERIFY_IDENTITY";
        this.runtimeUrl = "jdbc:mysql://" + config.get("TIDB_HOST") + ":" + config.get("TIDB_PORT") + "/" + schema + "?sslMode=VERIFY_IDENTITY";
        this.runtimeUsername = config.get("TIDB_SETUP_USERNAME");
        this.runtimePassword = config.get("TIDB_SETUP_PASSWORD");
        summary.put("status", "BLOCKED");
        summary.put("mode", config.get("mode"));
        summary.put("checks", checks);
        summary.put("timings_millis", timings);
        summary.put("login_http_statuses",loginHttpStatuses);
        summary.put("observed_login_successes",0);
        summary.put("tidb_basic_contracts_verified",false);
        summary.put("tidb_contracts_verified",false);
        summary.put("login_recovery_verified",false);
        summary.put("login_seed_api_verified",false);
        summary.put("upstash_functional_contracts_verified",false);
        summary.put("failover_revocation_guarantee", "NOT_ESTABLISHED");
        summary.put("public_deployment_ready", false);
        writeLedger();
    }

    public static void main(String[] args) {
        // Raw JDBC, Hibernate and provider exceptions can contain values. They never
        // enter console, raw evidence, Gradle XML or a managed runner report.
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        ManagedProviderProbe probe = null;
        int exit = 1;
        try {
            require(args.length == 0, "NO_ARGUMENTS");
            Path input = privateFile(System.getenv("MANAGED_CHECK_CONFIG"));
            Path result = privateFile(System.getenv("MANAGED_CHECK_RESULT"));
            Path ledger = privateFile(System.getenv("MANAGED_CHECK_LEDGER"));
            Path budget = privateFile(System.getenv("MANAGED_CHECK_BUDGET"));
            JsonNode document = JSON.readTree(Files.readAllBytes(input));
            require(document.isObject(), "INPUT_OBJECT");
            Map<String, String> config = new LinkedHashMap<>();
            document.fields().forEachRemaining(entry -> config.put(entry.getKey(), entry.getValue().asText()));
            probe = new ManagedProviderProbe(config, result, ledger, budget);
            ManagedProviderProbe running = probe;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                synchronized (running) {
                    if (!running.cleanupStarted) {
                        running.summary.put("status", "INTERRUPTED");
                        running.cleanup();
                        running.saveResult();
                    }
                }
            }, "managed-probe-owned-cleanup"));
            probe.run();
            probe.summary.put("status", "PASS");
            exit = 0;
        } catch (Exception failure) {
            if (probe != null) {
                probe.summary.put("status", "FAIL");
                // Only our fixed reasons are public. Never use arbitrary exception messages.
                probe.summary.put("failed_phase", probe.phase);
                if (failure instanceof ManagedSqlContracts.ContractFailure) probe.summary.put("contract", failure.getMessage());
                else probe.summary.put("failure_category", classify(failure));
            }
        } finally {
            if (probe != null) {
                probe.cleanup();
                if (!Boolean.TRUE.equals(probe.summary.get("cleanup_complete"))) {
                    probe.summary.put("status", "FAIL");
                    exit = 1;
                }
                probe.saveResult();
            }
        }
        System.exit(exit);
    }

    static void validateInputs(Map<String, String> input) {
        var requiredKeys = new java.util.HashSet<>(input.keySet());
        requiredKeys.remove("verificationBudgetLimit");
        require(requiredKeys.equals(INPUTS), "INPUT_ALLOWLIST");
        verificationBudgetLimit(input);
        require(input.values().stream().allMatch(value -> value != null && !value.isBlank()), "INPUT_REQUIRED");
        boolean local = "local-rehearsal".equals(input.get("mode"));
        require(local || "managed".equals(input.get("mode")), "MODE");
        for (String key : List.of("TIDB_HOST", "REDIS_HOST")) {
            String host = input.get(key);
            require(host.matches("[a-zA-Z0-9](?:[a-zA-Z0-9.-]{0,251}[a-zA-Z0-9])?"), "HOST_SYNTAX");
            boolean loopback = host.equals("127.0.0.1") || host.equals("localhost");
            require(local == loopback && !host.contains(".."), "HOST_MODE_BOUNDARY");
            if (!local) require(!host.matches("[0-9.]+"), "MANAGED_DNS_IDENTITY_REQUIRED");
        }
        for (String key : List.of("TIDB_PORT", "REDIS_PORT", "appPort")) {
            require(input.get(key).matches("[1-9][0-9]{0,4}"), "PORT_FORMAT");
            int port = Integer.parseInt(input.get(key));
            require(port <= 65535, "PORT_RANGE");
        }
        require(input.get("schema").matches("moneytoad_contract_[a-f0-9]{16}"), "OWNED_SCHEMA_FORMAT");
        require(input.get("REDIS_SSL_ENABLED").equals("true"), "TLS_REQUIRED");
        require(input.get("deadlineEpochMillis").matches("[1-9][0-9]{12}"), "DEADLINE_FORMAT");
        long remaining = Long.parseLong(input.get("deadlineEpochMillis")) - System.currentTimeMillis();
        require(remaining > 0 && remaining <= Duration.ofMinutes(30).toMillis(), "FINITE_DEADLINE");
    }

    /** Only this explicit approval extends the existing cumulative ledger; never reset or pre-add it. */
    static int verificationBudgetLimit(Map<String,String> input) {
        String value = input.getOrDefault("verificationBudgetLimit", "10000");
        require(value.equals("10000") || value.equals("15568"), "APPROVED_BUDGET_LIMIT");
        if (value.equals("15568")) require("managed".equals(input.get("mode")), "EXTENDED_BUDGET_MANAGED_ONLY");
        return Integer.parseInt(value);
    }
    static int[] budgetCounts(JsonNode previous) {
        require(previous != null && previous.isObject() && previous.size()==2
            && previous.has("loginAttempts") && previous.has("commandEquivalents")
            && previous.get("loginAttempts").isIntegralNumber() && previous.get("loginAttempts").canConvertToInt()
            && previous.get("commandEquivalents").isIntegralNumber() && previous.get("commandEquivalents").canConvertToInt(), "BUDGET_FORMAT");
        int logins=previous.get("loginAttempts").intValue(), used=previous.get("commandEquivalents").intValue();
        require(logins>=0 && logins<=8 && used>=0, "BUDGET_FORMAT");
        return new int[]{logins,used};
    }
    static void requireApprovedStartingBudget(int limit, JsonNode previous) {
        int[] counts=budgetCounts(previous);
        if (limit==15568) require(counts[0]==1 && counts[1]==9472, "APPROVED_RECOVERY_BASELINE");
        else require(limit==10000 && counts[1]<=limit, "GLOBAL_BUDGET_LIMIT");
    }
    static void requireReservation(int limit, int logins, int used, int commands, boolean login) {
        require((limit==10000 || limit==15568) && commands>0 && logins>=0 && used>=0
            && logins+(login?1:0)<=8 && (long)used+commands<=limit, "GLOBAL_BUDGET_LIMIT");
    }

    static Path privateFile(String raw) throws Exception {
        require(raw != null, "PRIVATE_INPUT_REQUIRED");
        Path path = Path.of(raw).toAbsolutePath().normalize();
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
            && Files.getPosixFilePermissions(path).equals(PRIVATE_FILE) && Files.size(path) <= 131072, "PRIVATE_FILE_MODE");
        require(((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue() == 1, "NO_HARDLINK");
        require(Files.getOwner(path).equals(Files.getOwner(Path.of(System.getProperty("user.home")))), "PRIVATE_FILE_OWNER");
        require(Files.getPosixFilePermissions(path.getParent()).equals(Set.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)), "PRIVATE_DIRECTORY_MODE");
        for (Path ancestor = path.getParent(); ancestor != null; ancestor = ancestor.getParent()) require(!Files.isSymbolicLink(ancestor), "NO_SYMLINK");
        return path;
    }

    private void run() throws Exception {
        phase = "CLEANUP_BUDGET_RESERVATION";
        reserve(2000, false);
        summary.put("cleanup_command_equivalents_reserved", 2000);
        phase = "REVIEWED_DDL";
        List<String> ddl = ManagedSqlContracts.reviewedStatements(Path.of(config.get("ddlPath")));
        phase = "SETUP_TLS_AND_SCHEMA";
        reserve(256, false);
        try (Connection setup = setupConnection()) {
            require(tls(setup), "SETUP_JDBC_TLS");
            String version = ManagedSqlContracts.scalar(setup, "SELECT VERSION()");
            summary.put("database_product", version.toLowerCase(java.util.Locale.ROOT).contains("tidb") ? "TiDB" : "MySQL");
            // Server version may contain a provider build name; permit a narrow printable version only.
            if (version.matches("[a-zA-Z0-9._+ -]{1,100}")) summary.put("database_version", version);
            summary.put("jdbc_driver_version", setup.getMetaData().getDriverVersion());
            if (config.get("mode").equals("managed")) require(version.toLowerCase(java.util.Locale.ROOT).contains("tidb"), "ACTUAL_TIDB_REQUIRED");
            try (var query = setup.prepareStatement("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
                query.setString(1, schema);
                try (var rows = query.executeQuery()) { require(rows.next() && rows.getInt(1) == 0, "SCHEMA_COLLISION_REFUSED"); }
            }
            schemaCreateAttempted = true;
            writeLedger();
            try (Statement statement = setup.createStatement()) {
                statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
            schemaCreated = true;
            writeLedger();
            try (Statement statement = setup.createStatement()) { statement.execute("USE `" + schema + "`"); }
            require(schema.equals(ManagedSqlContracts.scalar(setup, "SELECT DATABASE()")), "OWNED_SCHEMA_SELECTED");
            for (String statement : ddl) {
                checkDeadline();
                try (Statement command = setup.createStatement()) { command.execute(statement); }
            }
            require(ManagedSqlContracts.tableNames(setup).equals(ManagedSqlContracts.TABLES), "EXACT_SEVEN_TABLES");
            ManagedSqlContracts.assertEmpty(setup);
            schemaBeforeStart = ManagedSqlContracts.schemaSnapshot(setup);
            createRuntimeAccount(setup);
        }
        pass("explicit_owned_schema_tls_and_reviewed_ddl");
        phase = "PRODUCT_CONFIGDATA_VALIDATE";
        startApplication();
        var environment = context.getEnvironment();
        require("validate".equals(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
            && "never".equals(environment.getProperty("spring.sql.init.mode")), "VALIDATE_ONLY");
        for (String source : List.of("application.yml", "application-demo.yml", "application-render.yml"))
            require(environment.getPropertySources().stream().anyMatch(p -> p.getName().contains(source)), "ACTUAL_CONFIGDATA");
        require(context.getBeansOfType(AnalysisJobScheduler.class).isEmpty(), "DEMO_NO_ANALYSIS_SCHEDULER");
        var chains = context.getBeansOfType(SecurityFilterChain.class).values();
        require(chains.size() == 1 && chains.iterator().next().getFilters().contains(context.getBean(JwtAuthenticationFilter.class)), "REAL_JWT_CHAIN");
        try (Connection connection = context.getBean(DataSource.class).getConnection()) {
            require(tls(connection), "APPLICATION_JDBC_TLS");
            require("Asia/Seoul".equals(ManagedSqlContracts.scalar(connection, "SELECT @@session.time_zone")), "ACTUAL_SESSION_TIMEZONE");
            require("1".equals(ManagedSqlContracts.scalar(connection, "SELECT @@foreign_key_checks")), "FOREIGN_KEYS_ENABLED");
            var before = ManagedSqlContracts.schemaSnapshot(connection);
            require(schemaBeforeStart.equals(before), "VALIDATE_STARTUP_SCHEMA_UNCHANGED");
            phase = "JDBC_CONSTRAINTS_AND_ROLLBACK";
            ManagedSqlContracts.probeWithRollback(connection);
            ManagedSqlContracts.assertEmpty(connection);
            require(before.equals(ManagedSqlContracts.schemaSnapshot(connection)), "VALIDATE_AND_PROBES_NO_SCHEMA_CHANGE");
        }
        pass("actual_jdbc_timezone_dates_unicode_collation_constraints_identity_enum_bit_sum_rollback");
        summary.put("tidb_basic_contracts_verified",config.get("mode").equals("managed"));
        phase = "TWO_VISITOR_HTTP_SEED_AND_CHART";
        Visitor first = login("first_login");
        Visitor second = login("subsequent_login");
        seedCounts(first);
        seedCounts(second);
        List<List<Map<String, Object>>> otherBefore = ownedRows(second.user);
        JsonNode annual = read(first, "/transactions");
        require(annual.isArray() && annual.size() == 12 && sum(annual, "totalAmount") == 9_990_000, "ANNUAL_SEED_TOTAL");
        YearMonth anchor = YearMonth.parse(annual.get(11).path("date").asText());
        require(annual.get(11).path("totalAmount").asInt() == 908_000 && annual.get(11).path("leaked").asBoolean(), "ANCHOR_INITIAL_LEAK");
        String monthly = "/transactions/" + anchor.getYear() + "/" + anchor.getMonthValue();
        JsonNode transactions = read(first, monthly);
        require(transactions.size() == 20 && sum(transactions, "amount") == 908_000, "MONTHLY_SEED_TOTAL");
        long practice = 0;
        for (JsonNode row : transactions) if (DemoSeedScenario.PRACTICE_MERCHANT.equals(row.path("merchantName").asText())) practice = row.path("id").asLong();
        require(practice > 0, "PRACTICE_TRANSACTION");
        require(sum(read(first, monthly + "/categories"), "leakedAmount") == 18_000, "INITIAL_LEAK_AMOUNT");
        MvcResult changed = mvc.perform(patch("/api/transactions/" + practice + "/category").contextPath("/api")
            .header("Authorization", "Bearer " + first.access).contentType("application/json").content("{\"category\":\"마트 / 편의점\"}")).andReturn();
        require(changed.getResponse().getStatus() == 200, "REAL_CATEGORY_PATCH");
        JsonNode categories = read(first, monthly + "/categories");
        require(sum(categories, "totalAmount") == 908_000 && sum(categories, "leakedAmount") == 0, "PATCH_REAGGREGATION");
        require(!read(first, "/transactions").get(11).path("leaked").asBoolean(), "ANNUAL_LEAK_REMOVED");
        require(otherBefore.equals(ownedRows(second.user)), "OTHER_VISITOR_EVERY_COLUMN_UNCHANGED");
        require("마트 / 편의점".equals(jdbc.queryForObject("SELECT category FROM transactions WHERE id=?", String.class, practice)), "ACTUAL_PERSISTED_CATEGORY");
        pass("two_visitors_actual_login_seed_queries_patch_aggregation_isolation");
        summary.put("login_seed_api_verified",config.get("mode").equals("managed"));
        summary.put("tidb_contracts_verified",config.get("mode").equals("managed"));
        summary.put("seed_counts_per_login", Map.of("users", 1, "cards", 1, "transactions", 240, "budgets", 72, "analysis_jobs", 0));
        summary.put("chart_totals", Map.of("annual", 9990000, "monthly_before", 908000, "monthly_after", 908000, "leak_before", 18000, "leak_after", 0));
        phase = "UPSTASH_ROTATION_RECONNECTION_AND_REVOKE";
        verifySession(first);
        Visitor rotated = refresh(first, "first_refresh");
        require(rotated.deadline.equals(first.deadline) && !rotated.refresh.equals(first.refresh), "ABSOLUTE_EXPIRY_UNCHANGED");
        verifySession(rotated);
        reconnectReadAndRotate(rotated);
        require(statusGet(first, "/users") == 401, "RECONNECT_REVOKE_OLD_ACCESS_401");
        // Real old RT reuse policy on visitor B.
        Visitor secondRotated = refresh(second, "subsequent_refresh");
        require(mvc.perform(demoPost("reissue").cookie(new Cookie(DemoRefreshCookie.NAME, second.refresh))).andReturn().getResponse().getStatus() == 401,
            "OLD_REFRESH_REUSE_401");
        require(!hasKey(second.sid) && statusGet(secondRotated, "/users") == 401, "REUSE_REVOKED_NEW_ACCESS");
        pass("real_lua_rotation_absolute_expiry_reconnection_revocation_reuse");
        phase = "CONCURRENT_REFRESH";
        Visitor concurrent = login("concurrent_fixture_login");
        try (var pool = Executors.newFixedThreadPool(2)) {
            CyclicBarrier barrier = new CyclicBarrier(2);
            java.util.concurrent.Callable<MvcResult> request = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return mvc.perform(demoPost("reissue").cookie(new Cookie(DemoRefreshCookie.NAME, concurrent.refresh))).andReturn();
            };
            var left = pool.submit(request); var right = pool.submit(request);
            MvcResult a = left.get(30, TimeUnit.SECONDS); MvcResult b = right.get(30, TimeUnit.SECONDS);
            long successes = List.of(a, b).stream().filter(r -> r.getResponse().getStatus() == 200).count();
            long rejected = List.of(a, b).stream().filter(r -> r.getResponse().getStatus() == 401).count();
            require(successes == 1 && rejected == 1 && !hasKey(concurrent.sid), "ATOMIC_CONCURRENT_REUSE");
            MvcResult winner = a.getResponse().getStatus() == 200 ? a : b;
            Visitor winnerTokens = visitor(winner);
            require(statusGet(winnerTokens, "/users") == 401, "CONCURRENT_WINNER_ACCESS_REVOKED");
        }
        pass("same_refresh_concurrency_one_success_final_session_absent");
        phase = "LOCAL_FAILURE_INJECTION_WITH_REAL_REDIS";
        Visitor ambiguous = login("ambiguous_result_fixture_login");
        int beforeRotations = rotations.get();
        loseRotationResult.set(true);
        require(mvc.perform(demoPost("reissue").cookie(new Cookie(DemoRefreshCookie.NAME, ambiguous.refresh))).andReturn().getResponse().getStatus() == 503,
            "LOCAL_CAS_RESULT_LOSS_503");
        require(rotations.get() == beforeRotations + 1, "NO_CAS_RETRY");
        // Actual provider rotation has already occurred; no candidate tokens were returned.
        var statistics = context.getBean(jakarta.persistence.EntityManagerFactory.class).unwrap(org.hibernate.SessionFactory.class).getStatistics();
        long statementsBeforeUnavailable = statistics.getPrepareStatementCount();
        injectUnavailable.set(true);
        require(statusGet(ambiguous, "/users") == 503, "LOCAL_UNAVAILABLE_GUARD_503");
        require(statistics.getPrepareStatementCount() == statementsBeforeUnavailable, "UNAVAILABLE_BUSINESS_SQL_ZERO");
        injectUnavailable.set(false);
        context.getBean(DemoSessionStore.class).revoke(ambiguous.sid);
        require(statusGet(ambiguous, "/users") == 401, "REVOKED_OLD_ACCESS_401");
        summary.put("failure_injection_scope", "local_store_result_boundary_after_actual_redis_command_not_provider_outage");
        pass("local_ambiguous_cas_no_retry_no_tokens_and_known_unavailable_503");
        phase = "ACTUAL_SHORT_EXPIRY";
        shortExpiry(first.user);
        require(externalAttempts.get() == 0, "EXTERNAL_APPLICATION_CALLS_ZERO");
        require(wireCommandKinds.containsAll(Set.of("EVAL", "EVALSHA")), "ACTUAL_SCRIPT_DISPATCH");
        summary.put("external_application_requests", externalAttempts.get());
        summary.put("wire_commands_observed", wireCommands.get());
        summary.put("wire_command_kinds", wireCommandKinds.stream().sorted().toList());
        summary.put("noscript_failures_observed", noScriptFailures.get());
        summary.put("noscript_fallback_observation", noScriptFailures.get() == 0 ? "NOT_OBSERVED" : "NOSCRIPT_OBSERVED");
        summary.put("automatic_redis_command_replay", false);
        summary.put("application_command_gate", "PRODUCT_LUA_AND_EXACT_OWNED_KEYS");
        require(observedLoginSuccesses==4,"EXACT_FOUR_SUCCESSFUL_LOGIN_RESPONSES");
        summary.put("provider_console_plan_region_quota", "NOT_INDEPENDENTLY_VERIFIED");
        summary.put("limited_runtime_measurement", "NOT_RUN_BY_THIS_PROBE");
        summary.put("tidb_contracts_verified", config.get("mode").equals("managed"));
        summary.put("upstash_functional_contracts_verified", config.get("mode").equals("managed"));
        summary.put("provider_selection", "HOLD");
    }

    static String runtimeAccountName(String mode, String setupUsername, String entropy) {
        require(entropy != null && entropy.matches("[a-f0-9]{24}"), "RUNTIME_ACCOUNT_ENTROPY");
        if ("local-rehearsal".equals(mode)) return "mtc_" + entropy;
        require("managed".equals(mode), "MODE");
        // Starter requires its console prefix in CREATE/GRANT/JDBC/DROP alike.
        // Keep the full name within TiDB's 32-character limit. This random name
        // is only a collision-resistant identifier; the password remains 256-bit.
        require(setupUsername != null && setupUsername.matches("[A-Za-z0-9]{1,16}\\.[A-Za-z0-9_]+"),
            "TIDB_STARTER_USERNAME_PREFIX");
        String prefix = setupUsername.substring(0, setupUsername.indexOf('.'));
        String fullAccount = prefix + ".m" + entropy.substring(0, 14);
        require(fullAccount.length() <= 32, "RUNTIME_ACCOUNT_LENGTH");
        return fullAccount;
    }

    private void createRuntimeAccount(Connection setup) throws Exception {
        account = runtimeAccountName(config.get("mode"), config.get("TIDB_SETUP_USERNAME"), randomHex(12));
        String candidatePassword = randomHex(32);
        accountCreateAttempted = true;
        writeLedger();
        try (Statement statement = setup.createStatement()) {
            statement.execute("CREATE USER '" + account + "'@'%' IDENTIFIED BY '" + candidatePassword + "'");
            accountCreated = true;
            writeLedger();
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON `" + schema + "`.* TO '" + account + "'@'%'");
            runtimeUsername = account;
            runtimePassword = candidatePassword;
            summary.put("separate_restricted_runtime_account", true);
        } catch (SQLException failure) {
            // Only confirmed permission denial is a recorded limitation. Ambiguous
            // CREATE/GRANT results cannot fall through into a supposedly safe run.
            if (!accountCreated && Set.of(1044, 1045, 1142, 1227).contains(failure.getErrorCode())) {
                accountCreateAttempted = false;
                summary.put("separate_restricted_runtime_account", false);
                summary.put("least_privilege_status", "BLOCKED_SETUP_PERMISSION");
                writeLedger();
                throw new ManagedSqlContracts.ContractFailure("RESTRICTED_RUNTIME_ACCOUNT_REQUIRED");
            } else throw failure;
        }
    }

    private void startApplication() throws Exception {
        checkDeadline();
        reserve(256, false);
        long start = System.nanoTime();
        resources = DefaultClientResources.builder().build();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("DB_URL", runtimeUrl); values.put("DB_USERNAME", runtimeUsername); values.put("DB_PASSWORD", runtimePassword);
        for (String key : List.of("REDIS_HOST", "REDIS_PORT", "REDIS_USERNAME", "REDIS_PASSWORD", "REDIS_SSL_ENABLED")) values.put(key, config.get(key));
        values.put("PORT", config.get("appPort"));
        values.put("APP_DEMO_ENABLED", "true"); values.put("APP_DEPLOYMENT_KIND", "public-demo"); values.put("APP_DEMO_BROWSER_ORIGIN", ORIGIN);
        values.put("JWT_SECRET", randomHex(32)); values.put("JWT_ACCESS_SECONDS", 300); values.put("JWT_REFRESH_SECONDS", 3600);
        values.put("JWT_ISSUER", "managed-provider-contract"); values.put("AI_BASE_URL", "http://127.0.0.1:1");
        values.put("logging.level.root", "OFF");
        values.put("spring.jpa.properties.hibernate.generate_statistics", true);
        StandardEnvironment environment = new StandardEnvironment() {
            @Override protected void customizePropertySources(MutablePropertySources sources) { }
        };
        environment.getPropertySources().addFirst(new MapPropertySource("bounded-provider-inputs", values));
        SpringApplication application = new SpringApplication(DonApplication.class, Observation.class);
        application.setEnvironment(environment);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        application.setApplicationContextFactory(type -> {
            var created = new GenericWebApplicationContext(); created.setServletContext(new MockServletContext()); return created;
        });
        application.setRegisterShutdownHook(false); application.setLogStartupInfo(false); application.setBannerMode(Banner.Mode.OFF);
        application.addInitializers(created -> created.getBeanFactory().registerSingleton("managedProbe", this));
        context = application.run("--spring.config.location=classpath:/application.yml", "--spring.profiles.active=demo,render");
        timings.put("application_context_start", elapsed(start));
        jdbc = new JdbcTemplate(context.getBean(DataSource.class)); redis = context.getBean(StringRedisTemplate.class); jwt = context.getBean(JwtUtil.class);
        mvc = MockMvcBuilders.webAppContextSetup((GenericWebApplicationContext) context).apply(springSecurity()).build();
        var factory = context.getBean(LettuceConnectionFactory.class);
        require(factory.getClientConfiguration().isUseSsl() && factory.getClientConfiguration().isVerifyPeer(), "ACTUAL_LETTUCE_VERIFIED_TLS");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Observation {
        @Bean static BeanPostProcessor managedStoreBoundary(ManagedProviderProbe probe) {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof LettuceConnectionFactory factory) {
                        factory.getNativeClient().addListener(probe.commandListener());
                        return bean;
                    }
                    if (!(bean instanceof DemoSessionStore) && !(bean instanceof CsvClient)) return bean;
                    ProxyFactory proxy = new ProxyFactory(bean); proxy.setProxyTargetClass(true);
                    proxy.addAdvice((MethodInterceptor) invocation -> {
                        String method = invocation.getMethod().getName();
                        if (Set.of("toString", "equals", "hashCode").contains(method)) return invocation.proceed();
                        if (bean instanceof CsvClient) {
                            probe.externalAttempts.incrementAndGet(); throw new IllegalStateException("EXTERNAL_APPLICATION_CALL_BLOCKED");
                        }
                        String sid = (String) invocation.getArguments()[0];
                        if (method.equals("create")) probe.recordBeforeCreate(sid); else probe.requireOwned(sid);
                        probe.reserve(64, false);
                        if (method.equals("findActive") && probe.injectUnavailable.get())
                            throw new DemoAuthException(DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE);
                        if (method.equals("rotate")) probe.rotations.incrementAndGet();
                        Object result = invocation.proceed();
                        if (method.equals("create") && Boolean.TRUE.equals(result)) probe.confirmedSessionCreates.incrementAndGet();
                        if (method.equals("create") && Boolean.FALSE.equals(result)) probe.releaseCollision(sid);
                        if (method.equals("rotate") && probe.loseRotationResult.compareAndSet(true, false))
                            throw new DemoAuthException(DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE);
                        return result;
                    });
                    return proxy.getProxy();
                }
            };
        }
        @Bean WebClientCustomizer managedBlockExternal(ManagedProviderProbe probe) {
            return builder -> builder.filter((request, next) -> {
                probe.externalAttempts.incrementAndGet(); return Mono.error(new IllegalStateException("EXTERNAL_APPLICATION_CALL_BLOCKED"));
            });
        }
        @Bean LettuceClientConfigurationBuilderCustomizer managedCommandObservation(ManagedProviderProbe probe) {
            return builder -> builder.clientResources(probe.resources)
                .clientOptions(ManagedRedisCommandGate.noReplayOptions());
        }
    }

    private CommandListener commandListener() {
        return new CommandListener() {
            @Override public void commandStarted(CommandStartedEvent event) {
                // Installed Lettuce invokes this before delegate.write, including batch writes.
                redisCommandGate.check(event.getCommand());
                // Names/arguments/results are never recorded. Reserved action budgets
                // include handshake/NOSCRIPT and up to eight internal calls per Lua.
                wireCommandKinds.add(event.getCommand().getType() instanceof io.lettuce.core.protocol.CommandType kind
                    ? kind.name() : "OTHER_COMMAND");
                int count = wireCommands.incrementAndGet();
                if (count > 875) throw new IllegalStateException("OBSERVED_COMMAND_BOUNDARY");
            }
            @Override public void commandFailed(io.lettuce.core.event.command.CommandFailedEvent event) {
                if (event.getCommand().getType() == io.lettuce.core.protocol.CommandType.EVALSHA
                    && event.getCause() instanceof io.lettuce.core.RedisNoScriptException) noScriptFailures.incrementAndGet();
            }
        };
    }

    private Visitor login(String timingName) throws Exception {
        reserve(64, true);
        long start = System.nanoTime();
        MvcResult result = mvc.perform(demoPost("login")).andReturn();
        timings.put(timingName, elapsed(start));
        int httpStatus=result.getResponse().getStatus(); loginHttpStatuses.add(httpStatus);
        require(httpStatus == 201, "ACTUAL_LOGIN_201");
        summary.put("observed_login_successes",++observedLoginSuccesses);
        Visitor visitor = visitor(result);
        requireOwned(visitor.sid);
        require(statusGet(visitor, "/auth/demo/session") == 200, "AUTHENTICATED_SESSION");
        summary.put("login_recovery_verified",config.get("mode").equals("managed"));
        return visitor;
    }
    private Visitor visitor(MvcResult result) throws Exception {
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        require(body.size() == 1 && body.has("accessToken"), "TOKEN_JSON_BOUNDARY");
        String access = body.path("accessToken").asText();
        String header = result.getResponse().getHeader("Set-Cookie");
        require(header != null && header.startsWith(DemoRefreshCookie.NAME + "=") && header.contains("HttpOnly")
            && header.contains("Secure") && header.contains("SameSite=Lax") && header.contains("Path=/api/auth/demo")
            && !header.toLowerCase(java.util.Locale.ROOT).contains("domain="), "PUBLIC_COOKIE_ATTRIBUTES");
        String refresh = header.substring(DemoRefreshCookie.NAME.length() + 1, header.indexOf(';'));
        var a = jwt.validateDemoAccessToken(jwt.parse(access), Instant.now());
        var r = jwt.validateDemoRefreshToken(jwt.parse(refresh), Instant.now());
        require(a.userId() == r.userId() && a.sid().equals(r.sid()), "TOKEN_IDENTITY");
        return new Visitor(access, refresh, a.sid(), a.userId(), r.expiresAt());
    }
    private Visitor refresh(Visitor previous, String timing) throws Exception {
        long start = System.nanoTime();
        MvcResult result = mvc.perform(demoPost("reissue").cookie(new Cookie(DemoRefreshCookie.NAME, previous.refresh))).andReturn();
        timings.put(timing, elapsed(start));
        require(result.getResponse().getStatus() == 200, "REFRESH_200");
        return visitor(result);
    }
    private JsonNode read(Visitor visitor, String path) throws Exception {
        long start = System.nanoTime();
        MvcResult response = mvc.perform(get("/api" + path).contextPath("/api").header("Authorization", "Bearer " + visitor.access)).andReturn();
        timings.putIfAbsent(path.equals("/transactions") ? "annual_query" : "subsequent_query", elapsed(start));
        require(response.getResponse().getStatus() == 200, "PROTECTED_QUERY_200");
        return JSON.readTree(response.getResponse().getContentAsString());
    }
    private int statusGet(Visitor visitor, String path) throws Exception {
        long start = System.nanoTime();
        int status = mvc.perform(get("/api" + path).contextPath("/api").header("Authorization", "Bearer " + visitor.access)).andReturn().getResponse().getStatus();
        if (path.equals("/auth/demo/session")) timings.putIfAbsent("session_query", elapsed(start));
        return status;
    }
    private static MockHttpServletRequestBuilder demoPost(String action) {
        return post("/api/auth/demo/" + action).contextPath("/api").contentType("application/json")
            .header("Origin", ORIGIN).header("X-MoneyToad-Demo", "1").content("{}");
    }
    private void seedCounts(Visitor visitor) {
        require(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=?", Long.class, visitor.user) == 1
            && jdbc.queryForObject("SELECT COUNT(*) FROM cards WHERE user_id=? AND card_no IS NULL AND cvc IS NULL", Long.class, visitor.user) == 1
            && jdbc.queryForObject("SELECT COUNT(*) FROM transactions t JOIN cards c ON c.id=t.card_id WHERE c.user_id=?", Long.class, visitor.user) == 240
            && jdbc.queryForObject("SELECT COUNT(*) FROM budgets WHERE user_id=?", Long.class, visitor.user) == 72
            && jdbc.queryForObject("SELECT COUNT(*) FROM analysis_job", Long.class) == 0
            && jdbc.queryForObject("SELECT COUNT(*) FROM peer_transaction_stats", Long.class) == 0
            && jdbc.queryForObject("SELECT COUNT(*) FROM dummy", Long.class) == 0, "EXACT_SEED_COUNTS_NULL_FINANCIALS_NO_EXTERNAL_RECORDS");
    }
    private List<List<Map<String, Object>>> ownedRows(long user) {
        return List.of(jdbc.queryForList("SELECT * FROM users WHERE id=? ORDER BY id", user),
            jdbc.queryForList("SELECT * FROM cards WHERE user_id=? ORDER BY id", user),
            jdbc.queryForList("SELECT t.* FROM transactions t JOIN cards c ON c.id=t.card_id WHERE c.user_id=? ORDER BY t.id", user),
            jdbc.queryForList("SELECT * FROM budgets WHERE user_id=? ORDER BY id", user));
    }
    private void verifySession(Visitor visitor) throws Exception {
        requireOwned(visitor.sid); reserve(64, false);
        // Exercise the exact product read script via EVAL as well as the normal
        // DefaultScriptExecutor EVALSHA path. No script cache is flushed.
        byte[] findScript = new org.springframework.core.io.ClassPathResource("redis/demo-session-find-active.lua").getContentAsByteArray();
        List<?> found = redis.execute((org.springframework.data.redis.core.RedisCallback<List<?>>) connection ->
            connection.eval(findScript, org.springframework.data.redis.connection.ReturnType.MULTI, 1,
                ("demo:session:" + visitor.sid).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        require(found != null && found.size() == 2, "DIRECT_EVAL_PRODUCT_FIND_RETURN_TYPE");
        var values = redis.opsForHash().entries("demo:session:" + visitor.sid);
        require(values.keySet().equals(Set.of("userId", "refreshHash", "expiresAt")), "THREE_HASH_FIELDS");
        require(!values.containsValue(visitor.refresh) && sha256(visitor.refresh).equals(values.get("refreshHash")), "REFRESH_HASH_ONLY");
        require(Long.toString(visitor.deadline.toEpochMilli()).equals(values.get("expiresAt")), "STORED_ABSOLUTE_EXPIRY");
        long ttl = redis.getExpire("demo:session:" + visitor.sid, TimeUnit.MILLISECONDS);
        require(ttl > 0 && ttl <= 3600000 && ttl <= Duration.between(Instant.now().minusSeconds(2), visitor.deadline).toMillis(), "FINITE_ABSOLUTE_TTL");
    }
    private void reconnectReadAndRotate(Visitor visitor) throws Exception {
        reserve(256, false);
        LettuceConnectionFactory separate = separateFactory();
        StringRedisTemplate other = new StringRedisTemplate(separate);
        DemoSessionStore otherStore = new DemoSessionStore(other);
        requireOwned(visitor.sid); reserve(64, false);
        require(otherStore.findActive(visitor.sid).isPresent(), "SEPARATE_CONNECTION_READ");
        separate.resetConnection();
        reserve(256, false);
        require(otherStore.findActive(visitor.sid).isPresent(), "EXPLICIT_RECONNECTION_READ");
        String next = randomHex(32);
        reserve(64, false);
        require(otherStore.rotate(visitor.sid, visitor.user, visitor.deadline, sha256(visitor.refresh), next) == DemoSessionStore.RotationResult.ROTATED,
            "EXPLICIT_RECONNECTION_ROTATION");
        reserve(64, false); otherStore.revoke(visitor.sid);
        require(!hasKey(visitor.sid), "EXPLICIT_RECONNECTION_REVOCATION");
        summary.put("reconnection_scope", "explicit_client_connection_reset_not_provider_failover");
    }
    private void shortExpiry(long user) throws Exception {
        String sid = Base64.getUrlEncoder().withoutPadding().encodeToString(new SecureRandom().generateSeed(32));
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant deadline = now.plusSeconds(300);
        String access = jwt.createDemoAccessToken(user, sid, now, deadline);
        DemoSessionStore store = context.getBean(DemoSessionStore.class);
        require(store.create(sid, user, randomHex(32), deadline), "SHORT_SESSION_CREATE");
        Visitor shortVisit = new Visitor(access, "unused", sid, user, deadline);
        require(statusGet(shortVisit, "/users") == 200, "SHORT_SESSION_INITIAL_ACCESS");
        requireOwned(sid); reserve(64, false);
        require(Boolean.TRUE.equals(redis.expire("demo:session:" + sid, Duration.ofMillis(1500))), "OWNED_KEY_SHORTENED_TTL");
        long waitUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1700);
        while (System.nanoTime() < waitUntil) { checkDeadline(); Thread.sleep(50); }
        require(jwt.validateDemoAccessToken(jwt.parse(access), Instant.now()) != null, "ACCESS_STILL_CRYPTOGRAPHICALLY_VALID");
        require(!hasKey(sid) && statusGet(shortVisit, "/users") == 401, "REAL_EXPIRY_OLD_ACCESS_401");
        pass("actual_short_ttl_expiry_and_access_rejection");
    }
    private LettuceConnectionFactory separateFactory() {
        var standalone = new RedisStandaloneConfiguration(config.get("REDIS_HOST"), Integer.parseInt(config.get("REDIS_PORT")));
        standalone.setUsername(config.get("REDIS_USERNAME")); standalone.setPassword(RedisPassword.of(config.get("REDIS_PASSWORD")));
        var client = LettuceClientConfiguration.builder().clientResources(resources)
            .clientOptions(ManagedRedisCommandGate.noReplayOptions()).useSsl().and()
            .commandTimeout(Duration.ofSeconds(2)).build();
        var factory = new LettuceConnectionFactory(standalone, client);
        factory.afterPropertiesSet(); factory.start(); factory.getNativeClient().addListener(commandListener());
        auxiliaryFactories.add(factory); return factory;
    }
    private boolean hasKey(String sid) throws Exception {
        requireOwned(sid); reserve(64, false); return Boolean.TRUE.equals(redis.hasKey("demo:session:" + sid));
    }
    private synchronized void recordBeforeCreate(String sid) throws Exception {
        require(JwtUtil.isDemoIdentifier(sid), "OWNED_IDENTIFIER_SHAPE");
        String key = "demo:session:" + sid;
        // An existence error does not establish ownership. Only a confirmed
        // absent key may enter the durable journal, still before the create.
        reserve(64, false);
        Boolean exists;
        ownershipExistenceRead.set(key);
        try { exists = redis.hasKey(key); } finally { ownershipExistenceRead.remove(); }
        require(Boolean.FALSE.equals(exists), "REDIS_KEY_COLLISION_OR_UNKNOWN_REFUSED");
        ownedKeys.add(key);
        writeLedger();
    }
    private synchronized boolean permittedRedisKey(io.lettuce.core.protocol.CommandType type, String key) {
        return ownedKeys.contains(key) || (type == io.lettuce.core.protocol.CommandType.EXISTS
            && key.equals(ownershipExistenceRead.get()));
    }
    private synchronized void releaseCollision(String sid) throws Exception {
        ownedKeys.remove("demo:session:" + sid); writeLedger();
    }
    private synchronized void requireOwned(String sid) { require(ownedKeys.contains("demo:session:" + sid), "UNOWNED_REDIS_ACCESS_REFUSED"); }
    private synchronized void reserve(int commands, boolean login) throws Exception {
        checkDeadline();
        require(wireCommands.get() <= 875, "WIRE_COMMAND_BOUNDARY");
        try (FileChannel channel = FileChannel.open(budgetPath, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            JsonNode previous = JSON.readTree(Files.readAllBytes(budgetPath));
            int[] counts=budgetCounts(previous);
            int logins=counts[0], used=counts[1];
            requireReservation(verificationBudgetLimit,logins,used,commands,login);
            byte[] next = JSON.writeValueAsBytes(Map.of("loginAttempts", logins + (login ? 1 : 0), "commandEquivalents", used + commands));
            channel.truncate(0); channel.position(0); channel.write(java.nio.ByteBuffer.wrap(next)); channel.force(true);
            summary.put("reserved_command_equivalents", used + commands);
            summary.put("reserved_login_attempts", logins + (login ? 1 : 0));
        }
    }
    private void checkDeadline() { require(!cleanupStarted && System.currentTimeMillis() < deadline, "DEADLINE_OR_STOP"); }
    private Connection setupConnection() throws SQLException {
        Properties properties = new Properties(); properties.setProperty("user", config.get("TIDB_SETUP_USERNAME"));
        properties.setProperty("password", config.get("TIDB_SETUP_PASSWORD")); properties.setProperty("connectTimeout", "5000"); properties.setProperty("socketTimeout", "10000");
        return DriverManager.getConnection(setupUrl, properties);
    }
    private synchronized void writeLedger() throws Exception {
        Map<String, Object> ledger = new LinkedHashMap<>();
        ledger.put("schema", schema); ledger.put("schema_created", schemaCreated); ledger.put("schema_create_attempted", schemaCreateAttempted);
        ledger.put("runtime_account", account); ledger.put("account_created", accountCreated); ledger.put("account_create_attempted", accountCreateAttempted);
        ledger.put("keys", new ArrayList<>(ownedKeys)); ledger.put("cleanup_started", cleanupStarted);
        ledger.put("cleanupComplete", Boolean.TRUE.equals(summary.get("cleanup_complete")));
        byte[] bytes = JSON.writeValueAsBytes(ledger);
        try (FileChannel channel = FileChannel.open(ledgerPath, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(java.nio.ByteBuffer.wrap(bytes)); channel.force(true);
        }
    }
    private synchronized void cleanup() {
        if (cleanupStarted) return;
        cleanupStarted = true;
        boolean keysClean = ownedKeys.isEmpty(), schemaClean = !schemaCreateAttempted, accountClean = !accountCreateAttempted;
        boolean clientsClean=true, resourcesClean=resources==null;
        try { writeLedger(); } catch (Exception failure) { summary.put("cleanup_ledger_write_failed", true); }
        // The exact private ledger is retained if any cleanup result is unknown.
        if (context != null) {
            try {
                if (redis != null) for (String key : ownedKeys) redis.delete(key);
            } catch (RuntimeException ignored) { }
            try { context.close(); } catch (RuntimeException failure) { summary.put("context_close_failed", true); }
            context = null;
        }
        for (var factory : auxiliaryFactories) try { factory.destroy(); } catch (RuntimeException failure) { clientsClean=false; }
        auxiliaryFactories.clear();
        if (!ownedKeys.isEmpty() && resources != null) {
            LettuceConnectionFactory cleaner = null;
            try {
                cleaner = separateFactory(); StringRedisTemplate cleanupRedis = new StringRedisTemplate(cleaner);
                keysClean = true;
                for (String key : ownedKeys) {
                    cleanupRedis.delete(key);
                    if (Boolean.TRUE.equals(cleanupRedis.hasKey(key))) keysClean = false;
                }
            } catch (RuntimeException failure) { keysClean = false; }
            finally {
                if (cleaner != null) {
                    try { cleaner.destroy(); } catch (RuntimeException failure) {clientsClean=false;}
                    auxiliaryFactories.remove(cleaner);
                }
            }
        }
        // A CREATE with lost response is never claimed owned or deleted by guessing.
        if (schemaCreated || accountCreated) {
            try (Connection connection = setupConnection(); Statement statement = connection.createStatement()) {
                if (schemaCreated) {
                    statement.execute("DROP DATABASE `" + schema + "`");
                    try (var query = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
                        query.setString(1, schema);
                        try (var rows = query.executeQuery()) { schemaClean = rows.next() && rows.getInt(1) == 0; }
                    }
                }
                if (accountCreated) {
                    statement.execute("DROP USER '" + account + "'@'%'");
                    accountClean=ownedAccountAbsent(connection,account);
                }
            } catch (SQLException failure) { summary.put("owned_sql_cleanup_incomplete", true); }
        }
        if (resources != null) try {
            resourcesClean=Boolean.TRUE.equals(resources.shutdown(0,3,TimeUnit.SECONDS).get(5,TimeUnit.SECONDS));
        } catch (Exception failure) {resourcesClean=false;}
        summary.put("owned_clients_closed",clientsClean && !Boolean.TRUE.equals(summary.get("context_close_failed")));
        summary.put("owned_client_resources_terminated",resourcesClean);
        summary.put("owned_schema_create_confirmed_count",schemaCreated?1:0);
        summary.put("owned_runtime_account_create_confirmed_count",accountCreated?1:0);
        summary.put("owned_session_key_scope_count",ownedKeys.size());
        summary.put("confirmed_session_create_count",confirmedSessionCreates.get());
        summary.put("owned_keys_removed_and_absence_confirmed", keysClean);
        summary.put("owned_schema_removed_and_absence_confirmed", schemaClean);
        summary.put("owned_account_removed", accountClean);
        summary.put("cleanup_complete", keysClean && schemaClean && accountClean && clientsClean && resourcesClean && !Boolean.TRUE.equals(summary.get("context_close_failed"))
            && !Boolean.TRUE.equals(summary.get("cleanup_ledger_write_failed")));
        summary.put("wire_commands_observed", wireCommands.get());
        try { writeLedger(); } catch (Exception failure) { summary.put("cleanup_complete", false); }
    }
    /** A single exact-account metadata query; no global account listing or credential attempt. */
    static boolean ownedAccountAbsent(Connection connection, String ownedAccount) throws SQLException {
        require(ownedAccount != null && ownedAccount.matches("[A-Za-z0-9_.]{1,32}"), "OWNED_ACCOUNT_FORMAT");
        try (Statement statement=connection.createStatement()) {
            try (var ignored=statement.executeQuery("SHOW GRANTS FOR '" + ownedAccount + "'@'%'") ) {
                return false;
            } catch (SQLException failure) {
                // MySQL/TiDB ER_NONEXISTING_GRANT confirms only this exact target is absent.
                if (failure.getErrorCode()==1141) return true;
                throw failure;
            }
        }
    }
    private void saveResult() {
        try { Files.write(resultPath, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(summary)); }
        catch (Exception failure) { /* Launcher requires a complete valid result; absence is failure. */ }
    }
    private void pass(String name) { checks.add(Map.of("check", name, "status", "PASS")); }
    private static boolean tls(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); var rows = statement.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
            return rows.next() && rows.getString(2) != null && !rows.getString(2).isBlank();
        }
    }
    private static int sum(JsonNode rows, String field) { int total = 0; for (JsonNode row : rows) total += row.path(field).asInt(); return total; }
    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private static String randomHex(int bytes) { byte[] values = new byte[bytes]; new SecureRandom().nextBytes(values); return HexFormat.of().formatHex(values); }
    private static String sha256(String value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
    private static String classify(Throwable error) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.startsWith("08")) return "JDBC_CONNECTION_OR_TLS";
                if (state != null && state.startsWith("28")) return "JDBC_AUTHENTICATION";
                return "JDBC_PROVIDER_CONTRACT";
            }
            if (cursor instanceof org.springframework.data.redis.RedisConnectionFailureException) return "REDIS_CONNECTION_OR_TLS";
            if (cursor instanceof org.springframework.dao.QueryTimeoutException) return "REDIS_TIMEOUT";
        }
        return "FIXTURE_OR_PRODUCT_CONTRACT";
    }
    private record Visitor(String access, String refresh, String sid, long user, Instant deadline) {
        @Override public String toString() { return "Visitor[redacted]"; }
    }
}
