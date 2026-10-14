package com.potg.don.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.aopalliance.intercept.MethodInterceptor;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potg.don.auth.jwt.JwtAuthenticationFilter;
import com.potg.don.auth.jwt.JwtUtil;
import com.potg.don.card.dto.request.CardRequest;
import com.potg.don.card.entity.Card;
import com.potg.don.card.repository.CardRepository;
import com.potg.don.card.service.CardService;
import com.potg.don.transaction.dto.response.AnalysisTriggerResponse;
import com.potg.don.transaction.dto.response.CsvUploadResponse;
import com.potg.don.transaction.service.CsvService;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;

/**
 * New regression contract, not a reconstruction of a deleted historical test.
 * Product OAuth ConfigData/JWT/MVC/CardService/CsvService/JPA use owned MySQL.
 * Only DummyService, CsvClient and AnalysisJobService calls are test doubles.
 * No test-managed transaction can hide a committed mutation from fresh JDBC reads.
 */
@Execution(ExecutionMode.SAME_THREAD)
class CardCvcContractIntegrationTest {
    private static final String CARD_ONE = "synthetic-card-one";
    private static final String CARD_TWO = "synthetic-card-two";
    private static final LocalDate ANCHOR = LocalDate.of(2024, 2, 29);

    @Test
    void successfulPostPatchAndGetNeverExposeCvcAndActualOrmWritesExcludeItsColumn() throws Exception {
        try (var s = new OAuthFixture()) {
            User owner = s.user();
            String access = s.access(owner);
            s.observed.reset();
            var created = s.mutate("POST", access, cardBody(CARD_ONE));
            assertCardResponse(created, 201, CARD_ONE);
            assertThat(s.observed.cardInserts.get()).as("Actual ORM card INSERT observed").isEqualTo(1);
            assertThat(s.observed.cardUpdates.get()).isZero();
            assertThat(s.observed.cvcWrites.get()).as("INSERT does not address the CVC column").isZero();
            assertThat(s.jdbc.queryForObject("SELECT cvc IS NULL FROM cards WHERE user_id=?", Boolean.class, owner.getId())).isTrue();
            assertThat(s.jdbc.queryForObject("SELECT file_id IS NOT NULL FROM users WHERE id=?", Boolean.class, owner.getId()))
                .as("Actual CsvService committed its synthetic upload result").isTrue();
            s.observed.assertWorkflowCalls(1);

            s.observed.reset();
            var changed = s.mutate("PATCH", access, cardBody(CARD_TWO));
            assertCardResponse(changed, 200, CARD_TWO);
            assertThat(s.observed.cardInserts.get()).isZero();
            assertThat(s.observed.cardUpdates.get()).as("Actual ORM card UPDATE observed").isEqualTo(1);
            assertThat(s.observed.cvcWrites.get()).as("UPDATE does not address the CVC column").isZero();
            assertThat(s.jdbc.queryForObject("SELECT card_no=? AND cvc IS NULL FROM cards WHERE user_id=?",
                Boolean.class, CARD_TWO, owner.getId())).isTrue();
            s.observed.assertWorkflowCalls(1);

            var beforeRead = allRows(s.jdbc);
            s.observed.reset();
            assertCardResponse(s.getCard(access), 200, CARD_TWO);
            assertThat(beforeRead.equals(allRows(s.jdbc))).as("GET preserves every committed row and column").isTrue();
            s.observed.assertNoSideEffects();
            evidence("ordinary_card_mutations_and_read", Map.of("statuses", List.of(201, 200, 200),
                "actualCardInsertObserved", true, "actualCardUpdateObserved", true, "cvcWriteStatements", 0));
        }
    }

