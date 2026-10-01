package com.potg.verification.managed;

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

/** Reviewed seven-table DDL and rollback-only JDBC checks, for a fresh owned schema only. */
final class ManagedSqlContracts {
    private static final String REVIEWED_SCHEMA_SHA256 =
        "6f407517cea0879bf55cd4568a01128890b045d5e72565b855c546c8236c7c35";
    static final Set<String> TABLES = Set.of("users", "cards", "transactions", "budgets",
        "analysis_job", "peer_transaction_stats", "dummy");
    private static final LocalDateTime LEAP_TIME = LocalDateTime.of(2024, 2, 29, 23, 59, 59, 123456000);
    private static final LocalDate LEAP_DATE = LocalDate.of(2024, 2, 29);
    private ManagedSqlContracts() { }
    static List<String> reviewedStatements(Path supplied) throws Exception {
        Path path = supplied.toAbsolutePath().normalize();
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

    static void probeWithRollback(Connection connection) throws Exception {
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
            try (PreparedStatement statement = prepare(connection,
                "INSERT INTO analysis_job(status,created_at,updated_at) VALUES(?,?,?)", "QUEUED", LEAP_TIME, LEAP_TIME)) {
                require(statement.executeUpdate() == 1, "ENUM_INSERT");
            }
            require("QUEUED".equals(scalar(connection, "SELECT status FROM analysis_job")), "ENUM_ROUNDTRIP");
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT SUM(amount) FROM transactions")) {
                require(rows.next() && rows.getBigDecimal(1).intValueExact() == 30000, "AGGREGATE_NUMERIC_CONTRACT");
            }
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

    static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            require(result.next(), "SCALAR_ROW");
            return result.getString(1);
        }
    }

    static Set<String> tableNames(Connection connection) throws SQLException {
        Set<String> result = new java.util.HashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()")) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    static void assertEmpty(Connection connection) throws SQLException {
        for (String table : TABLES) {
            require("0".equals(scalar(connection, "SELECT COUNT(*) FROM " + table)), "ALL_TABLES_EMPTY");
        }
    }

    static List<List<String>> schemaSnapshot(Connection connection) throws SQLException {
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

    static void require(boolean condition, String code) {
        if (!condition) throw new ContractFailure(code);
    }
    static final class ContractFailure extends IllegalStateException {
        ContractFailure(String code) { super(code); }
    }
}
