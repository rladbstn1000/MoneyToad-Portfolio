package com.potg.don.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import com.potg.don.auth.demo.DemoSessionService;
import com.potg.don.auth.jwt.JwtUtil;
import com.potg.don.card.dto.request.CardRequest;
import com.potg.don.card.entity.Card;
import com.potg.don.card.repository.CardRepository;
import com.potg.don.demo.seed.DemoSeedService;
import com.potg.don.transaction.entity.Transaction;
import com.potg.don.transaction.repository.TransactionRepository;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;

/** Actual owned MySQL constraints, transactional retries and product profile boundaries. */
@Execution(ExecutionMode.SAME_THREAD)
class DemoChartSeedInstallationTest {
    private static final LocalDate ANCHOR = LocalDate.of(2024, 2, 29);

    @Test
    void repeatedInstallationPreservesEveryColumnIncludingEditedCategoryAndBudget() throws Exception {
        try (var s = new DemoChartSeedTestSupport()) {
            User user = s.users.saveAndFlush(User.createUser("seed-repeat@moneytoad.invalid", "합성 설치 검증"));
            s.transaction.executeWithoutResult(status -> s.seed.install(user, ANCHOR));
            s.assertOwnedCounts(user.getId());
            long transactionId = s.jdbc.queryForObject("SELECT MIN(t.id) FROM transactions t JOIN cards c ON t.card_id=c.id WHERE c.user_id=?", Long.class, user.getId());
            long budgetId = s.jdbc.queryForObject("SELECT MIN(id) FROM budgets WHERE user_id=?", Long.class, user.getId());
            s.jdbc.update("UPDATE transactions SET category=? WHERE id=?", "기타", transactionId);
            s.jdbc.update("UPDATE budgets SET amount=?, is_overridden=true, overridden_at=? WHERE id=?", 777_777, ANCHOR.atStartOfDay(), budgetId);
            var before = s.ownedSnapshot(user.getId());
            s.transaction.executeWithoutResult(status -> s.seed.install(user, ANCHOR));
            s.transaction.executeWithoutResult(status -> s.seed.install(user, ANCHOR.plusMonths(2)));
            assertThat(before.equals(s.ownedSnapshot(user.getId()))).isTrue();
            assertThat(s.seed.requireAnchorMonth(user.getId())).isEqualTo(YearMonth.of(2024, 2));
            assertThat(s.observations.transactionInserts.get()).isEqualTo(240);
            assertThat(s.observations.budgetInserts.get()).isEqualTo(72);
            s.evidence("seed_repeat_preserves_edits", Map.of("repeatCalls", 2, "duplicateTransactions", 0,
                "duplicateBudgets", 0, "everyColumnUnchanged", true, "anchorPreserved", true));
        }
    }

    @Test
    void partialOrCorruptInstallationsAreRejectedWithoutRepairOrAnyWrite() throws Exception {
        try (var s = new DemoChartSeedTestSupport()) {
            List<String> corruptions = List.of("missingTransaction", "missingBudget", "duplicateBudgetSlot", "changedAmount", "changedDate", "budgetWithoutCard");
            for (int i = 0; i < corruptions.size(); i++) {
                User user = s.users.saveAndFlush(User.createUser("seed-partial-" + i + "@moneytoad.invalid", "합성 부분 검증"));
                s.transaction.executeWithoutResult(status -> s.seed.install(user, ANCHOR));
                long card = s.jdbc.queryForObject("SELECT id FROM cards WHERE user_id=?", Long.class, user.getId());
                long tx = s.jdbc.queryForObject("SELECT MIN(id) FROM transactions WHERE card_id=?", Long.class, card);
                long budget = s.jdbc.queryForObject("SELECT MIN(id) FROM budgets WHERE user_id=?", Long.class, user.getId());
                switch (corruptions.get(i)) {
                    case "missingTransaction" -> s.jdbc.update("DELETE FROM transactions WHERE id=?", tx);
                    case "missingBudget" -> s.jdbc.update("DELETE FROM budgets WHERE id=?", budget);
                    case "duplicateBudgetSlot" -> {
                        long secondBudget = s.jdbc.queryForObject("SELECT MIN(id) FROM budgets WHERE user_id=? AND id<>?", Long.class, user.getId(), budget);
                        s.jdbc.update("UPDATE budgets target JOIN budgets source ON source.id=? SET target.budget_date=source.budget_date,target.category=source.category WHERE target.id=?", budget, secondBudget);
                        assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM budgets WHERE user_id=?", Integer.class, user.getId())).isEqualTo(72);
                    }
                    case "changedAmount" -> s.jdbc.update("UPDATE transactions SET amount=amount+1 WHERE id=?", tx);
                    case "changedDate" -> s.jdbc.update("UPDATE transactions SET transaction_date_time=DATE_ADD(transaction_date_time, INTERVAL 1 DAY) WHERE id=?", tx);
                    case "budgetWithoutCard" -> {
                        s.jdbc.update("DELETE FROM transactions WHERE card_id=?", card);
                        s.jdbc.update("DELETE FROM cards WHERE id=?", card);
                    }
                    default -> throw new AssertionError("Unknown synthetic corruption");
                }
                var before = DemoAuthHttpTestSupport.snapshot(s.jdbc);
                assertThatThrownBy(() -> s.transaction.executeWithoutResult(status -> s.seed.install(user, ANCHOR)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("DEMO_SEED_INCOMPLETE");
                assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(s.jdbc))).isTrue();
            }
            s.evidence("partial_seed_rejected_without_repair", Map.of("corruptionCases", corruptions.size(), "everyColumnUnchanged", true));
        }
    }

