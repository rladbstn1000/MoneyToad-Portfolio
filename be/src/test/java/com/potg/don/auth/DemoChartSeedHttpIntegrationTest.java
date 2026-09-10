package com.potg.don.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.potg.don.demo.seed.DemoSeedScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/** Product login, JWT security, MySQL repositories and real Chart API; no synthetic response double. */
@Execution(ExecutionMode.SAME_THREAD)
class DemoChartSeedHttpIntegrationTest {
    @Test
    void loginMakesTheAnnualChartReadyWithTwelveSeededMonths() throws Exception {
        try (var scenario = new DemoAuthHttpIntegrationTest.Scenario(false)) {
            var login = scenario.login("{}");
            var result = scenario.mvc.perform(get("/api/transactions").contextPath("/api")
                .header("Authorization", "Bearer " + login.access())).andReturn();
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("scenario", "login_chart_ready");
            evidence.put("annualHttpStatus", result.getResponse().getStatus());
            evidence.put("users", scenario.jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
            evidence.put("outboundRequests", scenario.support.stubRequests.get() + scenario.started.probe().webClientBlocked.get());
            evidence.put("cards", scenario.jdbc.queryForObject("SELECT COUNT(*) FROM cards", Integer.class));
            evidence.put("transactions", scenario.jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class));
            evidence.put("budgets", scenario.jdbc.queryForObject("SELECT COUNT(*) FROM budgets", Integer.class));
            evidence.put("noOutbound", scenario.support.noOutbound(scenario.started.probe()));
            System.out.println("DEMO_CHART_SEED_EVIDENCE " + DemoAuthHttpTestSupport.JSON.writeValueAsString(evidence));
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            var body = DemoAuthHttpTestSupport.JSON.readTree(result.getResponse().getContentAsString());
            assertThat(body.isArray()).isTrue();
            assertThat(body.size()).isEqualTo(12);
            assertThat(evidence.get("cards")).isEqualTo(1);
            assertThat(evidence.get("transactions")).isEqualTo(240);
            assertThat(evidence.get("budgets")).isEqualTo(72);
            assertThat(evidence.get("noOutbound")).isEqualTo(true);
        }
    }

    @Test
    void realLoginRepositoryAndChartPatchPreserveTotalsAndOtherVisitorEveryColumn() throws Exception {
        try (var s = new DemoChartSeedTestSupport()) {
            var a = s.login();
            s.assertOwnedCounts(a.userId());
            assertThat(s.observations.userInserts.get()).isEqualTo(1);
            assertThat(s.observations.cardInserts.get()).isEqualTo(1);
            assertThat(s.observations.transactionInserts.get()).isEqualTo(240);
            assertThat(s.observations.budgetInserts.get()).isEqualTo(72);
            assertThat(s.observations.nonParameterizedInserts.get()).isZero();
            assertThat(s.observations.multiRowInsertStatements.get()).isZero();
            s.evidence("login_insert_observation", Map.of(
                "userInsertStatements", 1, "cardInsertStatements", 1, "transactionInsertStatements", 240,
                "budgetInsertStatements", 72, "multiRowInsertStatements", 0, "parameterizedOnly", true,
                "loginElapsedMillis", a.loginElapsedMillis(),
                "seedElapsedMillis", TimeUnit.NANOSECONDS.toMillis(s.observations.seedNanos.get()),
                "seedCalls", s.observations.seedCalls.get()));

            var b = s.login();
            s.assertOwnedCounts(b.userId());
            assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(2);
            assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM peer_transaction_stats", Integer.class)).isZero();
            var bBefore = s.ownedSnapshot(b.userId());
            var annual = jsonGet(s, a, "/transactions");
            assertThat(annual.size()).isEqualTo(12);
            int yearlyTotal = 0;
            for (var month : annual) yearlyTotal += month.path("totalAmount").asInt();
            assertThat(yearlyTotal).isEqualTo(9_990_000);
            YearMonth anchor = YearMonth.parse(annual.get(11).path("date").asText());
            assertThat(anchor).isEqualTo(YearMonth.now(ZoneId.of("Asia/Seoul")));
            for (int i = 0; i < 12; i++) {
                assertThat(annual.get(i).path("date").asText()).isEqualTo(anchor.minusMonths(11 - i).toString());
                int expected = i == 6 ? 962_000 : i == 9 ? 842_000 : i == 10 ? 862_000 : i == 11 ? 908_000 : 802_000;
                assertThat(annual.get(i).path("totalAmount").asInt()).isEqualTo(expected);
                assertThat(annual.get(i).path("leaked").asBoolean()).isEqualTo(i == 6 || i == 11);
            }
            assertThat(s.jdbc.queryForObject("SELECT SUM(t.amount) FROM transactions t JOIN cards c ON t.card_id=c.id WHERE c.user_id=?", Long.class, a.userId())).isEqualTo(9_990_000L);
            assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM transactions t JOIN cards c ON t.card_id=c.id WHERE c.user_id=? AND t.transaction_date_time>?", Integer.class,
                a.userId(), java.time.LocalDateTime.now(ZoneId.of("Asia/Seoul")))).isZero();

