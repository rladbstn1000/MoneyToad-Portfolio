package com.potg.don.maintenance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.potg.don.demo.admission.DemoAdmissionSchema;
import com.potg.don.demo.seed.DemoDatasetValidator;
import com.potg.don.demo.seed.DemoSeedScenario;
import com.potg.don.maintenance.CleanupFailure.Code;
import com.potg.don.maintenance.CleanupOptions.Mode;

/** One connection, one bounded batch, no Redis and no application context. */
public final class DemoCleanupService {
    public record Result(Mode mode, int candidates, int deleted, long countBefore, long countAfter, int maximum) { }
    private record Capacity(long count, int maximum) { }
    private record Candidate(long user, LocalDateTime createdAt, String version, LocalDateTime expiresAt) {
        @Override public String toString() { return "Candidate[redacted]"; }
    }
    private record Dataset(long user, List<Long> cards, List<Long> transactions, List<Long> budgets) {
        @Override public String toString() { return "Dataset[redacted]"; }
    }

    public Result execute(Connection connection, Mode mode, int batchSize, int expectedMax) {
        if (mode == null || batchSize < 1 || batchSize > 100 || expectedMax < 1) throw failure(Code.INPUT_REJECTED);
        boolean transaction = false;
        boolean commitAttempted = false;
        try {
            if (!connection.getAutoCommit()) throw failure(Code.INPUT_REJECTED);
            connection.setTransactionIsolation(mode == Mode.APPLY ? Connection.TRANSACTION_READ_COMMITTED : Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(mode != Mode.APPLY);
            connection.setAutoCommit(false);
            transaction = true;
            if (mode != Mode.APPLY) connection = CleanupReadOnlyGuard.wrap(connection);
            try { DemoAdmissionSchema.verify(connection); }
            catch (SQLException error) { throw failure(Code.SCHEMA_REJECTED); }
            verifyRelations(connection);
            if (mode == Mode.APPLY) DemoAdmissionSchema.verifyTransactionMode(connection);
            if (mode == Mode.APPLY) lockAdmission(connection);
            Capacity before = integrity(connection, expectedMax);
            if (mode == Mode.VERIFY) {
                connection.rollback(); transaction = false;
                return new Result(mode, 0, 0, before.count(), before.count(), before.maximum());
            }
            LocalDateTime now = databaseNow(connection);
            List<Candidate> candidates = candidates(connection, now, batchSize, mode == Mode.APPLY);
            List<Dataset> validated = new ArrayList<>();
            for (Candidate candidate : candidates) validated.add(validate(connection, candidate, now, mode == Mode.APPLY));
            if (mode == Mode.DRY_RUN) {
                connection.rollback(); transaction = false;
                return new Result(mode, validated.size(), 0, before.count(), before.count(), before.maximum());
            }
            // No DELETE is issued until every candidate in the selected batch has passed.
            deleteExact(connection, "transactions", "id", validated.stream().flatMap(row -> row.transactions().stream()).toList());
            deleteExact(connection, "budgets", "id", validated.stream().flatMap(row -> row.budgets().stream()).toList());
            deleteExact(connection, "cards", "id", validated.stream().flatMap(row -> row.cards().stream()).toList());
            List<Long> users = validated.stream().map(Dataset::user).toList();
            deleteExact(connection, "demo_visit", "user_id", users);
            deleteExact(connection, "users", "id", users);
            // Marker COUNT is the only occupancy source; the singleton lock still serializes writers.
            Capacity after = integrity(connection, expectedMax);
            if (after.count() != before.count() - users.size()) throw failure(Code.INTEGRITY_REJECTED);
            commitAttempted = true;
            connection.commit(); transaction = false;
            return new Result(mode, candidates.size(), users.size(), before.count(), after.count(), after.maximum());
        } catch (SQLException | RuntimeException error) {
            boolean rollbackConfirmed = !transaction;
            if (transaction) {
                try { connection.rollback(); rollbackConfirmed = true; }
                catch (SQLException ignored) { /* Never equate cleanup intent with a confirmed rollback. */ }
            }
            if (commitAttempted) throw failure(Code.COMMIT_UNKNOWN);
            if (!rollbackConfirmed) throw failure(Code.ROLLBACK_UNKNOWN);
            if (error instanceof CleanupFailure known) throw known;
            if (error instanceof SQLException) throw failure(Code.SQL_FAILURE);
            throw failure(Code.UNEXPECTED_FAILURE);
        }
        // The command/test caller owns and closes this connection. Never recycle an unknown transaction.
    }

    private static void lockAdmission(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT id FROM demo_admission_lock WHERE id=1 FOR UPDATE NOWAIT"); var result = query(statement, true)) {
            if (!result.next() || result.getInt(1) != 1 || result.next()) throw failure(Code.INTEGRITY_REJECTED);
        }
    }