    @Test
    void simultaneousSameUserFirstInstallsHaveAtMostOneCommitAndNoDuplicates() throws Exception {
        try (var s = new DemoChartSeedTestSupport()) {
            User user = s.users.saveAndFlush(User.createUser("seed-concurrent@moneytoad.invalid", "합성 동시 검증"));
            s.observations.emptyCardReads = new CyclicBarrier(2);
            var executor = Executors.newFixedThreadPool(2);
            List<Boolean> results;
            try {
                java.util.concurrent.Callable<Boolean> install = () -> {
                    try {
                        s.transaction.executeWithoutResult(status -> s.seed.install(user, ANCHOR));
                        return true;
                    } catch (RuntimeException failure) { return false; }
                };
                var one = executor.submit(install); var two = executor.submit(install);
                results = List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
            } finally {
                s.observations.emptyCardReads = null;
                executor.shutdownNow();
            }
            assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
            s.assertOwnedCounts(user.getId());
            assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
            s.evidence("same_user_seed_concurrency", Map.of("successfulCommits", 1, "failedTransactions", 1,
                "cards", 1, "transactions", 240, "budgets", 72));
        }
    }

    @Test
    void storedHistoricalAnchorDrivesActualAnnualApiAndNeverSeedsOnRead() throws Exception {
        try (var s = new DemoChartSeedTestSupport()) {
            User user = s.users.saveAndFlush(User.createUser("seed-anchor@moneytoad.invalid", "합성 기준월 검증"));
            s.transaction.executeWithoutResult(status -> s.seed.install(user, ANCHOR));
            var tokens = s.started.context().getBean(DemoSessionService.class).startForUser(user.getId());
            var visitor = new DemoChartSeedTestSupport.Visitor(tokens.accessToken(), user.getId(), 0);
            var before = s.ownedSnapshot(user.getId());
            var annual = DemoChartSeedHttpIntegrationTest.jsonGet(s, visitor, "/transactions");
            assertThat(annual.size()).isEqualTo(12);
            assertThat(annual.get(0).path("date").asText()).isEqualTo("2023-03");
            assertThat(annual.get(11).path("date").asText()).isEqualTo("2024-02");
            assertThat(DemoChartSeedHttpIntegrationTest.sum(annual, "totalAmount")).isEqualTo(9_990_000);
            assertThat(DemoChartSeedHttpIntegrationTest.jsonGet(s, visitor, "/transactions/2024/2").size()).isEqualTo(20);
            assertThat(s.observations.seedCalls.get()).isEqualTo(1);
            assertThat(before.equals(s.ownedSnapshot(user.getId()))).isTrue();
            assertThat(s.jdbc.queryForObject("SELECT MAX(t.transaction_date_time) FROM transactions t JOIN cards c ON t.card_id=c.id WHERE c.user_id=?", java.sql.Timestamp.class,
                user.getId()).toLocalDateTime().toLocalDate()).isEqualTo(LocalDate.of(2024, 2, 28));
            s.evidence("stored_anchor_actual_http", Map.of("monthCount", 12, "lastYear", 2024, "lastMonth", 2,
                "yearlyTotal", 9_990_000, "readSeedCalls", 0, "databaseUnchanged", true));
        }
    }

