package com.potg.don.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import java.time.YearMonth;
import java.time.Duration;
import java.net.InetAddress;
import java.net.URI;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.EnumSet;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.potg.don.transaction.dto.request.UpdateCategoryRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import com.potg.don.global.config.JsonRequestConfiguration;
import com.potg.don.transaction.controller.TransactionController;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;

/** Real JWT/Redis guard, product MVC/services and owned MySQL; no test-managed transaction. */
@Execution(ExecutionMode.SAME_THREAD)
class TransactionCategoryContractIntegrationTest {
    private static final List<String> INPUT_CATEGORIES = List.of(
        "식비", "카페", "마트 / 편의점", "문화생활", "교통 / 차량", "패션 / 미용",
        "생활용품", "주거 / 통신", "건강 / 병원", "교육", "경조사 / 회비", "기타", "보험 / 세금");

    @Test
    void everySupportedCategoryChangesOnlyTheOwnedTransactionCategory() throws Exception {
        try (var s = new DemoChartSeedTestSupport()) {
            var owner = s.login();
            var other = s.login();
            long target = transactionId(s, owner);
            long foreign = transactionId(s, other);
            assertThat(target != foreign).as("Distinct visitor transaction IDs").isTrue();
            Long ownerCard = s.jdbc.queryForObject("SELECT card_id FROM transactions WHERE id=?", Long.class, target);
            Long otherCard = s.jdbc.queryForObject("SELECT card_id FROM transactions WHERE id=?", Long.class, foreign);
            assertThat(!ownerCard.equals(otherCard)).as("Distinct visitor cards").isTrue();
            for (String category : INPUT_CATEGORIES) {
                var before = DemoAuthHttpTestSupport.snapshot(s.jdbc);
                var response = patchCategory(s, owner, target,
                    DemoAuthHttpTestSupport.JSON.writeValueAsString(Map.of("category", category)));
                assertThat(response.getResponse().getStatus()).as("Supported category status").isEqualTo(200);
                JsonNode body = DemoAuthHttpTestSupport.JSON.readTree(response.getResponse().getContentAsString());
                assertThat(body.path("category").asText()).isEqualTo(category);
                var expected = withCategory(before, target, category);
                assertThat(expected.equals(DemoAuthHttpTestSupport.snapshot(s.jdbc)))
                    .as("Fresh committed reads: only target category changes; all other rows and columns unchanged").isTrue();
            }
            s.evidence("category_supported_inputs", Map.of("acceptedCategories", INPUT_CATEGORIES.size(),
                "distinctVisitorsAndCards", true, "onlyTargetCategoryChanged", true));
        }
    }