            String monthlyPath = "/transactions/" + anchor.getYear() + "/" + anchor.getMonthValue();
            var monthly = jsonGet(s, a, monthlyPath);
            assertThat(monthly.size()).isEqualTo(20);
            int monthlyTotal = 0; long practice = 0;
            for (var row : monthly) {
                monthlyTotal += row.path("amount").asInt();
                if (DemoSeedScenario.PRACTICE_MERCHANT.equals(row.path("merchantName").asText())) practice = row.path("id").asLong();
            }
            assertThat(monthlyTotal).isEqualTo(908_000);
            assertThat(practice).isPositive();
            var categories = jsonGet(s, a, monthlyPath + "/categories");
            assertThat(sum(categories, "totalAmount")).isEqualTo(908_000);
            assertThat(sum(categories, "leakedAmount")).isEqualTo(18_000);
            var culture = jsonGet(s, a, "/transactions/" + anchor.minusMonths(5).getYear() + "/" + anchor.minusMonths(5).getMonthValue() + "/categories");
            assertThat(sum(culture, "leakedAmount")).isEqualTo(120_000);

            var patchResult = s.mvc.perform(patch("/api/transactions/" + practice + "/category").contextPath("/api")
                .header("Authorization", "Bearer " + a.access()).contentType("application/json")
                .content("{\"category\":\"마트 / 편의점\"}")).andReturn();
            assertThat(patchResult.getResponse().getStatus()).isEqualTo(200);
            assertThat(s.jdbc.queryForObject("SELECT category FROM transactions WHERE id=?", String.class, practice)).isEqualTo("마트 / 편의점");
            var after = jsonGet(s, a, monthlyPath + "/categories");
            assertThat(sum(after, "totalAmount")).isEqualTo(908_000);
            assertThat(sum(after, "leakedAmount")).isZero();
            var afterAnnual = jsonGet(s, a, "/transactions");
            assertThat(afterAnnual.get(11).path("totalAmount").asInt()).isEqualTo(908_000);
            assertThat(afterAnnual.get(11).path("leaked").asBoolean()).isFalse();
            assertThat(jsonGet(s, a, monthlyPath).size()).isEqualTo(20);
            assertThat(bBefore.equals(s.ownedSnapshot(b.userId()))).as("Visitor B every column unchanged").isTrue();

            var bMonthly = jsonGet(s, b, monthlyPath);
            long bTransaction = bMonthly.get(0).path("id").asLong();
            for (var bRow : bMonthly) {
                for (var aRow : monthly) assertThat(bRow.path("id").asLong()).isNotEqualTo(aRow.path("id").asLong());
            }
            var allBeforeForbidden = DemoAuthHttpTestSupport.snapshot(s.jdbc);
            var foreignPatch = s.mvc.perform(patch("/api/transactions/" + bTransaction + "/category").contextPath("/api")
                .header("Authorization", "Bearer " + a.access()).contentType("application/json")
                .content("{\"category\":\"카페\"}")).andReturn();
            // Preserve the known original NoSuchElementException -> generic 500 contract in this scoped change.
            assertThat(foreignPatch.getResponse().getStatus()).isEqualTo(500);
            assertThat(allBeforeForbidden.equals(DemoAuthHttpTestSupport.snapshot(s.jdbc))).isTrue();
            assertThat(jsonGet(s, b, "/transactions").get(11).path("leaked").asBoolean()).isTrue();
            s.evidence("actual_chart_patch_isolation", Map.of("users", 2, "cards", 2, "transactions", 480, "budgets", 144,
                "yearlyTotal", yearlyTotal, "monthlyTotal", 908_000, "leakBefore", 18_000, "leakAfter", 0,
                "otherVisitorEveryColumnUnchanged", true, "foreignPatchDatabaseUnchanged", true));
        }
    }

    @Test
    void demoCardMutationEndpointsDenyBeforeAnyDummyCsvOrJobWork() throws Exception {
        try (var s = new DemoChartSeedTestSupport()) {
            var visitor = s.login();
            var before = DemoAuthHttpTestSupport.snapshot(s.jdbc);
            var requests = java.util.List.of(post("/api/cards"), patch("/api/cards"), delete("/api/cards"));
            for (var request : requests) {
                var result = s.mvc.perform(request.contextPath("/api").header("Authorization", "Bearer " + visitor.access())
                    .contentType("application/json").content("{}")).andReturn();
                assertThat(result.getResponse().getStatus()).isEqualTo(403);
            }
            assertThat(s.mvc.perform(post("/api/cards").contextPath("/api").contentType("application/json").content("{}"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
            assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(s.jdbc))).isTrue();
            var card = jsonGet(s, visitor, "/cards");
            assertThat(card.path("cardNo").isNull()).isTrue();
            assertThat(card.path("cvc").isNull()).isTrue();
            s.evidence("demo_card_mutation_blocked", Map.of("authenticated403", 3, "anonymous401", true,
                "databaseEveryColumnUnchanged", true, "cardFinancialFieldsNull", true));
        }
    }

    static JsonNode jsonGet(DemoChartSeedTestSupport s, DemoChartSeedTestSupport.Visitor visitor, String path) throws Exception {
        var result = s.mvc.perform(get("/api" + path).contextPath("/api")
            .header("Authorization", "Bearer " + visitor.access())).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return DemoAuthHttpTestSupport.JSON.readTree(result.getResponse().getContentAsString());
    }
    static int sum(JsonNode rows, String field) {
        int result = 0; for (var row : rows) result += row.path(field).asInt(); return result;
    }
}