    @Test
    void realSchemaAllowsNullFinancialFieldsAndRequiresCallerTransaction() throws Exception {
        try (var s = new DemoChartSeedTestSupport()) {
            User user = s.users.saveAndFlush(User.createUser("seed-schema@moneytoad.invalid", "합성 스키마 검증"));
            var before = DemoAuthHttpTestSupport.snapshot(s.jdbc);
            assertThatThrownBy(() -> s.seed.install(user, ANCHOR)).isInstanceOf(IllegalTransactionStateException.class);
            assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(s.jdbc))).isTrue();
            var nullability = s.jdbc.queryForList("SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cards' AND column_name IN ('card_no','cvc')", String.class);
            assertThat(nullability).containsExactlyInAnyOrder("YES", "YES");
            assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='cards' AND column_name='user_id' AND non_unique=0", Integer.class)).isEqualTo(1);
            assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND table_name='transactions' AND column_name='card_id' AND referenced_table_name='cards'", Integer.class)).isEqualTo(1);
            s.evidence("actual_schema_and_mandatory_transaction", Map.of("nullableFinancialColumns", 2, "uniqueCardOwner", true,
                "transactionCardForeignKey", true, "outsideTransactionWrites", 0));
        }
    }

    @Test
    void ordinaryProfilesHaveNoSeedBeanAndKeepCurrentAnnualWindowAndCardDelete() throws Exception {
        for (String profile : List.of("", "default", "prod", "production")) {
            try (var support = new DemoAuthHttpTestSupport();
                 var started = support.start(profile, "", "false", "standard", true, Map.of())) {
                assertThat(started.failure() == null).isTrue();
                var context = started.context();
                assertThat(context.getBeansOfType(DemoSeedService.class)).isEmpty();
                var users = context.getBean(UserRepository.class);
                User user = users.saveAndFlush(User.createUser("seed-oauth-control@moneytoad.invalid", "합성 원본 경로 검증"));
                Card card = context.getBean(CardRepository.class).saveAndFlush(Card.createCard(new CardRequest(), user));
                context.getBean(TransactionRepository.class).saveAndFlush(Transaction.builder().card(card)
                    .transactionDateTime(LocalDate.of(2020, 1, 1).atStartOfDay()).merchantName("합성 과거 거래")
                    .amount(100).category("카페").build());
                JwtUtil jwt = context.getBean(JwtUtil.class);
                String access = jwt.createAccessToken(user.getId(), user.getEmail());
                var mvc = DemoAuthHttpIntegrationTest.mvc(started);
                var response = mvc.perform(get("/api/transactions").contextPath("/api").header("Authorization", "Bearer " + access)).andReturn();
                assertThat(response.getResponse().getStatus()).isEqualTo(200);
                var annual = DemoAuthHttpTestSupport.JSON.readTree(response.getResponse().getContentAsString());
                assertThat(annual.size()).isEqualTo(12);
                assertThat(annual.get(11).path("date").asText()).isEqualTo(YearMonth.now().toString());
                // Original CardService deletes only the Card; prepare a card with no child rows for this control.
                // Its pre-existing foreign-key failure on nonempty cards is not changed by demo seeding.
                context.getBean(TransactionRepository.class).deleteAll();
                var deleted = mvc.perform(delete("/api/cards").contextPath("/api").header("Authorization", "Bearer " + access)).andReturn();
                assertThat(deleted.getResponse().getStatus()).isEqualTo(204);
                JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cards", Integer.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class)).isZero();
                assertThat(users.count()).isEqualTo(1);
                assertThat(support.noOutbound(started.probe())).isTrue();
            }
        }
        System.out.println("DEMO_CHART_SEED_EVIDENCE " + DemoAuthHttpTestSupport.JSON.writeValueAsString(Map.of(
            "scenario", "normal_profiles_preserved", "profiles", 4, "seedBeans", 0, "currentAnnualWindow", true,
            "originalCardDeletes204", 4, "outboundRequests", 0)));
    }
}
