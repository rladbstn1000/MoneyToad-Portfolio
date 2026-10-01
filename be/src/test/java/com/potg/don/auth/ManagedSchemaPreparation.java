package com.potg.don.auth;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.springframework.core.env.Profiles;

/**
 * Local-only explicit-DDL rehearsal, not a managed-provider verification.
 * Requires the existing RUNNER_RENDER_* loopback/TLS inputs plus
 * RUNNER_MANAGED_SCHEMA_DDL pointing to the reviewed SQL file in a private copy.
 * The caller creates and owns an EMPTY moneytoad_render_* schema and cleans it
 * in finally. This helper never creates/drops a database, loads cloud inputs,
 * imports data, or enables Hibernate schema creation. Probe rows are rolled back.
 * Fixed markers only: no connection values, SQL rows, or exception text is output.
 */
public final class ManagedSchemaPreparation {
    private static final String REVIEWED_SCHEMA_SHA256 =
        "6f407517cea0879bf55cd4568a01128890b045d5e72565b855c546c8236c7c35";
    private static final Set<String> TABLES = Set.of("users", "cards", "transactions", "budgets",
        "analysis_job", "peer_transaction_stats", "dummy");
    private static final String COLLATION = "utf8mb4_0900_ai_ci";
    private static final LocalDateTime LEAP_TIME = LocalDateTime.of(2024, 2, 29, 23, 59, 59, 123456000);
    private static final LocalDate LEAP_DATE = LocalDate.of(2024, 2, 29);
    private static String phase = "INPUTS";

    private ManagedSchemaPreparation() { }

