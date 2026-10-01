package com.potg.don.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.potg.don.auth.demo.DemoAbuseLimiter;
import com.potg.don.auth.demo.DemoAuthService;
import com.potg.don.auth.demo.DemoReadinessService;
import com.potg.don.auth.demo.DemoSessionGuard;
import com.potg.don.auth.demo.DemoSessionStore;
import com.potg.don.auth.jwt.JwtUtil;
import com.potg.don.demo.seed.DemoSeedService;

/** Real ConfigData, security, admission/JPA/MySQL and Redis; observers never replace their results. */
@Execution(ExecutionMode.SAME_THREAD)
class DemoAbuseGuardIntegrationTest {
    private static final String ORIGIN = "https://demo.example.invalid";

    @Test void gatewayCoversEveryApiRouteBeforeCorsJwtAndDependenciesWithOnlyExactGetLivenessExempt() throws Exception {
        try (var f = new Fixture(false)) {
            var visitor = f.login();
            var database = f.snapshot();
            var paths = List.of("/auth/demo/login", "/auth/demo/reissue", "/auth/demo/session", "/auth/demo/logout",
                "/auth/demo/ready", "/users", "/unknown", "/v3/api-docs", "/test/nested", "/error");
            for (String path : paths) {
                for (String method : List.of("GET", "POST", "OPTIONS")) {
                    f.observed.clearCalls();
                    var request = request(org.springframework.http.HttpMethod.valueOf(method), "/api" + path)
                        .contextPath("/api").header("Authorization", "Bearer " + visitor.access())
                        .header("X-Forwarded-For", "192.0.2.37").header("CF-Connecting-IP", "192.0.2.37")
                        .header("X-MoneyToad-Client-IP", "192.0.2.37");
                    assertError(f.mvc.perform(request).andReturn(), 403, "DEMO_GATEWAY_REJECTED");
                    f.observed.noDependencies();
                }
            }
            for (String method : List.of("HEAD", "POST", "OPTIONS")) {
                f.observed.clearCalls();
                var response = f.mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), "/api/test")
                    .contextPath("/api")).andReturn().getResponse();
                assertThat(response.getStatus()).isEqualTo(403);
                f.observed.noDependencies();
            }
            for (String[] values : List.of(new String[] {"invalid"},
                new String[] {SyntheticGatewayTestSupport.secret(), SyntheticGatewayTestSupport.secret()},
                new String[] {SyntheticGatewayTestSupport.secret() + "," + SyntheticGatewayTestSupport.secret()})) {
                f.observed.clearCalls();
                assertError(f.mvc.perform(get("/api/users").contextPath("/api")
                    .header("Authorization", "Bearer " + visitor.access())
                    .header("X-MoneyToad-Gateway", (Object[]) values)).andReturn(), 403, "DEMO_GATEWAY_REJECTED");
                f.observed.noDependencies();
            }
            f.observed.clearCalls();
            var alive = f.mvc.perform(get("/api/test").contextPath("/api")).andReturn().getResponse();
            assertThat(alive.getStatus()).isEqualTo(200);
            assertThat(alive.getContentAsString()).isEqualTo("Hello World");
            f.observed.noDependencies();
            assertThat(database.equals(f.snapshot())).isTrue();
            assertThat(f.mvc.perform(f.gateway(get("/api/users").contextPath("/api"))
                .header("Authorization", "Bearer " + visitor.access())).andReturn().getResponse().getStatus()).isEqualTo(200);
        }
    }

    @Test void actualFilterAndInterceptorOrderingIsSingleRegistrationAndCorsKeepsInternalHeadersPrivate() throws Exception {
        try (var f = new Fixture(false)) {
            var context = f.started.context();
            var chains = context.getBeansOfType(SecurityFilterChain.class).values();
            assertThat(chains).hasSize(1);
            var filters = chains.iterator().next().getFilters().stream().map(filter -> filter.getClass().getSimpleName()).toList();
            assertThat(filters.stream().filter("DemoGatewayFilter"::equals).count()).isEqualTo(1);
            assertThat(filters.indexOf("DemoGatewayFilter")).isLessThan(filters.indexOf("CorsFilter"));
            assertThat(filters.indexOf("DemoGatewayFilter")).isLessThan(filters.indexOf("JwtAuthenticationFilter"));
            assertThat(context.getBeansOfType(Filter.class).values().stream()
                .filter(filter -> filter.getClass().getSimpleName().equals("DemoGatewayFilter")).count()).isZero();
            var mappingRequest = f.post("login").buildRequest(((GenericWebApplicationContext) context).getServletContext());
            org.springframework.web.util.ServletRequestPathUtils.parseAndCache(mappingRequest);
            var chain = context.getBean(RequestMappingHandlerMapping.class).getHandler(mappingRequest);
            assertThat(chain).isNotNull();
            var interceptors = java.util.Arrays.stream(chain.getInterceptors()).map(item -> item.getClass().getSimpleName()).toList();
            assertThat(interceptors.indexOf("DemoAuthRequestInterceptor")).isGreaterThanOrEqualTo(0)
                .isLessThan(interceptors.indexOf("DemoAbuseRequestInterceptor"));
            f.observed.clearCalls();
            var result = f.mvc.perform(f.gateway(options("/api/auth/demo/login").contextPath("/api"))
                .header("Origin", ORIGIN).header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type,X-MoneyToad-Demo")).andReturn().getResponse();
            assertThat(result.getStatus()).isEqualTo(200);
            assertThat(result.getHeader("Access-Control-Allow-Origin")).isEqualTo(ORIGIN);
            assertThat(result.getHeader("Access-Control-Expose-Headers")).contains("Retry-After")
                .doesNotContain("X-MoneyToad-Gateway", "X-MoneyToad-Client-IP");
            f.observed.noDependencies();
            for (String header : List.of("X-MoneyToad-Gateway", "X-MoneyToad-Client-IP")) {
                var rejected = f.mvc.perform(f.gateway(options("/api/auth/demo/login").contextPath("/api"))
                    .header("Origin", ORIGIN).header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", header)).andReturn().getResponse();
                assertThat(rejected.getStatus()).isEqualTo(403);
            }
        }
    }

    @Test void invalidHttpAndClientAddressDoNotConsumeQuotaOrReadTheBodyTwice() throws Exception {
        try (var f = new Fixture(false)) {
            var database = f.snapshot();
            for (int i = 0; i < 8; i++) {
                var request = f.post("login").content("{\"userId\":1}");
                assertThat(f.mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(400);
                request = f.post("login").with(value -> { value.removeHeader("Origin"); return value; });
                assertThat(f.mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(403);
            }
            for (String input : List.of("", "192.0.2.1,192.0.2.2", "example.invalid", "192.0.2.1:80", "192.0.2.1/24", "fe80::1%en0", "300.1.1.1")) {
                f.observed.clearCalls();
                assertError(f.mvc.perform(f.post("login").with(request -> {
                    request.removeHeader("X-MoneyToad-Client-IP");
                    if (!input.isEmpty()) request.addHeader("X-MoneyToad-Client-IP", input);
                    return request;
                })).andReturn(), 403, "DEMO_CLIENT_ADDRESS_REJECTED");
                f.observed.noDependencies();
            }
            assertError(f.mvc.perform(f.post("login").header("X-MoneyToad-Client-IP", "192.0.2.38")).andReturn(),
                403, "DEMO_CLIENT_ADDRESS_REJECTED");
            assertThat(database.equals(f.snapshot())).isTrue();
            // Body validation leaves the existing no-@RequestBody controller contract intact.
            f.login();
            for (int i = 0; i < 4; i++) {
                assertThat(f.mvc.perform(f.post("login").cookie(new Cookie("demoRefreshToken", "synthetic-malformed")))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
            }
            var before = f.snapshot();
            f.observed.clearCalls();
            assertError(f.mvc.perform(f.post("login")).andReturn(), 429, "DEMO_LOGIN_RATE_LIMITED");
            f.observed.noDependencies();
            assertThat(before.equals(f.snapshot())).isTrue();
        }
    }

    @Test void loginRateLimitCreatesNothingAndDoesNotBlockExistingVisitorRestoreReadOrLogout() throws Exception {
        try (var f = new Fixture(false)) {
            var visitor = f.login();
            for (int i = 0; i < 4; i++) {
                assertThat(f.mvc.perform(f.post("login").cookie(visitor.cookie())).andReturn().getResponse().getStatus()).isEqualTo(409);
            }
            var before = f.snapshot();
            f.observed.clearCalls();
            var rejected = f.mvc.perform(f.post("login")).andReturn();
            assertError(rejected, 429, "DEMO_LOGIN_RATE_LIMITED");
            f.observed.noDependencies();
            assertThat(before.equals(f.snapshot())).isTrue();
            f.counts(1);
            assertThat(f.observed.ownedSids.size()).isEqualTo(1);
            assertThat(f.mvc.perform(f.gateway(get("/api/auth/demo/session").contextPath("/api"))
                .header("Authorization", "Bearer " + visitor.access())).andReturn().getResponse().getStatus()).isEqualTo(200);
            assertThat(f.mvc.perform(f.gateway(get("/api/users").contextPath("/api"))
                .header("Authorization", "Bearer " + visitor.access())).andReturn().getResponse().getStatus()).isEqualTo(200);
            var rotated = f.mvc.perform(f.post("reissue").cookie(visitor.cookie())).andReturn();
            assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
            String access = DemoAuthHttpTestSupport.JSON.readTree(rotated.getResponse().getContentAsString()).path("accessToken").asText();
            assertThat(f.mvc.perform(f.post("logout").header("Authorization", "Bearer " + access))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
            assertThat(f.mvc.perform(f.gateway(get("/api/auth/demo/session").contextPath("/api"))
                .header("Authorization", "Bearer " + access)).andReturn().getResponse().getStatus()).isEqualTo(401);
            assertThat(before.equals(f.snapshot())).isTrue();
            f.clock().addAndGet(java.time.Duration.ofMinutes(1).toNanos());
            f.login();
            f.counts(2);
        }
    }

    @Test void readinessHasItsOwnSixtyRequestBucketAndRejectedProbesTouchNoSqlOrRedis() throws Exception {
        try (var f = new Fixture(false)) {
            f.observed.clearCalls();
            for (int i = 0; i < 60; i++) {
                var response = f.mvc.perform(f.gateway(get("/api/auth/demo/ready").contextPath("/api"))).andReturn().getResponse();
                assertThat(response.getStatus()).isEqualTo(200);
                assertThat(response.getContentAsString()).isEqualTo("{\"ready\":true}");
            }
            assertThat(f.observed.readyCalls.get()).isEqualTo(60);
            assertThat(f.observed.sqlConnections.get()).isPositive();
            assertThat(f.observed.redisConnections.get()).isPositive();
            f.observed.clearCalls();
            assertError(f.mvc.perform(f.gateway(get("/api/auth/demo/ready").contextPath("/api"))).andReturn(),
                429, "DEMO_READINESS_RATE_LIMITED");
            f.observed.noDependencies();
            f.observed.clearCalls();
            var headResponse = f.mvc.perform(f.gateway(head("/api/auth/demo/ready").contextPath("/api")))
                .andReturn().getResponse();
            // Existing security rejects unauthenticated HEAD before MVC; it performs no probe.
            assertThat(headResponse.getStatus()).isEqualTo(401);
            f.observed.noDependencies();
            f.login(); // Readiness attempts do not consume login's five-per-minute budget.
            f.clock().addAndGet(java.time.Duration.ofMinutes(1).toNanos());
            assertThat(f.mvc.perform(f.gateway(get("/api/auth/demo/ready").contextPath("/api")))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        }
    }

    @Test void admissionFullAndBusyKeepTheirExisting503ContractsAndDoNotRefundAllowedAttempts() throws Exception {
        try (var f = new Fixture(true)) {
            try (Connection admin = f.dataSource.getConnection()) {
                admin.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                admin.setAutoCommit(false);
                try (var lock = admin.prepareStatement("SELECT id FROM demo_admission_lock WHERE id=1 FOR UPDATE NOWAIT")) {
                    lock.executeQuery().close();
                }
                var before = f.snapshot();
                assertError(f.mvc.perform(f.post("login")).andReturn(), 503, "DEMO_ADMISSION_BUSY");
                assertThat(before.equals(f.snapshot())).isTrue();
                assertThat(f.observed.ownedSids.isEmpty()).isTrue();
                admin.rollback();
            }
            f.login();
            for (int i = 0; i < 3; i++) assertError(f.mvc.perform(f.post("login")).andReturn(), 503, "DEMO_CAPACITY_FULL");
            var before = f.snapshot();
            f.observed.clearCalls();
            assertError(f.mvc.perform(f.post("login")).andReturn(), 429, "DEMO_LOGIN_RATE_LIMITED");
            f.observed.noDependencies();
            assertThat(before.equals(f.snapshot())).isTrue();
            f.counts(1);
        }
    }

    private static void assertError(MvcResult result, int status, String code) throws Exception {
        var response = result.getResponse();
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        var body = DemoAuthHttpTestSupport.JSON.readTree(response.getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.has("accessToken")).isFalse();
        assertThat(response.getContentAsString().contains(SyntheticGatewayTestSupport.secret())).isFalse();
        if (status == 429) {
            assertThat(response.getHeader("Retry-After")).matches("[1-9][0-9]{0,3}");
            assertThat(Integer.parseInt(response.getHeader("Retry-After"))).isBetween(1, 3600);
        }
    }

    private record Visitor(String access, String refresh) {
        Cookie cookie() { return new Cookie("demoRefreshToken", refresh); }
        @Override public String toString() { return "Visitor[redacted]"; }
    }
    private static final class Fixture implements AutoCloseable {
        final DemoAuthHttpTestSupport support = new DemoAuthHttpTestSupport();
        final DemoAuthHttpTestSupport.Started started;
        final MockMvc mvc;
        final DataSource dataSource;
        final JdbcTemplate jdbc;
        final Observations observed;
        final ch.qos.logback.classic.Logger appLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.potg.don");
        final ch.qos.logback.classic.Level previousLevel = appLogger.getLevel();
        final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> capturedLogs =
            new ch.qos.logback.core.read.ListAppender<>();
        Fixture(boolean capacityOne) throws Exception {
            started = support.start("demo", "", "true", "public-demo", false,
                capacityOne ? Map.of("app.demo.max-visitors", 1, "test.abuse.capacity-one", "true") : Map.of(),
                ObservationConfiguration.class);
            if (started.failure() != null) {
                started.close(); support.close();
                var types = new ArrayList<String>();
                for (Throwable cause = started.failure(); cause != null; cause = cause.getCause())
                    types.add(cause.getClass().getSimpleName());
                throw new AssertionError("Owned public-demo context failed: " + String.join("/", types));
            }
            dataSource = started.context().getBean(DataSource.class);
            jdbc = new JdbcTemplate(dataSource);
            mvc = MockMvcBuilders.webAppContextSetup((GenericWebApplicationContext) started.context()).apply(springSecurity()).build();
            observed = started.context().getBean(Observations.class);
            capturedLogs.start();
            appLogger.addAppender(capturedLogs);
            appLogger.setLevel(ch.qos.logback.classic.Level.INFO);
        }
        AtomicLong clock() { return started.context().getBean(AtomicLong.class); }
        MockHttpServletRequestBuilder gateway(MockHttpServletRequestBuilder request) { return SyntheticGatewayTestSupport.gateway(request); }
        MockHttpServletRequestBuilder post(String endpoint) {
            return gateway(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/demo/" + endpoint)
                .contextPath("/api").header("Origin", ORIGIN).header("X-MoneyToad-Demo", "1")
                .contentType("application/json").content("{}"));
        }
        Visitor login() throws Exception {
            var result = mvc.perform(post("login")).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            return new Visitor(DemoAuthHttpTestSupport.JSON.readTree(result.getResponse().getContentAsString())
                .path("accessToken").asText(), result.getResponse().getCookie("demoRefreshToken").getValue());
        }
        Map<String, List<Map<String, Object>>> snapshot() { return DemoAuthHttpTestSupport.snapshot(jdbc); }
        void counts(int visitors) {
            for (var expected : Map.of("users", visitors, "cards", visitors, "transactions", visitors * 240,
                "budgets", visitors * 72, "demo_visit", visitors).entrySet()) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + expected.getKey(), Integer.class)).isEqualTo(expected.getValue());
            }
        }
        @Override public void close() {
            try {
                var store = started.context().getBean(DemoSessionStore.class);
                for (String sid : observed.ownedSids) {
                    store.revoke(sid);
                    assertThat(store.findActive(sid).isEmpty()).isTrue();
                }
                assertThat(support.noOutbound(started.probe())).isTrue();
                assertThat(capturedLogs.list.stream().noneMatch(event -> event.getFormattedMessage().contains(SyntheticGatewayTestSupport.secret())
                    || event.getFormattedMessage().contains("192.0.2.37"))).as("Gateway material and client address remain absent from captured application logs").isTrue();
            } finally {
                appLogger.setLevel(previousLevel); appLogger.detachAppender(capturedLogs); capturedLogs.stop();
                started.close(); support.close();
            }
        }
    }

    static final class Observations {
        final AtomicInteger sqlConnections = new AtomicInteger();
        final AtomicInteger redisConnections = new AtomicInteger();
        final AtomicInteger guards = new AtomicInteger();
        final AtomicInteger authCalls = new AtomicInteger();
        final AtomicInteger seedCalls = new AtomicInteger();
        final AtomicInteger sessionCreates = new AtomicInteger();
        final AtomicInteger readyCalls = new AtomicInteger();
        final List<String> ownedSids = new CopyOnWriteArrayList<>();
        void clearCalls() {
            sqlConnections.set(0); redisConnections.set(0); guards.set(0); authCalls.set(0);
            seedCalls.set(0); sessionCreates.set(0); readyCalls.set(0);
        }
        void noDependencies() {
            assertThat(sqlConnections.get()).isZero(); assertThat(redisConnections.get()).isZero();
            assertThat(guards.get()).isZero(); assertThat(authCalls.get()).isZero(); assertThat(seedCalls.get()).isZero();
            assertThat(sessionCreates.get()).isZero(); assertThat(readyCalls.get()).isZero();
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class ObservationConfiguration {
        @Bean AtomicLong abuseClock() { return new AtomicLong(); }
        @Bean @Primary DemoAbuseLimiter fixtureAbuseLimiter(AtomicLong clock) { return new DemoAbuseLimiter(clock::get); }
        @Bean Observations abuseObservations() { return new Observations(); }
        @Bean static BeanPostProcessor capacityFixture(org.springframework.core.env.Environment environment) {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof JdbcTemplate jdbc && environment.getProperty("test.abuse.capacity-one", Boolean.class, false))
                        jdbc.update("UPDATE demo_capacity SET max_visitors=1 WHERE id=1");
                    return bean;
                }
            };
        }
        @Bean static BeanPostProcessor dependencyObservations(Observations observations) {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    boolean interfaceBean = bean instanceof DataSource || bean instanceof RedisConnectionFactory;
                    if (!interfaceBean && !(bean instanceof DemoSessionStore) && !(bean instanceof DemoSessionGuard)
                        && !(bean instanceof DemoAuthService) && !(bean instanceof DemoSeedService) && !(bean instanceof DemoReadinessService)) return bean;
                    ProxyFactory proxy = new ProxyFactory(bean);
                    proxy.setProxyTargetClass(!interfaceBean);
                    proxy.addAdvice((MethodInterceptor) call -> {
                        String method = call.getMethod().getName();
                        if (bean instanceof DataSource && method.equals("getConnection")) observations.sqlConnections.incrementAndGet();
                        if (bean instanceof RedisConnectionFactory && (method.equals("getConnection") || method.equals("getReactiveConnection"))) observations.redisConnections.incrementAndGet();
                        if (bean instanceof DemoSessionGuard && method.equals("requireActive")) observations.guards.incrementAndGet();
                        if (bean instanceof DemoAuthService && List.of("login", "reissue", "logout", "session").contains(method)) observations.authCalls.incrementAndGet();
                        if (bean instanceof DemoSeedService && method.equals("install")) observations.seedCalls.incrementAndGet();
                        if (bean instanceof DemoReadinessService && method.equals("ready")) observations.readyCalls.incrementAndGet();
                        if (bean instanceof DemoSessionStore && method.equals("create")) {
                            observations.sessionCreates.incrementAndGet(); observations.ownedSids.add((String) call.getArguments()[0]);
                        }
                        return call.proceed();
                    });
                    return proxy.getProxy();
                }
            };
        }
    }
}