    private static Capacity integrity(Connection connection, int expectedMax) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT id,max_visitors,(SELECT COUNT(*) FROM demo_visit),"
            + "(SELECT COUNT(*) FROM demo_admission_lock),(SELECT MIN(id) FROM demo_admission_lock),"
            + "(SELECT MAX(id) FROM demo_admission_lock) FROM demo_capacity"); var rows = statement.executeQuery()) {
            if (!rows.next()) throw failure(Code.INTEGRITY_REJECTED);
            int id = rows.getInt(1);
            int maximum = rows.getInt(2);
            long count = rows.getLong(3);
            if (id != 1 || maximum < 1 || maximum != expectedMax || count < 0 || count > maximum
                || rows.getLong(4) != 1 || rows.getInt(5) != 1 || rows.getInt(6) != 1 || rows.next()) throw failure(Code.INTEGRITY_REJECTED);
            return new Capacity(count, maximum);
        }
    }

    private static LocalDateTime databaseNow(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT UTC_TIMESTAMP(6)"); var rows = statement.executeQuery()) {
            if (!rows.next()) throw failure(Code.SQL_FAILURE);
            return rows.getObject(1, LocalDateTime.class);
        }
    }

    private static List<Candidate> candidates(Connection connection, LocalDateTime now, int batch, boolean lock) throws SQLException {
        String sql = "SELECT user_id,created_at,scenario_version,session_expires_at FROM demo_visit WHERE created_at<=? AND session_expires_at<? ORDER BY created_at,user_id LIMIT ?" + lock(lock);
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, now.minusHours(24)); statement.setObject(2, now); statement.setInt(3, batch);
            try (var rows = query(statement, lock)) {
                List<Candidate> candidates = new ArrayList<>();
                while (rows.next()) candidates.add(new Candidate(rows.getLong(1), rows.getObject(2, LocalDateTime.class), rows.getString(3), rows.getObject(4, LocalDateTime.class)));
                return candidates;
            }
        }
    }

    private static Dataset validate(Connection connection, Candidate candidate, LocalDateTime now, boolean lock) throws SQLException {
        if (candidate.user() < 1 || !DemoSeedScenario.VERSION.equals(candidate.version())
            || candidate.createdAt() == null || candidate.expiresAt() == null
            || candidate.createdAt().isAfter(now.minusHours(24)) || !candidate.expiresAt().isBefore(now)) throw failure(Code.UNSAFE_BATCH);
        try (var statement = connection.prepareStatement("SELECT id,file_id FROM users WHERE id=?" + lock(lock))) {
            statement.setLong(1, candidate.user());
            try (var rows = query(statement, lock)) {
                if (!rows.next() || rows.getLong(1) != candidate.user() || rows.getString(2) != null || rows.next()) throw failure(Code.UNSAFE_BATCH);
            }
        }
        List<Long> cards = new ArrayList<>();
        List<DemoDatasetValidator.CardData> cardData = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT id,card_no,cvc FROM cards WHERE user_id=? ORDER BY id" + lock(lock))) {
            statement.setLong(1, candidate.user());
            try (var rows = query(statement, lock)) {
                while (rows.next()) { cards.add(rows.getLong(1)); cardData.add(new DemoDatasetValidator.CardData(rows.getString(2), rows.getString(3))); }
            }
        }
        if (cards.size() != 1) throw failure(Code.UNSAFE_BATCH);
        List<Long> transactions = new ArrayList<>();
        List<DemoDatasetValidator.TransactionData> transactionData = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT id,transaction_date_time,amount,merchant_name FROM transactions WHERE card_id=? ORDER BY id" + lock(lock))) {
            statement.setLong(1, cards.getFirst());
            try (var rows = query(statement, lock)) {
                while (rows.next()) {
                    transactions.add(rows.getLong(1));
                    transactionData.add(new DemoDatasetValidator.TransactionData(rows.getObject(2, LocalDateTime.class), rows.getObject(3, Integer.class), rows.getString(4)));
                }
            }
        }
        List<Long> budgets = new ArrayList<>();
        List<DemoDatasetValidator.BudgetData> budgetData = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT id,budget_date,category,initial_amount,initial_file_id,predicted_at FROM budgets WHERE user_id=? ORDER BY id" + lock(lock))) {
            statement.setLong(1, candidate.user());
            try (var rows = query(statement, lock)) {
                while (rows.next()) {
                    budgets.add(rows.getLong(1));
                    budgetData.add(new DemoDatasetValidator.BudgetData(rows.getObject(2, java.time.LocalDate.class), rows.getString(3), rows.getObject(4, Integer.class), rows.getString(5), rows.getObject(6, LocalDateTime.class)));
                }
            }
        }
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM analysis_job WHERE user_id=?")) {
            statement.setLong(1, candidate.user());
            try (var rows = statement.executeQuery()) { if (!rows.next() || rows.getLong(1) != 0) throw failure(Code.UNSAFE_BATCH); }
        }
        try { DemoDatasetValidator.validate(cardData, transactionData, budgetData); }
        catch (IllegalArgumentException error) { throw failure(Code.UNSAFE_BATCH); }
        return new Dataset(candidate.user(), List.copyOf(cards), List.copyOf(transactions), List.copyOf(budgets));
    }

    private static void deleteExact(Connection connection, String table, String column, List<Long> ids) throws SQLException {
        if (new HashSet<>(ids).size() != ids.size() || ids.stream().anyMatch(id -> id < 1)) throw failure(Code.UNSAFE_BATCH);
        for (int start = 0; start < ids.size(); start += 500) {
            List<Long> chunk = ids.subList(start, Math.min(start + 500, ids.size()));
            String sql = "DELETE FROM " + table + " WHERE " + column + " IN (" + String.join(",", Collections.nCopies(chunk.size(), "?")) + ")";
            try (var statement = connection.prepareStatement(sql)) {
                for (int index = 0; index < chunk.size(); index++) statement.setLong(index + 1, chunk.get(index));
                if (statement.executeUpdate() != chunk.size()) throw failure(Code.ROW_COUNT_MISMATCH);
            }
        }
    }

    /** Future FK additions require an explicit cleanup review rather than relying on cascade. */
    private static void verifyRelations(Connection connection) throws SQLException {
        Set<String> tables = new HashSet<>();
        try (var statement = connection.prepareStatement("SELECT TABLE_NAME,TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()"); var rows = statement.executeQuery()) {
            while (rows.next()) {
                if (!"BASE TABLE".equals(rows.getString(2))) throw failure(Code.SCHEMA_REJECTED);
                tables.add(rows.getString(1));
            }
        }
        if (!tables.equals(Set.of("users", "cards", "transactions", "budgets", "analysis_job",
            "peer_transaction_stats", "dummy", "demo_visit", "demo_capacity", "demo_admission_lock"))) throw failure(Code.SCHEMA_REJECTED);
        Set<String> expected = Set.of("cards:user_id:users:id", "transactions:card_id:cards:id",
            "budgets:user_id:users:id", "demo_visit:user_id:users:id");
        Set<String> actual = new HashSet<>();
        String sql = "SELECT k.TABLE_NAME,k.COLUMN_NAME,k.REFERENCED_TABLE_NAME,k.REFERENCED_COLUMN_NAME,r.DELETE_RULE,r.UPDATE_RULE,k.REFERENCED_TABLE_SCHEMA,k.CONSTRAINT_SCHEMA "
            + "FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r "
            + "ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.TABLE_NAME=k.TABLE_NAME AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME "
            + "WHERE (k.CONSTRAINT_SCHEMA=DATABASE() AND k.TABLE_NAME IN ('users','cards','transactions','budgets','demo_visit')) "
            + "OR (k.REFERENCED_TABLE_SCHEMA=DATABASE() AND k.REFERENCED_TABLE_NAME IN ('users','cards','transactions','budgets','demo_visit'))";
        try (var statement = connection.prepareStatement(sql); var rows = statement.executeQuery()) {
            while (rows.next()) {
                String key = rows.getString(1) + ":" + rows.getString(2) + ":" + rows.getString(3) + ":" + rows.getString(4);
                if (!Set.of("RESTRICT", "NO ACTION").contains(rows.getString(5))
                    || !Set.of("RESTRICT", "NO ACTION").contains(rows.getString(6))
                    || !connection.getCatalog().equals(rows.getString(7)) || !connection.getCatalog().equals(rows.getString(8)) || !actual.add(key)) throw failure(Code.SCHEMA_REJECTED);
            }
        }
        if (!actual.equals(expected)) throw failure(Code.SCHEMA_REJECTED);
    }

    private static ResultSet query(PreparedStatement statement, boolean explicitNowait) throws SQLException {
        try { return statement.executeQuery(); }
        catch (SQLException error) {
            if (explicitNowait && isExplicitNowait(error)) throw failure(Code.LOCK_BUSY);
            throw error;
        }
    }
    static boolean isExplicitNowait(Throwable error) { return DemoAdmissionSchema.isNowaitConflict(error); }
    private static String lock(boolean apply) { return apply ? " FOR UPDATE NOWAIT" : ""; }
    private static CleanupFailure failure(Code code) { return new CleanupFailure(code); }
}