    @Test
    void legacyDatabaseValueSurvivesCardNumberUpdateAndNeverAppearsInEntityOrHttpSerialization() throws Exception {
        try (var s = new OAuthFixture()) {
            User owner = s.user();
            String access = s.access(owner);
            assertCardResponse(s.mutate("POST", access, cardBody(CARD_ONE)), 201, CARD_ONE);
            String canary = "synthetic-legacy-" + UUID.randomUUID();
            assertThat(s.jdbc.update("UPDATE cards SET cvc=? WHERE user_id=?", canary, owner.getId())).isEqualTo(1);
            var before = allRows(s.jdbc);
            s.observed.reset();
            try (var logs = new ErrorCapture()) {
                var changed = s.mutate("PATCH", access, cardBody(CARD_TWO));
                assertCardResponse(changed, 200, CARD_TWO);
                assertThat(!changed.getResponse().getContentAsString().contains(canary))
                    .as("Legacy value absent from mutation response").isTrue();
                assertThat(s.observed.cardUpdates.get()).as("Legacy row received an actual ORM UPDATE").isEqualTo(1);
                assertThat(s.observed.cvcWrites.get()).isZero();
                assertThat(s.jdbc.queryForObject("SELECT BINARY cvc=BINARY ? FROM cards WHERE user_id=?", Boolean.class, canary, owner.getId()))
                    .as("Fresh MySQL read preserves legacy value byte-for-byte").isTrue();
                assertThat(withCardNumber(before, owner.getId(), CARD_TWO).equals(allRows(s.jdbc)))
                    .as("Only the owned card number changes across all SQL tables").isTrue();
                s.observed.assertWorkflowCalls(1);

                s.observed.reset();
                var response = s.getCard(access);
                assertCardResponse(response, 200, CARD_TWO);
                assertThat(!response.getResponse().getContentAsString().contains(canary)).isTrue();
                assertThat(Arrays.stream(Card.class.getMethods()).noneMatch(method -> method.getName().equals("getCvc")))
                    .as("Card has no public raw CVC getter").isTrue();
                assertThat(Arrays.stream(CardRequest.class.getDeclaredFields()).noneMatch(field -> field.getName().equalsIgnoreCase("cvc")))
                    .as("Request model has no CVC field").isTrue();
                assertThat(Arrays.stream(CardRequest.class.getMethods()).noneMatch(method -> method.getName().equals("getCvc")))
                    .as("Request model has no CVC getter").isTrue();
                JsonNode serialized = s.transaction.execute(status -> {
                    // Resolve User first in this same persistence context so unrelated Hibernate proxy
                    // internals do not prevent testing the actual Card JSON serialization contract.
                    s.users.findById(owner.getId()).orElseThrow();
                    Card card = s.cards.findByUserId(owner.getId()).orElseThrow();
                    try { return s.mapper.valueToTree(card); }
                    catch (RuntimeException failure) { throw new AssertionError("CARD_ENTITY_SERIALIZATION_REQUIRED"); }
                });
                assertThat(serialized != null && serialized.isObject()).isTrue();
                assertThat(hasCvcProperty(serialized)).as("Direct entity serialization contains no CVC property").isFalse();
                assertThat(!serialized.toString().contains(canary)).as("Direct entity serialization contains no legacy value").isTrue();
                assertThat(logs.hasNoCanary(canary)).as("Legacy value absent from normal application output").isTrue();
                assertThat(withCardNumber(before, owner.getId(), CARD_TWO).equals(allRows(s.jdbc))).isTrue();
                s.observed.assertNoSideEffects();
            }
            evidence("legacy_cvc_preserved_and_not_exposed", Map.of("legacyValuePreserved", true,
                "publicRawGetter", false, "requestCvcField", false, "entityAndResponseCvcProperty", false));
        }
    }