    public static void main(String[] args) {
        try {
            require(args.length == 0, "ARGUMENTS");
            run();
            System.out.println("MANAGED_SCHEMA_LOCAL_PREPARATION_PASS");
        } catch (Exception failure) {
            // Exception chains can include credentials or fixture values. Never print them.
            String code = failure instanceof ContractFailure ? failure.getMessage() : phase;
            System.err.println("MANAGED_SCHEMA_LOCAL_PREPARATION_FAIL: " + code);
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        String url = required("RUNNER_RENDER_DB_URL");
        URI uri = URI.create(url.substring("jdbc:".length()));
        require(url.startsWith("jdbc:mysql://") && "mysql".equals(uri.getScheme())
            && Set.of("localhost", "127.0.0.1").contains(uri.getHost())
            && uri.getPort() > 0 && uri.getPort() <= 65535 && uri.getPort() != 3306
            && uri.getUserInfo() == null && uri.getFragment() == null
            && uri.getPath().matches("/moneytoad_render_[a-z0-9_]+")
            && Set.of("sslMode=VERIFY_IDENTITY",
                "sslMode=VERIFY_IDENTITY&connectionTimeZone=Asia/Seoul",
                "connectionTimeZone=Asia/Seoul&sslMode=VERIFY_IDENTITY").contains(uri.getRawQuery()),
            "OWNED_LOCAL_JDBC_REQUIRED");
        String schema = uri.getPath().substring(1);
        List<String> statements = reviewedStatements();
        Properties credentials = new Properties();
        credentials.setProperty("user", required("RUNNER_RENDER_DB_USERNAME"));
        credentials.setProperty("password", required("RUNNER_RENDER_DB_PASSWORD"));
        credentials.setProperty("connectTimeout", "5000");
        credentials.setProperty("socketTimeout", "10000");
        List<List<String>> before;
        phase = "EXPLICIT_DDL";
        try (Connection connection = DriverManager.getConnection(url, credentials)) {
            require(schema.equals(scalar(connection, "SELECT DATABASE()")), "SELECTED_SCHEMA");
            require("0".equals(scalar(connection,
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")), "EMPTY_SCHEMA");
            // The old local MySQL runner has no collation override. Do not silently
            // change its case/accent/trailing-space behavior to TiDB's default bin.
            require(COLLATION.equals(scalar(connection, "SELECT @@collation_database")), "LOCAL_BASELINE_COLLATION");
            for (String sql : statements) {
                try (Statement statement = connection.createStatement()) { statement.execute(sql); }
            }
            require(tableNames(connection).equals(TABLES), "SEVEN_MAPPED_TABLES");
            require("7".equals(scalar(connection,
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                    + "AND table_collation='utf8mb4_0900_ai_ci'")), "EXPLICIT_TABLE_COLLATION");
            assertEmpty(connection);
            for (String file : List.of("V001__demo_admission.sql", "V002__demo_admission_lock.sql")) {
                try (var source = ManagedSchemaPreparation.class.getResourceAsStream("/db/demo/" + file)) {
                    require(source != null, "ADDITIVE_DEMO_MIGRATION");
                    String migration = new String(source.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                        .replaceAll("(?m)^\\s*--.*$", "");
                    for (String sql : migration.split(";")) if (!sql.isBlank()) {
                        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
                    }
                }
            }
            before = schemaSnapshot(connection);
        }

        phase = "PRODUCT_CONFIGDATA_VALIDATE";
        try (RenderRuntimeTestSupport fixture = new RenderRuntimeTestSupport()) {
            var context = fixture.start(false);
            var environment = context.getEnvironment();
            require(environment.acceptsProfiles(Profiles.of("demo & render")), "PRODUCT_PROFILES");
            require("validate".equals(environment.getProperty("spring.jpa.hibernate.ddl-auto")), "VALIDATE_ONLY");
            require("never".equals(environment.getProperty("spring.sql.init.mode")), "SQL_INIT_NEVER");
            for (String file : List.of("application.yml", "application-demo.yml", "application-render.yml")) {
                require(environment.getPropertySources().stream().anyMatch(source -> source.getName().contains(file)),
                    "ACTUAL_CONFIGDATA");
            }
            try (Connection connection = context.getBean(DataSource.class).getConnection()) {
                require(before.equals(schemaSnapshot(connection)), "VALIDATE_SCHEMA_UNCHANGED");
                require("Asia/Seoul".equals(scalar(connection, "SELECT @@session.time_zone")), "APP_SESSION_TIMEZONE");
                require("1".equals(scalar(connection, "SELECT @@foreign_key_checks")), "FOREIGN_KEYS_ENABLED");
                phase = "APP_JDBC_CONSTRAINTS_AND_DATES";
                probeWithRollback(connection);
                assertEmpty(connection);
            }
            require(fixture.probe.csvCalls.get() == 0 && fixture.probe.webClientCalls.get() == 0
                && fixture.probe.httpCalls.get() == 0 && fixture.probe.repositoryPollCalls.get() == 0
                && fixture.probe.servicePollCalls.get() == 0, "EXTERNAL_AND_SCHEDULER_CALLS_ZERO");
        }
    }

    private static List<String> reviewedStatements() throws Exception {
        Path path = Path.of(required("RUNNER_MANAGED_SCHEMA_DDL")).toAbsolutePath().normalize();
        require(path.getFileName().toString().equals("managed-provider-schema.sql")
            && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            && !Files.isSymbolicLink(path) && Files.size(path) <= 32_768, "REVIEWED_DDL_FILE");
        for (Path ancestor = path.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            require(!Files.isSymbolicLink(ancestor), "DDL_SYMLINK");
        }
        byte[] content = Files.readAllBytes(path);
        require(REVIEWED_SCHEMA_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))),
            "REVIEWED_DDL_DIGEST");
        String sql = new String(content, java.nio.charset.StandardCharsets.UTF_8).replaceAll("(?m)^\\s*--.*$", "");
        List<String> statements = java.util.Arrays.stream(sql.split(";")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        Set<String> seen = new java.util.HashSet<>();
        Pattern create = Pattern.compile("^CREATE TABLE ([a-z_]+) \\(", Pattern.DOTALL);
        for (String statement : statements) {
            var matcher = create.matcher(statement);
            require(matcher.find() && TABLES.contains(matcher.group(1)) && seen.add(matcher.group(1)), "CREATE_TABLE_ONLY");
        }
        require(seen.equals(TABLES) && statements.size() == TABLES.size(), "DDL_TABLE_INVENTORY");
        return statements;
    }

    private static void probeWithRollback(Connection connection) throws Exception {
        require(connection.getAutoCommit(), "INITIAL_AUTOCOMMIT");
        connection.setAutoCommit(false);
        try {
            String syntheticEmail = "schema-fixture-a@example.invalid";
            String syntheticName = "\uac80\uc99d \ubc29\ubb38\uc790";
            long user = insert(connection, "INSERT INTO users(created_at,email,name) VALUES(?,?,?)",
                LEAP_TIME, syntheticEmail, syntheticName);
            rejectIntegrity(connection, "INSERT INTO users(created_at,email,name) VALUES(?,?,?)",
                LEAP_TIME, syntheticEmail, syntheticName);
            rejectIntegrity(connection, "INSERT INTO users(created_at,email,name) VALUES(?,?,?)",
                LEAP_TIME, syntheticEmail.toUpperCase(Locale.ROOT), syntheticName);
            long card = insert(connection, "INSERT INTO cards(created_at,user_id,card_no,cvc) VALUES(?,?,?,?)",
                LEAP_TIME, user, null, null);
            rejectIntegrity(connection, "INSERT INTO cards(created_at,user_id) VALUES(?,?)", LEAP_TIME, user);
            rejectIntegrity(connection, "INSERT INTO cards(created_at,user_id) VALUES(?,?)", LEAP_TIME, Long.MAX_VALUE);
            rejectIntegrity(connection, "INSERT INTO transactions(created_at,card_id) VALUES(?,?)", LEAP_TIME, Long.MAX_VALUE);
            rejectIntegrity(connection, "INSERT INTO budgets(user_id) VALUES(?)", Long.MAX_VALUE);
            long transaction = insert(connection,
                "INSERT INTO transactions(created_at,card_id,transaction_date_time,amount,merchant_name,category) VALUES(?,?,?,?,?,?)",
                LEAP_TIME, card, LEAP_TIME, 30000, syntheticName, "\uce74\ud398");
            long budget = insert(connection,
                "INSERT INTO budgets(user_id,budget_date,amount,category,is_overridden) VALUES(?,?,?,?,?)",
                user, LEAP_DATE, 30000, "\uce74\ud398", false);
            try (PreparedStatement statement = prepare(connection,
                "SELECT transaction_date_time,merchant_name FROM transactions WHERE id=?", transaction);
                 ResultSet result = statement.executeQuery()) {
                require(result.next() && LEAP_TIME.equals(result.getObject(1, LocalDateTime.class))
                    && syntheticName.equals(result.getString(2)), "DATE_TIME_UNICODE_ROUNDTRIP");
            }
            try (PreparedStatement statement = prepare(connection,
                "SELECT budget_date,is_overridden FROM budgets WHERE id=?", budget);
                 ResultSet result = statement.executeQuery()) {
                require(result.next() && LEAP_DATE.equals(result.getObject(1, LocalDate.class))
                    && !result.getBoolean(2) && !result.wasNull(), "DATE_BOOLEAN_ROUNDTRIP");
            }
            require("1".equals(scalar(connection, "SELECT COUNT(*) FROM cards WHERE card_no IS NULL AND cvc IS NULL")),
                "FINANCIAL_COLUMNS_NULL");
            require("0".equals(scalar(connection,
                "SELECT _utf8mb4'X' COLLATE utf8mb4_0900_ai_ci = _utf8mb4'X ' COLLATE utf8mb4_0900_ai_ci")),
                "COLLATION_NO_PAD");
            require("30000".equals(scalar(connection, "SELECT SUM(amount) FROM transactions")), "SUM_VALUE");
            assertDateBoundaries(connection, transaction, budget);
            // No peer, analysis or legacy dummy data is installed by this rehearsal.
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    private static void assertDateBoundaries(Connection connection, long transaction, long budget) throws Exception {
        for (LocalDateTime dateTime : List.of(
            LocalDateTime.of(2023, 12, 31, 23, 59, 59, 999999000),
            LocalDateTime.of(2024, 1, 1, 0, 0),
            LEAP_TIME,
            LocalDateTime.of(2024, 3, 1, 0, 0))) {
            try (PreparedStatement statement = prepare(connection,
                "UPDATE transactions SET transaction_date_time=? WHERE id=?", dateTime, transaction)) {
                require(statement.executeUpdate() == 1, "BOUNDARY_TRANSACTION_UPDATE");
            }
            try (PreparedStatement statement = prepare(connection,
                "UPDATE budgets SET budget_date=? WHERE id=?", dateTime.toLocalDate(), budget)) {
                require(statement.executeUpdate() == 1, "BOUNDARY_BUDGET_UPDATE");
            }
            try (PreparedStatement statement = prepare(connection,
                "SELECT transaction_date_time,YEAR(transaction_date_time),MONTH(transaction_date_time) "
                    + "FROM transactions WHERE id=?", transaction);
                 ResultSet result = statement.executeQuery()) {
                require(result.next() && dateTime.equals(result.getObject(1, LocalDateTime.class))
                    && dateTime.getYear() == result.getInt(2) && dateTime.getMonthValue() == result.getInt(3),
                    "YEAR_MONTH_DATE_TIME_BOUNDARY");
            }
            try (PreparedStatement statement = prepare(connection,
                "SELECT budget_date FROM budgets WHERE id=?", budget);
                 ResultSet result = statement.executeQuery()) {
                require(result.next() && dateTime.toLocalDate().equals(result.getObject(1, LocalDate.class)),
                    "DATE_BOUNDARY");
            }
        }
    }

    private static long insert(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, values);
            require(statement.executeUpdate() == 1, "ONE_INSERTED_ROW");
            try (ResultSet keys = statement.getGeneratedKeys()) {
                require(keys.next(), "IDENTITY_RETURNED");
                long id = keys.getLong(1);
                require(id > 0 && !keys.wasNull(), "POSITIVE_IDENTITY");
                return id;
            }
        }
    }

    private static void rejectIntegrity(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = prepare(connection, sql, values)) {
            try {
                statement.executeUpdate();
            } catch (SQLException failure) {
                require(failure.getSQLState() != null && failure.getSQLState().startsWith("23"), "INTEGRITY_ERROR_CLASS");
                return;
            }
        }
        throw new ContractFailure("CONSTRAINT_NOT_ENFORCED");
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... values) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try { bind(statement, values); return statement; }
        catch (SQLException failure) { statement.close(); throw failure; }
    }

    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            require(result.next(), "SCALAR_ROW");
            return result.getString(1);
        }
    }

    private static Set<String> tableNames(Connection connection) throws SQLException {
        Set<String> result = new java.util.HashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()")) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    private static void assertEmpty(Connection connection) throws SQLException {
        for (String table : TABLES) {
            require("0".equals(scalar(connection, "SELECT COUNT(*) FROM " + table)), "ALL_TABLES_EMPTY");
        }
    }

    private static List<List<String>> schemaSnapshot(Connection connection) throws SQLException {
        List<List<String>> result = new ArrayList<>();
        for (String sql : List.of(
            "SELECT table_name,column_name,column_type,is_nullable,column_default,extra,collation_name "
                + "FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY table_name,ordinal_position",
            "SELECT table_name,index_name,non_unique,seq_in_index,column_name "
                + "FROM information_schema.statistics WHERE table_schema=DATABASE() ORDER BY table_name,index_name,seq_in_index",
            "SELECT table_name,column_name,referenced_table_name,referenced_column_name "
                + "FROM information_schema.key_column_usage WHERE table_schema=DATABASE() "
                + "ORDER BY table_name,constraint_name,ordinal_position")) {
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
                while (rows.next()) {
                    List<String> values = new ArrayList<>();
                    for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) values.add(rows.getString(i));
                    result.add(values);
                }
            }
        }
        return result;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        require(value != null && !value.isBlank(), "REQUIRED_INPUT");
        return value;
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new ContractFailure(code);
    }

    private static final class ContractFailure extends IllegalStateException {
        ContractFailure(String code) { super(code); }
    }
}