    @Test
    void foreignAndAbsentTransactionsShareGeneric404AndAnonymousRemains401() throws Exception {
        try (var s = new DemoChartSeedTestSupport()) {
            var owner = s.login();
            var other = s.login();
            long target = transactionId(s, owner);
            long foreign = transactionId(s, other);
            assertThat(target != foreign).isTrue();
            var before = DemoAuthHttpTestSupport.snapshot(s.jdbc);
            var denied = patchCategory(s, owner, foreign, "{\"category\":\"카페\"}");
            assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(s.jdbc)))
                .as("Fresh reads after foreign request preserve every table and column").isTrue();
            var absent = patchCategory(s, owner, Long.MAX_VALUE, "{\"category\":\"카페\"}");
            assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(s.jdbc)))
                .as("Fresh reads after absent request preserve every table and column").isTrue();
            assertThat(denied.getResponse().getStatus()).isEqualTo(404);
            assertThat(absent.getResponse().getStatus()).isEqualTo(404);
            assertThat(denied.getResponse().getContentAsString().equals(absent.getResponse().getContentAsString()))
                .as("Missing and foreign resources have identical public response").isTrue();
            JsonNode error = DemoAuthHttpTestSupport.JSON.readTree(denied.getResponse().getContentAsString());
            assertThat(error.path("message").asText()).isEqualTo("거래내역을 찾을 수 없습니다.");
            var anonymous = s.mvc.perform(patch("/api/transactions/" + target + "/category").contextPath("/api")
                .contentType("application/json").content("{\"category\":\"카페\"}")).andReturn();
            assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
            assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(s.jdbc)))
                .as("Fresh reads after every denied request preserve every table and column").isTrue();
            s.evidence("category_ownership_status", Map.of("foreignStatus", 404, "absentStatus", 404,
                "anonymousStatus", 401, "databaseUnchanged", true));
        }
    }

    @Test
    void invalidValuesAndJsonAre400WithoutEchoOrCommittedSideEffects() throws Exception {
        try (var s = new DemoChartSeedTestSupport()) {
            var owner = s.login();
            s.login();
            long target = transactionId(s, owner);
            String canary = "synthetic-invalid-" + UUID.randomUUID();
            List<String> requests = List.of(
                "{}", "{\"category\":null}", "{\"category\":\"\"}", "{\"category\":\"   \"}",
                "{\"category\":\"\\t\\n\"}", "{\"category\":\"카페 \"}",
                DemoAuthHttpTestSupport.JSON.writeValueAsString(Map.of("category", canary)),
                "{\"category\":123}", "{\"category\":true}", "{\"category\":[]}",
                "{\"category\":{\"marker\":\"" + canary + "\"}}", "[]", "null", "",
                "{\"category\":\"" + canary + "\"");
            try (var logs = new ErrorCapture()) {
                for (String request : requests) {
                    var before = DemoAuthHttpTestSupport.snapshot(s.jdbc);
                    var response = patchCategory(s, owner, target, request);
                    assertThat(response.getResponse().getStatus()).as("Invalid input status").isEqualTo(400);
                    JsonNode error = DemoAuthHttpTestSupport.JSON.readTree(response.getResponse().getContentAsString());
                    assertThat(error.path("status").asInt()).isEqualTo(400);
                    assertThat(error.path("message").asText()).isIn(
                        "지원하는 거래 카테고리를 입력해 주세요.", "요청 본문 형식이 올바르지 않습니다.");
                    assertThat(!response.getResponse().getContentAsString().contains(canary))
                        .as("Rejected input is absent from response").isTrue();
                    assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(s.jdbc)))
                        .as("Rejected input leaves all committed rows and columns unchanged").isTrue();
                }
                assertThat(logs.hasNoCanary(canary)).as("Rejected input is absent from error output").isTrue();
            }
            s.evidence("category_invalid_input", Map.of("rejectedInputs", requests.size(), "status", 400,
                "databaseUnchanged", true, "inputEchoAbsent", true));
        }
    }

    @Test
    void legacyNullAndEmptyRowsAreMergedIntoOthersWithoutLosingAmountsOrIncludingInsuranceInLeak() throws Exception {
        try (var s = new DemoChartSeedTestSupport()) {
            var owner = s.login();
            var other = s.login();
            var untouched = s.ownedSnapshot(other.userId());
            JsonNode annualBefore = DemoChartSeedHttpIntegrationTest.jsonGet(s, owner, "/transactions");
            YearMonth month = YearMonth.parse(annualBefore.get(11).path("date").asText());
            long card = s.jdbc.queryForObject("SELECT card_id FROM transactions WHERE id=?", Long.class, transactionId(s, owner));
            // Keep the demo's exact 240 rows, dates, merchants and amounts valid.
            var legacyTargets = s.jdbc.queryForList(
                "SELECT id FROM transactions WHERE card_id=? AND transaction_date_time>=? AND transaction_date_time<? AND category=? AND amount=? ORDER BY id LIMIT 4",
                Long.class, card, month.atDay(1).atStartOfDay(), month.plusMonths(1).atDay(1).atStartOfDay(), "카페", 4_000);
            assertThat(legacyTargets).hasSize(4);
            String[] legacyCategories = {null, "", "기타", "보험 / 세금"};
            for (int i = 0; i < legacyCategories.length; i++) {
                assertThat(s.jdbc.update("UPDATE transactions SET category=? WHERE id=?",
                    legacyCategories[i], legacyTargets.get(i))).isEqualTo(1);
            }
            s.assertOwnedCounts(owner.userId());
            var beforeReads = DemoAuthHttpTestSupport.snapshot(s.jdbc);
            String monthlyPath = "/transactions/" + month.getYear() + "/" + month.getMonthValue();
            JsonNode monthly = DemoChartSeedHttpIntegrationTest.jsonGet(s, owner, monthlyPath);
            assertThat(monthly.size()).isEqualTo(20);
            assertThat(DemoChartSeedHttpIntegrationTest.sum(monthly, "amount")).isEqualTo(908_000);
            int normalizedRows = 0;
            for (JsonNode row : monthly) {
                assertThat(row.path("category").isTextual()).isTrue();
                assertThat(row.path("category").asText().isEmpty()).isFalse();
                if (row.path("category").asText().equals("기타")) normalizedRows++;
            }
            assertThat(normalizedRows).isEqualTo(3);
            JsonNode categories = DemoChartSeedHttpIntegrationTest.jsonGet(s, owner, monthlyPath + "/categories");
            assertThat(DemoChartSeedHttpIntegrationTest.sum(categories, "totalAmount")).isEqualTo(908_000);
            JsonNode others = oneCategory(categories, "기타");
            assertThat(others.path("totalAmount").asInt()).isEqualTo(12_000);
            assertThat(others.path("leakedAmount").asInt()).isEqualTo(12_000);
            JsonNode insurance = oneCategory(categories, "보험 / 세금");
            assertThat(insurance.path("totalAmount").asInt()).isEqualTo(4_000);
            assertThat(insurance.path("leakedAmount").asInt()).isZero();
            JsonNode budgets = DemoChartSeedHttpIntegrationTest.jsonGet(s, owner,
                "/budgets/" + month.getYear() + "/" + month.getMonthValue());
            assertThat(budgets.size()).isEqualTo(12);
            assertThat(DemoChartSeedHttpIntegrationTest.sum(budgets, "spending")).isEqualTo(904_000);
            assertThat(oneCategory(budgets, "기타").path("spending").asInt()).isEqualTo(12_000);
            assertThat(oneCategory(budgets, "기타").path("id").isNull()).isTrue();
            JsonNode annual = DemoChartSeedHttpIntegrationTest.jsonGet(s, owner, "/transactions");
            assertThat(annual.size()).isEqualTo(12);
            assertThat(annual.get(11).path("totalAmount").asInt()).isEqualTo(904_000);
            assertThat(DemoChartSeedHttpIntegrationTest.sum(annual, "totalAmount")).isEqualTo(9_986_000);
            assertThat(annual.get(11).path("leaked").asBoolean()).isTrue();
            assertThat(DemoChartSeedHttpIntegrationTest.jsonGet(s, owner, "/budgets").size()).isEqualTo(12);
            assertThat(untouched.equals(s.ownedSnapshot(other.userId()))).as("Other visitor unchanged").isTrue();
            assertThat(beforeReads.equals(DemoAuthHttpTestSupport.snapshot(s.jdbc)))
                .as("Read normalization does not rewrite legacy storage").isTrue();
            s.evidence("category_legacy_null_empty", Map.of("normalizedOthersRows", 3,
                "mergedOthersAmount", 12_000, "monthlyIncludingInsurance", 908_000,
                "monthlyLeakEligible", 904_000, "insuranceLeak", 0, "databaseUnchangedByRead", true));
        }
    }

    @Test
    void trailingJsonOverRealLoopbackHttpIs400WithoutEchoOrCommittedMutation() throws Exception {
        try (var s = new DemoChartSeedTestSupport()) {
            var owner = s.login();
            long target = transactionId(s, owner);
            var before = DemoAuthHttpTestSupport.snapshot(s.jdbc);
            String canary = "synthetic-trailing-" + UUID.randomUUID();
            List<String> requests = List.of(
                "{\"category\":\"카페\"} {\"marker\":\"" + canary + "\"}",
                "{\"category\":\"카페\"} [\"" + canary + "\"]",
                "{\"category\":\"카페\"} true",
                "{\"category\":\"카페\"} 123",
                "{\"category\":\"카페\"} null",
                "{\"category\":\"카페\"} \"" + canary + "\"",
                "{\"category\":\"카페\"} " + canary);
            assertThat(s.started.context().getBeansOfType(JsonRequestConfiguration.class))
                .as("Product scoped JSON configuration is loaded by the existing ConfigData fixture").hasSize(1);
            var adapter = s.started.context().getBean(RequestMappingHandlerAdapter.class);
            for (var converter : adapter.getMessageConverters()) {
                if (converter instanceof MappingJackson2HttpMessageConverter json) {
                    assertThat(json.getObjectMapper().isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS))
                        .as("Default mapper retains the existing unrelated request contract").isFalse();
                }
            }
            for (String contentType : List.of("application/json", "application/vnd.moneytoad+json")) {
                MediaType mediaType = MediaType.parseMediaType(contentType);
                int categoryReaders = 0;
                for (var converter : adapter.getMessageConverters()) {
                    // Spring Data also adds a Jackson converter that reads only @ProjectedPayload interfaces.
                    // Check every converter eligible for this concrete request using MVC's generic read contract.
                    if (converter instanceof MappingJackson2HttpMessageConverter json
                        && json.canRead(UpdateCategoryRequest.class, TransactionController.class, mediaType)) {
                        categoryReaders++;
                        var scoped = json.getObjectMappersForType(UpdateCategoryRequest.class);
                        assertThat(scoped).as("Scoped mapper for eligible converter %s", json.getClass().getName())
                            .isNotEmpty();
                        var selected = scoped.entrySet().stream().filter(entry -> entry.getKey().includes(mediaType))
                            .findFirst();
                        assertThat(selected).as("Scoped mapping covers each accepted JSON media type").isPresent();
                        assertThat(selected.orElseThrow().getValue().isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS))
                            .as("The mapper selected for the request rejects trailing JSON").isTrue();
                    }
                }
                assertThat(categoryReaders).as("The concrete request has an eligible JSON reader").isGreaterThan(0);
            }
            // Use the existing product context, MVC converters and full SecurityFilterChain on an owned socket.
            var factory = new TomcatServletWebServerFactory();
            factory.setAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
            factory.setPort(0);
            factory.setContextPath("/api");
            var server = factory.getWebServer(context -> {
                var dispatcher = new DispatcherServlet();
                dispatcher.setApplicationContext((WebApplicationContext) s.started.context());
                var servlet = context.addServlet("categoryContractDispatcher", dispatcher);
                servlet.addMapping("/");
                servlet.setLoadOnStartup(1);
                var filter = context.addFilter("categoryContractSecurity",
                    s.started.context().getBean("springSecurityFilterChain", Filter.class));
                filter.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
            });
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, java.io.IOException failure) { }
                }).build(); var logs = new ErrorCapture()) {
                server.start();
                URI endpoint = URI.create("http://127.0.0.1:" + server.getPort() + "/api/transactions/" + target + "/category");
                for (String request : requests) {
                    for (String contentType : List.of("application/json", "application/vnd.moneytoad+json")) {
                        var response = client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.access())
                            .header(HttpHeaders.CONTENT_TYPE, contentType)
                            .method("PATCH", HttpRequest.BodyPublishers.ofString(request)).build(),
                            HttpResponse.BodyHandlers.ofString());
                        assertThat(response.statusCode()).as("Trailing JSON status over actual loopback HTTP").isEqualTo(400);
                        JsonNode error = DemoAuthHttpTestSupport.JSON.readTree(response.body());
                        assertThat(error.path("status").asInt()).isEqualTo(400);
                        assertThat(error.path("message").asText()).isEqualTo("요청 본문 형식이 올바르지 않습니다.");
                        assertThat(response.body().contains(canary)).as("No submitted value in HTTP response").isFalse();
                        assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(s.jdbc)))
                            .as("Every rejected HTTP request preserves committed rows and columns").isTrue();
                    }
                }
                var valid = client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.access())
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"category\":\"카페\"} \n\t"))
                    .build(), HttpResponse.BodyHandlers.ofString());
                assertThat(valid.statusCode()).as("One JSON value followed only by whitespace remains valid").isEqualTo(200);
                assertThat(withCategory(before, target, "카페").equals(DemoAuthHttpTestSupport.snapshot(s.jdbc))).isTrue();
                assertThat(logs.hasNoCanary(canary)).as("No submitted value in normal error logs").isTrue();
            } finally {
                try { server.stop(); }
                finally { server.destroy(); }
            }
            s.evidence("category_trailing_json_loopback_http", Map.of("rejectedHttpRequests", requests.size() * 2,
                "status", 400, "validWhitespaceStatus", 200, "databaseUnchangedOnRejection", true,
                "inputEchoAbsent", true, "defaultMapperUnchanged", true));
        }
    }

    private static long transactionId(DemoChartSeedTestSupport s, DemoChartSeedTestSupport.Visitor visitor) {
        return s.jdbc.queryForObject("SELECT MIN(t.id) FROM transactions t JOIN cards c ON t.card_id=c.id WHERE c.user_id=?",
            Long.class, visitor.userId());
    }

    private static MvcResult patchCategory(DemoChartSeedTestSupport s, DemoChartSeedTestSupport.Visitor visitor,
        long transaction, String body) throws Exception {
        return s.mvc.perform(patch("/api/transactions/" + transaction + "/category").contextPath("/api")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + visitor.access())
            .contentType("application/json").content(body)).andReturn();
    }

    private static Map<String, List<Map<String, Object>>> withCategory(Map<String, List<Map<String, Object>>> rows,
        long target, String category) {
        var expected = new LinkedHashMap<String, List<Map<String, Object>>>();
        rows.forEach((table, records) -> {
            List<Map<String, Object>> copied = new ArrayList<>();
            for (var record : records) {
                var copy = new LinkedHashMap<>(record);
                if (table.equals("transactions") && ((Number) record.get("id")).longValue() == target) {
                    copy.put("category", category);
                }
                copied.add(copy);
            }
            expected.put(table, copied);
        });
        return expected;
    }

    private static JsonNode oneCategory(JsonNode rows, String category) {
        List<JsonNode> matching = new ArrayList<>();
        for (JsonNode row : rows) if (row.path("category").asText().equals(category)) matching.add(row);
        assertThat(matching.size()).as("Exactly one response row per normalized category").isEqualTo(1);
        return matching.getFirst();
    }

    /** Capture normal INFO/WARN/ERROR output in memory; intentional failures never print rejected input. */
    private static final class ErrorCapture implements AutoCloseable {
        private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        private final Level previous = root.getLevel();
        private final List<Appender<ILoggingEvent>> appenders = new ArrayList<>();
        private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

        ErrorCapture() {
            root.iteratorForAppenders().forEachRemaining(appenders::add);
            appenders.forEach(root::detachAppender);
            captured.start();
            root.addAppender(captured);
            root.setLevel(Level.INFO);
        }

        boolean hasNoCanary(String canary) {
            return captured.list.stream().noneMatch(event -> event.getFormattedMessage().contains(canary)
                || (event.getThrowableProxy() != null && throwableContains(event.getThrowableProxy(), canary)));
        }

        private static boolean throwableContains(ch.qos.logback.classic.spi.IThrowableProxy failure, String canary) {
            if (failure == null) return false;
            if (failure.getMessage() != null && failure.getMessage().contains(canary)) return true;
            if (throwableContains(failure.getCause(), canary)) return true;
            if (failure.getSuppressed() != null) {
                for (var suppressed : failure.getSuppressed()) if (throwableContains(suppressed, canary)) return true;
            }
            return false;
        }

        @Override public void close() {
            root.detachAppender(captured);
            captured.stop();
            root.setLevel(previous);
            appenders.forEach(root::addAppender);
        }
    }
}