    @Test
    void cvcFieldWithNullScalarOrStructureIs400BeforeAnyWriteOrDownstreamCall() throws Exception {
        try (var s = new OAuthFixture()) {
            User createOwner = s.user();
            User updateOwner = s.user();
            assertCardResponse(s.mutate("POST", s.access(updateOwner), cardBody(CARD_ONE)), 201, CARD_ONE);
            String canary = "synthetic-rejected-" + UUID.randomUUID();
            List<String> bodies = List.of(
                "{\"cardNo\":\"" + CARD_TWO + "\",\"cvc\":null}",
                "{\"cardNo\":\"" + CARD_TWO + "\",\"cvc\":\"" + canary + "\"}",
                "{\"cvc\":{\"marker\":\"" + canary + "\"},\"cardNo\":\"" + CARD_TWO + "\"}",
                "{\"cardNo\":\"" + CARD_TWO + "\",\"cvc\":[\"" + canary + "\"]}",
                "{\"cardNo\":\"" + CARD_TWO + "\",\"cvc\":true}",
                "{\"cardNo\":\"" + CARD_TWO + "\",\"cvc\":123}",
                "{\"cardNo\":\"" + CARD_TWO + "\",\"cvc\":null,\"cvc\":\"" + canary + "\"}");
            s.observed.reset();
            try (var logs = new ErrorCapture()) {
                for (String method : List.of("POST", "PATCH")) {
                    String access = s.access(method.equals("POST") ? createOwner : updateOwner);
                    for (String body : bodies) {
                        var before = allRows(s.jdbc);
                        var response = s.mutate(method, access, body);
                        assertRejected(response.getResponse().getStatus(), response.getResponse().getContentAsString(), canary);
                        assertThat(before.equals(allRows(s.jdbc)))
                            .as("Each rejected CVC field preserves every committed row and column").isTrue();
                        s.observed.assertNoSideEffects();
                    }
                }
                assertThat(logs.hasNoCanary(canary)).as("Rejected CVC input absent from normal output and exception causes").isTrue();
            }
            evidence("cvc_field_rejected_at_request_boundary", Map.of("rejectedRequests", bodies.size() * 2,
                "httpStatus", 400, "databaseUnchanged", true, "downstreamCalls", 0, "inputEchoAbsent", true));
        }
    }

    @Test
    void trailingJsonOnPostAndPatchOverActualLoopbackHttpIs400WithoutSideEffectsOrEcho() throws Exception {
        try (var s = new OAuthFixture()) {
            User createOwner = s.user();
            User updateOwner = s.user();
            assertCardResponse(s.mutate("POST", s.access(updateOwner), cardBody(CARD_ONE)), 201, CARD_ONE);
            assertScopedTrailingTokenCheck(s);
            String canary = "synthetic-trailing-" + UUID.randomUUID();
            List<String> suffixes = List.of("{\"marker\":\"" + canary + "\"}", "[\"" + canary + "\"]",
                "true", "123", "null", "\"" + canary + "\"", canary);
            s.observed.reset();
            var factory = new TomcatServletWebServerFactory();
            factory.setAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
            factory.setPort(0);
            factory.setContextPath("/api");
            var server = factory.getWebServer(context -> {
                var dispatcher = new DispatcherServlet();
                dispatcher.setApplicationContext((WebApplicationContext) s.started.context());
                var servlet = context.addServlet("cardCvcContractDispatcher", dispatcher);
                servlet.addMapping("/");
                servlet.setLoadOnStartup(1);
                var filter = context.addFilter("cardCvcContractSecurity",
                    s.started.context().getBean("springSecurityFilterChain", Filter.class));
                filter.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
            });
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
                }).build(); var logs = new ErrorCapture()) {
                server.start();
                URI endpoint = URI.create("http://127.0.0.1:" + server.getPort() + "/api/cards");
                for (String method : List.of("POST", "PATCH")) {
                    String access = s.access(method.equals("POST") ? createOwner : updateOwner);
                    for (String suffix : suffixes) {
                        for (String contentType : List.of("application/json", "application/vnd.moneytoad+json")) {
                            var before = allRows(s.jdbc);
                            var response = client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access).header(HttpHeaders.CONTENT_TYPE, contentType)
                                .method(method, HttpRequest.BodyPublishers.ofString(cardBody(CARD_TWO) + " " + suffix)).build(),
                                HttpResponse.BodyHandlers.ofString());
                            assertRejected(response.statusCode(), response.body(), canary);
                            assertThat(before.equals(allRows(s.jdbc)))
                                .as("Each trailing JSON rejection preserves every committed row and column").isTrue();
                            s.observed.assertNoSideEffects();
                        }
                    }
                    var valid = client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + access).header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(cardBody(CARD_TWO) + " \n\t")).build(),
                        HttpResponse.BodyHandlers.ofString());
                    assertThat(valid.statusCode()).as("Single JSON value plus whitespace remains valid").isEqualTo(method.equals("POST") ? 201 : 200);
                    assertThat(hasCvcProperty(responseJson(valid.body()))).isFalse();
                    s.observed.assertWorkflowCalls(1);
                    s.observed.reset();
                }
                assertThat(logs.hasNoCanary(canary)).as("Trailing input absent from normal application output").isTrue();
            } finally {
                try { server.stop(); }
                finally { server.destroy(); }
            }
            evidence("card_trailing_json_loopback_http", Map.of("rejectedRequests", suffixes.size() * 4,
                "validWhitespaceRequests", 2, "rejectionStatus", 400, "databaseUnchangedOnReject", true, "inputEchoAbsent", true));
        }
    }

    @Test
    void anonymousCardMutationsStillStopAtTheActualJwtFilterWithoutSideEffects() throws Exception {
        try (var s = new OAuthFixture()) {
            s.user();
            var before = allRows(s.jdbc);
            s.observed.reset();
            for (String method : List.of("POST", "PATCH")) {
                var response = s.mvc.perform(request(method).content(cardBody(CARD_ONE))).andReturn();
                assertThat(response.getResponse().getStatus()).isEqualTo(401);
                assertThat(response.getHandler()).isNull();
                assertThat(before.equals(allRows(s.jdbc))).isTrue();
                s.observed.assertNoSideEffects();
            }
        }
    }

    @Test
    void actualLegacyCvcMakesSeedAnchorAndRepeatInstallRejectWithoutRepairOrMutation() throws Exception {
        try (var s = new DemoChartSeedTestSupport()) {
            User owner = s.users.saveAndFlush(User.createUser("seed-cvc-" + UUID.randomUUID() + "@example.invalid", "Synthetic seed check"));
            s.transaction.executeWithoutResult(status -> s.seed.install(owner, ANCHOR));
            s.assertOwnedCounts(owner.getId());
            assertThat(s.seed.requireAnchorMonth(owner.getId())).isEqualTo(YearMonth.of(2024, 2));
            String canary = "synthetic-seed-legacy-" + UUID.randomUUID();
            for (String legacy : List.of("", canary)) {
                assertThat(s.jdbc.update("UPDATE cards SET cvc=? WHERE user_id=?", legacy, owner.getId())).isEqualTo(1);
                var before = allRows(s.jdbc);
                int cardInserts = s.observations.cardInserts.get();
                int transactionInserts = s.observations.transactionInserts.get();
                int budgetInserts = s.observations.budgetInserts.get();
                try (var logs = new ErrorCapture()) {
                    assertSeedRejected(() -> s.seed.requireAnchorMonth(owner.getId()));
                    assertThat(before.equals(allRows(s.jdbc))).as("Anchor validation does not change a legacy row").isTrue();
                    assertSeedRejected(() -> s.transaction.executeWithoutResult(status -> s.seed.install(owner, ANCHOR.plusMonths(1))));
                    assertThat(before.equals(allRows(s.jdbc))).as("Repeat installation never repairs or erases legacy CVC").isTrue();
                    assertThat(logs.hasNoCanary(canary)).isTrue();
                }
                assertThat(s.observations.cardInserts.get()).isEqualTo(cardInserts);
                assertThat(s.observations.transactionInserts.get()).isEqualTo(transactionInserts);
                assertThat(s.observations.budgetInserts.get()).isEqualTo(budgetInserts);
            }
            s.evidence("legacy_cvc_seed_rejected", Map.of("anchorRejected", true, "repeatInstallRejected", true,
                "databaseUnchanged", true, "legacyValuePreserved", true, "emptyAndNonemptyLegacyRejected", true));
        }
    }

    private static String cardBody(String number) throws Exception {
        return DemoAuthHttpTestSupport.JSON.writeValueAsString(Map.of("cardNo", number));
    }

    private static MockHttpServletRequestBuilder request(String method) {
        return (method.equals("POST") ? post("/api/cards") : patch("/api/cards"))
            .contextPath("/api").contentType("application/json");
    }

    private static void assertCardResponse(MvcResult response, int status, String number) throws Exception {
        assertThat(response.getResponse().getStatus()).isEqualTo(status);
        JsonNode body = responseJson(response.getResponse().getContentAsString());
        assertThat(body.size()).as("Card response contains only cardId and cardNo").isEqualTo(2);
        assertThat(body.path("cardId").asLong()).isPositive();
        assertThat(body.path("cardNo").asText().equals(number)).as("Requested synthetic card number retained").isTrue();
        assertThat(hasCvcProperty(body)).as("No CVC JSON property, including null-valued properties").isFalse();
    }

    private static void assertRejected(int status, String response, String canary) throws Exception {
        assertThat(status).as("Disallowed card body rejected before controller side effects").isEqualTo(400);
        var error = responseJson(response);
        assertThat(error.path("status").asInt()).isEqualTo(400);
        assertThat(error.path("message").asText().equals("요청 본문 형식이 올바르지 않습니다."))
            .as("Error response uses the fixed unreadable-body message").isTrue();
        assertThat(!response.contains(canary)).as("Rejected input absent from public response").isTrue();
    }

    private static JsonNode responseJson(String response) {
        try { return DemoAuthHttpTestSupport.JSON.readTree(response); }
        catch (IOException failure) { throw new AssertionError("CARD_RESPONSE_JSON_REQUIRED"); }
    }

    private static void assertSeedRejected(Runnable action) {
        RuntimeException rejected = null;
        try { action.run(); }
        catch (RuntimeException failure) { rejected = failure; }
        assertThat(rejected instanceof IllegalArgumentException).as("Invalid seed fails with the expected exception type").isTrue();
        assertThat(rejected != null && "DEMO_SEED_INCOMPLETE".equals(rejected.getMessage()))
            .as("Invalid seed uses a fixed value-free error code").isTrue();
    }

    private static boolean hasCvcProperty(JsonNode value) {
        if (value == null) return false;
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (field.getKey().toLowerCase(Locale.ROOT).contains("cvc") || hasCvcProperty(field.getValue())) return true;
            }
        } else if (value.isArray()) {
            for (var child : value) if (hasCvcProperty(child)) return true;
        }
        return false;
    }

    private static Map<String, List<Map<String, Object>>> allRows(JdbcTemplate jdbc) {
        var rows = new LinkedHashMap<>(DemoAuthHttpTestSupport.snapshot(jdbc));
        rows.put("dummy", jdbc.queryForList("SELECT * FROM dummy ORDER BY id"));
        rows.put("peer_transaction_stats", jdbc.queryForList("SELECT * FROM peer_transaction_stats ORDER BY id"));
        return rows;
    }

    private static Map<String, List<Map<String, Object>>> withCardNumber(
        Map<String, List<Map<String, Object>>> before, long owner, String cardNo) {
        var expected = new LinkedHashMap<String, List<Map<String, Object>>>();
        before.forEach((table, rows) -> {
            var copies = new ArrayList<Map<String, Object>>();
            for (var row : rows) {
                var copy = new LinkedHashMap<>(row);
                if (table.equals("cards") && ((Number) row.get("user_id")).longValue() == owner) copy.put("card_no", cardNo);
                copies.add(copy);
            }
            expected.put(table, copies);
        });
        return expected;
    }

    private static void assertScopedTrailingTokenCheck(OAuthFixture s) {
        int scopedMappers = 0;
        for (var converter : s.started.context().getBean(RequestMappingHandlerAdapter.class).getMessageConverters()) {
            if (converter instanceof MappingJackson2HttpMessageConverter json) {
                assertThat(json.getObjectMapper().isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS))
                    .as("Unrelated request mapper contract stays unchanged").isFalse();
                // Spring Data may contribute a projection-only Jackson converter that cannot read
                // this concrete DTO. Require scoped strict mapping on every converter that can.
                for (var mediaType : json.getSupportedMediaTypes()) {
                    if (!json.canRead(CardRequest.class, mediaType)) continue;
                    var scoped = json.getObjectMappersForType(CardRequest.class);
                    assertThat(scoped).isNotEmpty();
                    var mapper = scoped.get(mediaType);
                    assertThat(mapper != null && mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)).isTrue();
                    scopedMappers++;
                }
            }
        }
        assertThat(scopedMappers).isPositive();
    }

    private static void evidence(String scenario, Map<String, Object> facts) throws Exception {
        var row = new LinkedHashMap<String, Object>();
        row.put("scenario", scenario);
        row.putAll(facts);
        System.out.println("CARD_CVC_CONTRACT_EVIDENCE " + DemoAuthHttpTestSupport.JSON.writeValueAsString(row));
    }

    private static final class OAuthFixture implements AutoCloseable {
        final DemoAuthHttpTestSupport support = new DemoAuthHttpTestSupport();
        final DemoAuthHttpTestSupport.Started started;
        final JdbcTemplate jdbc;
        final MockMvc mvc;
        final ObjectMapper mapper;
        final CardRepository cards;
        final UserRepository users;
        final JwtUtil jwt;
        final Observations observed;
        final TransactionTemplate transaction;

        OAuthFixture() throws Exception {
            started = support.start("", "", "false", "standard", true, Map.of(), BoundaryConfiguration.class);
            if (started.failure() != null) {
                support.close();
                throw new AssertionError("Owned OAuth product context must start");
            }
            try {
                var context = started.context();
                jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                mvc = DemoAuthHttpIntegrationTest.mvc(started);
                mapper = context.getBean(ObjectMapper.class);
                cards = context.getBean(CardRepository.class);
                users = context.getBean(UserRepository.class);
                jwt = context.getBean(JwtUtil.class);
                observed = context.getBean(Observations.class);
                transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                assertThat(context.getBean(SecurityFilterChain.class).getFilters()).contains(context.getBean(JwtAuthenticationFilter.class));
                assertThat(context.getBean(ClientRegistrationRepository.class).findByRegistrationId("ssafy") != null).isTrue();
                assertThat(AopUtils.getTargetClass(context.getBean(CardService.class))).isEqualTo(CardService.class);
                assertThat(AopUtils.getTargetClass(context.getBean(CsvService.class))).isEqualTo(CsvService.class);
            } catch (RuntimeException | Error failure) {
                try { started.close(); } finally { support.close(); }
                throw failure;
            }
        }

        User user() { return users.saveAndFlush(User.createUser("card-check-" + UUID.randomUUID() + "@example.invalid", "Synthetic card check")); }
        String access(User user) { return jwt.createAccessToken(user.getId(), user.getEmail()); }
        MvcResult mutate(String method, String access, String body) throws Exception {
            return mvc.perform(request(method).header(HttpHeaders.AUTHORIZATION, "Bearer " + access).content(body)).andReturn();
        }
        MvcResult getCard(String access) throws Exception {
            return mvc.perform(get("/api/cards").contextPath("/api")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access)).andReturn();
        }
        @Override public void close() {
            try { assertThat(support.noOutbound(started.probe())).as("No OAuth or AI network request attempted").isTrue(); }
            finally { try { started.close(); } finally { support.close(); } }
        }
    }

    /** Counts SQL shape only; no SQL text, request values, bind values, token or identity is retained. */
    static final class Observations implements StatementInspector {
        private static final Pattern CVC_WORD = Pattern.compile("\\bcvc\\b");
        private static final Pattern CARD_INSERT = Pattern.compile("^insert into cards\\s*\\(([^)]*)\\)");
        private static final Pattern CARD_UPDATE = Pattern.compile("^update cards set (.*?) where\\b");
        final AtomicInteger cardInserts = new AtomicInteger();
        final AtomicInteger cardUpdates = new AtomicInteger();
        final AtomicInteger cvcWrites = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();
        final AtomicInteger dummyCalls = new AtomicInteger();
        final AtomicInteger csvUploadCalls = new AtomicInteger();
        final AtomicInteger csvAnalysisCalls = new AtomicInteger();
        final AtomicInteger jobCalls = new AtomicInteger();

        @Override public String inspect(String sql) {
            String normalized = sql.replace("`", "").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            if (normalized.matches("^(insert|update|delete)\\b.*")) writes.incrementAndGet();
            var insert = CARD_INSERT.matcher(normalized);
            if (insert.find()) {
                cardInserts.incrementAndGet();
                if (CVC_WORD.matcher(insert.group(1)).find()) cvcWrites.incrementAndGet();
            }
            var update = CARD_UPDATE.matcher(normalized);
            if (update.find()) {
                cardUpdates.incrementAndGet();
                if (CVC_WORD.matcher(update.group(1)).find()) cvcWrites.incrementAndGet();
            }
            return sql;
        }
        void reset() {
            for (var counter : List.of(cardInserts, cardUpdates, cvcWrites, writes,
                dummyCalls, csvUploadCalls, csvAnalysisCalls, jobCalls)) counter.set(0);
        }
        void assertWorkflowCalls(int expected) {
            assertThat(dummyCalls.get()).isEqualTo(expected);
            assertThat(csvUploadCalls.get()).isEqualTo(expected);
            assertThat(csvAnalysisCalls.get()).isEqualTo(expected);
            assertThat(jobCalls.get()).isEqualTo(expected);
        }
        void assertNoSideEffects() { assertThat(writes.get()).isZero(); assertWorkflowCalls(0); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BoundaryConfiguration {
        @Bean Observations cardCvcObservations() { return new Observations(); }
        @Bean HibernatePropertiesCustomizer cardCvcStatementInspector(Observations observed) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", observed);
        }
        @Bean static BeanPostProcessor cardCvcExternalBoundary(Observations observed) {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    String type = AopUtils.getTargetClass(bean).getSimpleName();
                    if (!Set.of("DummyService", "CsvClient", "AnalysisJobService").contains(type)) return bean;
                    var proxy = new ProxyFactory(bean);
                    proxy.setProxyTargetClass(true);
                    proxy.addAdvice((MethodInterceptor) invocation -> {
                        if (invocation.getMethod().getDeclaringClass() == Object.class) return invocation.proceed();
                        String method = invocation.getMethod().getName();
                        if (type.equals("DummyService") && method.equals("populateFromPool")) {
                            observed.dummyCalls.incrementAndGet();
                            return null;
                        }
                        if (type.equals("CsvClient") && method.equals("uploadCsv")) {
                            observed.csvUploadCalls.incrementAndGet();
                            var response = new CsvUploadResponse();
                            response.setFileId("synthetic-cvc-contract-file");
                            return response;
                        }
                        if (type.equals("CsvClient") && method.equals("triggerAnalysis")) {
                            observed.csvAnalysisCalls.incrementAndGet();
                            var response = new AnalysisTriggerResponse();
                            response.setFileId("synthetic-cvc-contract-file");
                            return response;
                        }
                        if (type.equals("AnalysisJobService") && method.equals("enqueue")) {
                            observed.jobCalls.incrementAndGet();
                            return null;
                        }
                        throw new AssertionError("CARD_CVC_TEST_UNEXPECTED_DOWNSTREAM_CALL");
                    });
                    return proxy.getProxy();
                }
            };
        }
    }

    /** Capture normal INFO/WARN/ERROR output without ever printing request values or exception causes. */
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
                || throwableContains(event.getThrowableProxy(), canary));
        }
        private static boolean throwableContains(IThrowableProxy failure, String canary) {
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
            captured.list.clear();
            root.setLevel(previous);
            appenders.forEach(root::addAppender);
        }
    }
}
