package com.potg.don.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import com.potg.don.auth.jwt.JwtUtil;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;
import javax.sql.DataSource;

class DemoAuthHttpIntegrationTest {
    private static final String ORIGIN = "http://localhost:5173";

    @Test
    void loginPublishesAccessOnlyAfterCreatingVisitor() throws Exception {
        try (var support = new DemoAuthHttpTestSupport(); var started = support.startDemo()) {
            assertThat(started.failure() == null).isTrue();
            var context = started.context();
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            var before = DemoAuthHttpTestSupport.snapshot(jdbc);
            var result = mvc(started).perform(post("/api/auth/demo/login").contextPath("/api")
                .header("Origin", ORIGIN).header("X-MoneyToad-Demo", "1")
                .contentType("application/json").content("{}")).andReturn();
            var row = support.evidence("login_http_contract", started);
            row.put("httpStatus", result.getResponse().getStatus());
            row.put("databaseUnchanged", before.equals(DemoAuthHttpTestSupport.snapshot(jdbc)));
            row.put("demoEndpointCount", demoMappings(started));
            DemoAuthHttpTestSupport.emit(row);
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            var body = DemoAuthHttpTestSupport.JSON.readTree(result.getResponse().getContentAsString());
            assertThat(body.size()).isEqualTo(1);
            assertThat(body.hasNonNull("accessToken")).isTrue();
            var jwt = context.getBean(JwtUtil.class);
            var claims = jwt.validateDemoAccessToken(jwt.parse(body.get("accessToken").asText()), java.time.Instant.now());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(before.get("users").size() + 1);
            var user = context.getBean(UserRepository.class).findById(claims.userId()).orElseThrow();
            assertThat(user.getEmail().matches("demo-[0-9a-f]{32}@moneytoad\\.invalid")).isTrue();
            assertThat(user.getName().matches("데모 방문자 [0-9a-f]{8}")).isTrue();
            assertThat(result.getResponse().getHeaders("Set-Cookie").size()).isEqualTo(1);
            assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(support.noOutbound(started.probe())).isTrue();
            context.getBean(com.potg.don.auth.demo.DemoSessionService.class).revoke(claims.sid());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "prod", "production"})
    void ordinaryModeHasNoDemoAuthHandlers(String profile) throws Exception {
        try (var support = new DemoAuthHttpTestSupport();
             var started = support.start(profile.equals("default") ? "" : profile, "", null, null, true, Map.of())) {
            assertThat(started.failure() == null).isTrue();
            var context = started.context();
            var user = context.getBean(UserRepository.class).saveAndFlush(User.createUser("http-control@moneytoad.invalid", "Synthetic Control"));
            String access = context.getBean(JwtUtil.class).createAccessToken(user.getId(), user.getEmail());
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            var before = DemoAuthHttpTestSupport.snapshot(jdbc);
            MockMvc mvc = mvc(started);
            for (String path : Set.of("login", "reissue", "session", "logout")) {
                var request = path.equals("session") ? get("/api/auth/demo/" + path) : post("/api/auth/demo/" + path);
                var result = mvc.perform(request.contextPath("/api").header("Authorization", "Bearer " + access)).andReturn();
                assertThat(result.getResponse().getStatus()).isEqualTo(404);
            }
            assertThat(demoMappings(started)).isZero();
            for (Class<?> type : java.util.List.of(com.potg.don.auth.demo.DemoAuthController.class,
                com.potg.don.auth.demo.DemoAuthService.class, com.potg.don.auth.demo.DemoAuthHttpConfiguration.class,
                com.potg.don.auth.demo.DemoAuthHttpConfiguration.Settings.class,
                com.potg.don.auth.demo.DemoRefreshCookie.class, com.potg.don.auth.demo.DemoAuthExceptionHandler.class,
                com.potg.don.auth.demo.DemoSessionGuard.class, com.potg.don.auth.demo.DemoSessionStore.class,
                com.potg.don.auth.demo.DemoSessionService.class)) {
                assertThat(context.getBeansOfType(type)).isEmpty();
            }
            assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(jdbc))).isTrue();
            assertThat(support.noOutbound(started.probe())).isTrue();
            var row = support.evidence("ordinary_" + profile, started); row.put("demoEndpointCount", 0);
            row.put("databaseUnchanged", true); DemoAuthHttpTestSupport.emit(row);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}"})
    void completeHttpLifecyclePreservesAbsoluteDeadline(String body) throws Exception {
        try (Scenario scenario = new Scenario(false)) {
            var first = scenario.login(body);
            long deadline = expiry(scenario, first);
            var before = DemoAuthHttpTestSupport.snapshot(scenario.jdbc);
            var hash = scenario.redis.opsForHash().get(key(first), "refreshHash");
            var session = scenario.mvc.perform(get("/api/auth/demo/session").contextPath("/api")
                .header("Authorization", "Bearer " + first.access())).andReturn();
            assertThat(session.getResponse().getStatus()).isEqualTo(200);
            var state = DemoAuthHttpTestSupport.JSON.readTree(session.getResponse().getContentAsString());
            assertThat(state.size()).isEqualTo(2); assertThat(state.path("demo").asBoolean()).isTrue();
            assertThat(java.time.Instant.parse(state.path("expiresAt").asText()).toEpochMilli()).isEqualTo(first.deadline());
            assertThat(session.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            var response = scenario.mvc.perform(postRequest("reissue", body).cookie(rt(first))).andReturn();
            assertThat(response.getResponse().getStatus()).isEqualTo(200);
            var next = credentials(scenario, response);
            assertThat(next.refresh().equals(first.refresh())).isFalse();
            assertThat(next.sid().equals(first.sid())).isTrue();
            assertThat(expiry(scenario, next)).isEqualTo(deadline);
            assertThat(next.deadline()).isEqualTo(first.deadline());
            assertThat(scenario.redis.opsForHash().get(key(next), "refreshHash").equals(hash)).isFalse();
            var logout = scenario.mvc.perform(postRequest("logout", body).header("Authorization", "Bearer " + next.access())
                .cookie(rt(next))).andReturn();
            assertThat(logout.getResponse().getStatus()).isEqualTo(204);
            assertDeletedCookie(logout, false);
            assertThat(scenario.redis.hasKey(key(first))).isFalse();
            assertThat(protectedStatus(scenario, first.access())).isEqualTo(401);
            assertThat(protectedStatus(scenario, next.access())).isEqualTo(401);
            assertThat(scenario.mvc.perform(postRequest("reissue", "{}").cookie(rt(next))).andReturn().getResponse().getStatus()).isEqualTo(401);
            assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(scenario.jdbc))).isTrue();
            scenario.record("full_lifecycle", Map.of("httpFlowPassed", true, "deadlinePreserved", true, "databaseUnchanged", true));
        }
    }

    @Test
    void activeAndStaleLoginCookiesNeverCreateAnotherVisitor() throws Exception {
        try (Scenario scenario = new Scenario(false)) {
            var existing = scenario.login("{}");
            var before = DemoAuthHttpTestSupport.snapshot(scenario.jdbc);
            var hash = scenario.redis.opsForHash().get(key(existing), "refreshHash"); long deadline = expiry(scenario, existing);
            var active = scenario.mvc.perform(postRequest("login", "{}").cookie(rt(existing))).andReturn();
            assertError(active, 409); assertThat(active.getResponse().getHeaders("Set-Cookie")).isEmpty();
            assertThat(scenario.redis.opsForHash().get(key(existing), "refreshHash").equals(hash)).isTrue();
            assertThat(expiry(scenario, existing)).isEqualTo(deadline);
            scenario.store.revoke(existing.sid());
            var stale = scenario.mvc.perform(postRequest("login", "{}").cookie(rt(existing))).andReturn();
            assertError(stale, 401); assertDeletedCookie(stale, false);
            for (String invalid : java.util.List.of("malformed-demo-cookie", "")) {
                var rejected = scenario.mvc.perform(postRequest("login", "{}").cookie(new jakarta.servlet.http.Cookie("demoRefreshToken", invalid))).andReturn();
                assertError(rejected, 401); assertDeletedCookie(rejected, false);
            }
            var duplicate = scenario.mvc.perform(postRequest("login", "{}").cookie(rt(existing), rt(existing))).andReturn();
            assertError(duplicate, 401); assertDeletedCookie(duplicate, false);
            assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(scenario.jdbc))).isTrue();
            var fresh = scenario.login("");
            assertThat(fresh.userId()).isNotEqualTo(existing.userId());
            scenario.record("duplicate_login_and_new_visitor", Map.of("active409", true, "stale401", true, "newUserOnlyWithoutCookie", true));
        }
    }

    @Test
    void expiredCookieAndWrongTokenPurposesAreRejectedWithoutWrites() throws Exception {
        try (Scenario scenario = new Scenario(false)) {
            var token = scenario.login("{}"); var before = DemoAuthHttpTestSupport.snapshot(scenario.jdbc);
            var normal = scenario.started.context().getBean(JwtUtil.class).createRefreshToken(token.userId());
            java.time.Instant now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            String jti = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
            String expired = scenario.jwt.createDemoRefreshToken(token.userId(), token.sid(), jti, now.minusSeconds(20), now.minusSeconds(10));
            for (String bad : java.util.List.of(normal, token.access(), expired)) {
                for (String endpoint : java.util.List.of("login", "reissue")) {
                    var result = scenario.mvc.perform(postRequest(endpoint, "{}").cookie(new jakarta.servlet.http.Cookie("demoRefreshToken", bad))).andReturn();
                    assertError(result, 401); assertDeletedCookie(result, false);
                }
            }
            assertThat(protectedStatus(scenario, token.refresh())).isEqualTo(401);
            var noCookie = scenario.mvc.perform(postRequest("reissue", "{}").header("Authorization", "Bearer " + token.access())).andReturn();
            assertError(noCookie, 401); assertDeletedCookie(noCookie, false);
            assertThat(scenario.store.findActive(token.sid()).isPresent()).isTrue();
            assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(scenario.jdbc))).isTrue();
            scenario.record("http_token_purpose", Map.of("rejectedWithoutWrites", true));
        }
    }

    @Test
    void oldRefreshReuseRevokesSidAndWinnerTokens() throws Exception {
        try (Scenario scenario = new Scenario(false)) {
            var old = scenario.login("{}"); var before = DemoAuthHttpTestSupport.snapshot(scenario.jdbc);
            var first = scenario.mvc.perform(postRequest("reissue", "{}").cookie(rt(old))).andReturn();
            assertThat(first.getResponse().getStatus()).isEqualTo(200); var next = credentials(scenario, first);
            var reused = scenario.mvc.perform(postRequest("reissue", "{}").cookie(rt(old))).andReturn();
            assertError(reused, 401); assertDeletedCookie(reused, false);
            assertThat(scenario.store.findActive(old.sid())).isEmpty();
            assertThat(protectedStatus(scenario, old.access())).isEqualTo(401);
            assertThat(protectedStatus(scenario, next.access())).isEqualTo(401);
            assertError(scenario.mvc.perform(postRequest("reissue", "{}").cookie(rt(next))).andReturn(), 401);
            assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(scenario.jdbc))).isTrue();
            scenario.record("old_rt_reuse_http", Map.of("sidRevoked", true, "winnerTokensRejected", true, "databaseUnchanged", true));
        }
    }

    @Test
    void simultaneousHttpReissueLeavesNoSessionEvenIfSuccessCookieArrivesLast() throws Exception {
        try (Scenario scenario = new Scenario(false)) {
            var old = scenario.login("{}"); var before = DemoAuthHttpTestSupport.snapshot(scenario.jdbc);
            var barrier = new java.util.concurrent.CyclicBarrier(2);
            var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
            java.util.List<org.springframework.test.web.servlet.MvcResult> outcomes;
            try {
                java.util.concurrent.Callable<org.springframework.test.web.servlet.MvcResult> call = () -> {
                    barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    return scenario.mvc.perform(postRequest("reissue", "{}").cookie(rt(old))).andReturn();
                };
                var one = executor.submit(call); var two = executor.submit(call);
                outcomes = java.util.List.of(one.get(20, java.util.concurrent.TimeUnit.SECONDS), two.get(20, java.util.concurrent.TimeUnit.SECONDS));
            } finally { executor.shutdownNow(); }
            assertThat(outcomes.stream().map(r -> r.getResponse().getStatus()).sorted().toList()).containsExactly(200, 401);
            var successful = outcomes.stream().filter(r -> r.getResponse().getStatus() == 200).findFirst().orElseThrow();
            var rejected = outcomes.stream().filter(r -> r.getResponse().getStatus() == 401).findFirst().orElseThrow();
            assertDeletedCookie(rejected, false); var winner = credentials(scenario, successful);
            assertThat(scenario.store.findActive(old.sid())).isEmpty();
            assertThat(protectedStatus(scenario, winner.access())).isEqualTo(401);
            assertThat(protectedStatus(scenario, old.access())).isEqualTo(401);
            // Model both response-cookie arrival orders; no browser E2E is claimed.
            assertError(scenario.mvc.perform(postRequest("reissue", "{}").cookie(rt(winner))).andReturn(), 401);
            assertError(scenario.mvc.perform(postRequest("reissue", "{}")).andReturn(), 401);
            assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(scenario.jdbc))).isTrue();
            scenario.record("concurrent_http_reissue", Map.of("successCount", 1, "reuseCount", 1, "finalSessionAbsent", true, "bothCookieArrivalOrdersUnauthorized", true));
        }
    }

    @Test
    void logoutUsesAccessSidOnlyAndDoesNotRevokeCookieOwnersSession() throws Exception {
        try (Scenario scenario = new Scenario(false)) {
            var a = scenario.login("{}"); var b = scenario.login("{}");
            var before = DemoAuthHttpTestSupport.snapshot(scenario.jdbc);
            var result = scenario.mvc.perform(postRequest("logout", "{}").header("Authorization", "Bearer " + a.access()).cookie(rt(b))).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(204); assertDeletedCookie(result, false);
            assertThat(scenario.store.findActive(a.sid())).isEmpty();
            assertThat(scenario.store.findActive(b.sid()).isPresent()).isTrue();
            assertThat(protectedStatus(scenario, a.access())).isEqualTo(401);
            assertThat(protectedStatus(scenario, b.access())).isEqualTo(200);
            var noCookie = scenario.mvc.perform(postRequest("logout", "{}").header("Authorization", "Bearer " + b.access())).andReturn();
            assertThat(noCookie.getResponse().getStatus()).isEqualTo(204); assertDeletedCookie(noCookie, false);
            assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(scenario.jdbc))).isTrue();
            scenario.record("logout_at_sid_only", Map.of("otherSessionPreserved", true, "cookieNotRequired", true, "databaseUnchanged", true));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realRedisRefusalAndTimeoutAreHttp503WithoutBusinessWrites(boolean timeout) throws Exception {
        try (Scenario scenario = new Scenario(false);
             var socket = new java.net.ServerSocket(0, 10, java.net.InetAddress.getByAddress(new byte[]{127,0,0,1}))) {
            var existing = scenario.login("{}"); var before = DemoAuthHttpTestSupport.snapshot(scenario.jdbc);
            int port = socket.getLocalPort(); if (!timeout) socket.close();
            var config = new org.springframework.data.redis.connection.RedisStandaloneConfiguration("127.0.0.1", port);
            var client = org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
                .commandTimeout(java.time.Duration.ofMillis(200)).shutdownTimeout(java.time.Duration.ZERO)
                .clientOptions(io.lettuce.core.ClientOptions.builder()
                    .socketOptions(io.lettuce.core.SocketOptions.builder().connectTimeout(java.time.Duration.ofMillis(200)).build())
                    .timeoutOptions(io.lettuce.core.TimeoutOptions.enabled(java.time.Duration.ofMillis(200))).build()).build();
            var factory = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(config, client);
            factory.afterPropertiesSet(); factory.start();
            var unavailable = new org.springframework.data.redis.core.StringRedisTemplate(factory);
            var actual = scenario.redis;
            org.springframework.test.util.ReflectionTestUtils.setField(scenario.store, "redis", unavailable);
            try {
                var login = scenario.mvc.perform(postRequest("login", "{}")).andReturn();
                assertError(login, 503); assertThat(login.getResponse().getHeaders("Set-Cookie")).isEmpty();
                var reissue = scenario.mvc.perform(postRequest("reissue", "{}").cookie(rt(existing))).andReturn();
                assertError(reissue, 503); assertThat(reissue.getResponse().getHeaders("Set-Cookie")).isEmpty();
                int reads = scenario.started.probe().userLookups.get();
                assertThat(protectedStatus(scenario, existing.access())).isEqualTo(503);
                var logout = scenario.mvc.perform(postRequest("logout", "{}").header("Authorization", "Bearer " + existing.access())).andReturn();
                assertError(logout, 503); assertThat(logout.getResponse().getHeaders("Set-Cookie")).isEmpty();
                assertThat(scenario.started.probe().userLookups.get()).isEqualTo(reads);
            } finally {
                org.springframework.test.util.ReflectionTestUtils.setField(scenario.store, "redis", actual);
                factory.destroy();
            }
            assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(scenario.jdbc))).isTrue();
            assertThat(scenario.store.findActive(existing.sid()).isPresent()).isTrue();
            scenario.record(timeout ? "redis_http_timeout" : "redis_http_refusal", Map.of("http503", true, "databaseUnchanged", true, "protectedUserReads", 0));
        }
    }

    @Test
    void publicDemoSetsSecureCookieAndNeverExposesRefreshInJson() throws Exception {
        try (Scenario scenario = new Scenario(true)) {
            var first = scenario.login("{}");
            var result = scenario.mvc.perform(publicRequest("reissue").cookie(rt(first))).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(200); var next = credentials(scenario, result);
            assertCookie(result, next.deadline(), true);
            var logout = scenario.mvc.perform(publicRequest("logout").header("Authorization", "Bearer " + next.access())).andReturn();
            assertThat(logout.getResponse().getStatus()).isEqualTo(204); assertDeletedCookie(logout, true);
            scenario.record("public_cookie_scope", Map.of("secureCookie", true, "refreshAbsentFromJson", true));
        }
    }

    @Test
    void missingUserCannotReissueOrReachProtectedEndpoint() throws Exception {
        try (Scenario scenario = new Scenario(false)) {
            var token = scenario.login("{}");
            // Keep the original missing-user regression; explicitly remove only this fixture's new children.
            scenario.jdbc.update("DELETE t FROM transactions t JOIN cards c ON t.card_id=c.id WHERE c.user_id=?", token.userId());
            scenario.jdbc.update("DELETE FROM budgets WHERE user_id=?", token.userId());
            scenario.jdbc.update("DELETE FROM cards WHERE user_id=?", token.userId());
            scenario.started.context().getBean(UserRepository.class).deleteById(token.userId());
            var before = DemoAuthHttpTestSupport.snapshot(scenario.jdbc);
            var hash = scenario.redis.opsForHash().get(key(token), "refreshHash");
            var result = scenario.mvc.perform(postRequest("reissue", "{}").cookie(rt(token))).andReturn();
            assertError(result, 401); assertDeletedCookie(result, false);
            assertThat(protectedStatus(scenario, token.access())).isEqualTo(401);
            assertThat(scenario.redis.opsForHash().get(key(token), "refreshHash").equals(hash)).isTrue();
            assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(scenario.jdbc))).isTrue();
            scenario.record("missing_user_http", Map.of("rotationAbsent", true, "databaseUnchanged", true));
        }
    }

    static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postRequest(String endpoint, String body) {
        return post("/api/auth/demo/" + endpoint).contextPath("/api").header("Origin", ORIGIN)
            .header("X-MoneyToad-Demo", "1").contentType("application/json").content(body);
    }
    static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder publicRequest(String endpoint) {
        return post("/api/auth/demo/" + endpoint).contextPath("/api").header("Origin", "https://demo.example.invalid")
            .header("X-MoneyToad-Demo", "1").contentType("application/json").content("{}")
            .with(request -> { request.setScheme("https"); request.setSecure(true); request.setServerName("demo.example.invalid"); request.setServerPort(443); return request; });
    }
    static int protectedStatus(Scenario s, String access) throws Exception {
        return s.mvc.perform(get("/api/auth/demo/session").contextPath("/api").header("Authorization", "Bearer " + access))
            .andReturn().getResponse().getStatus();
    }
    static jakarta.servlet.http.Cookie rt(Credentials value) { return new jakarta.servlet.http.Cookie("demoRefreshToken", value.refresh()); }
    static String key(Credentials value) { return "demo:session:" + value.sid(); }
    static long expiry(Scenario s, Credentials value) {
        return s.redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(
            "return redis.call('PEXPIRETIME', KEYS[1])", Long.class), java.util.List.of(key(value)));
    }
    static void assertError(org.springframework.test.web.servlet.MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        var body = DemoAuthHttpTestSupport.JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.size()).isEqualTo(3); assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.has("error") && body.has("message") && !body.has("accessToken")).isTrue();
    }
    static void assertCookie(org.springframework.test.web.servlet.MvcResult result, long deadline, boolean secure) {
        var headers = result.getResponse().getHeaders("Set-Cookie"); assertThat(headers.size()).isEqualTo(1);
        var c = java.net.HttpCookie.parse(headers.getFirst()).getFirst();
        assertThat(c.getName()).isEqualTo("demoRefreshToken"); assertThat(c.isHttpOnly()).isTrue();
        assertThat(c.getSecure()).isEqualTo(secure); assertThat(c.getPath()).isEqualTo("/api/auth/demo");
        assertThat(c.getDomain()).isNull(); assertThat(headers.getFirst().contains("SameSite=Lax")).isTrue();
        assertThat(c.getMaxAge()).isPositive().isLessThanOrEqualTo(3600);
        assertThat(java.time.Instant.now().getEpochSecond() + c.getMaxAge()).isLessThanOrEqualTo(deadline / 1000 + 1);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    }
    static void assertDeletedCookie(org.springframework.test.web.servlet.MvcResult result, boolean secure) {
        var headers = result.getResponse().getHeaders("Set-Cookie"); assertThat(headers.size()).isEqualTo(1);
        var c = java.net.HttpCookie.parse(headers.getFirst()).getFirst();
        assertThat(c.getName()).isEqualTo("demoRefreshToken"); assertThat(c.getMaxAge()).isZero();
        assertThat(c.getValue().isEmpty()).isTrue(); assertThat(c.isHttpOnly()).isTrue();
        assertThat(c.getSecure()).isEqualTo(secure); assertThat(c.getDomain()).isNull();
        assertThat(c.getPath()).isEqualTo("/api/auth/demo"); assertThat(headers.getFirst().contains("SameSite=Lax")).isTrue();
    }
    static Credentials credentials(Scenario s, org.springframework.test.web.servlet.MvcResult result) throws Exception {
        var body = DemoAuthHttpTestSupport.JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.size()).isEqualTo(1); assertThat(body.hasNonNull("accessToken")).isTrue();
        String access = body.get("accessToken").asText();
        var c = s.jwt.validateDemoAccessToken(s.jwt.parse(access), java.time.Instant.now());
        var cookie = java.net.HttpCookie.parse(result.getResponse().getHeader("Set-Cookie")).getFirst();
        String refresh = cookie.getValue();
        var r = s.jwt.validateDemoRefreshToken(s.jwt.parse(refresh), java.time.Instant.now());
        assertThat(c.sid().equals(r.sid())).isTrue(); assertThat(c.userId()).isEqualTo(r.userId());
        assertCookie(result, r.expiresAt().toEpochMilli(), s.publicMode);
        return new Credentials(access, refresh, c.sid(), c.userId(), r.expiresAt().toEpochMilli());
    }
    record Credentials(String access, String refresh, String sid, long userId, long deadline) {
        @Override public String toString() { return "Credentials[redacted]"; }
    }
    static final class Scenario implements AutoCloseable {
        final DemoAuthHttpTestSupport support;
        final DemoAuthHttpTestSupport.Started started;
        final MockMvc mvc;
        final JdbcTemplate jdbc;
        final org.springframework.data.redis.core.StringRedisTemplate redis;
        final com.potg.don.auth.demo.DemoSessionStore store;
        final JwtUtil jwt;
        final java.util.Set<String> initialKeys;
        final boolean publicMode;
        Scenario(boolean publicMode) throws Exception {
            this.publicMode = publicMode; support = new DemoAuthHttpTestSupport();
            started = support.start("demo", "", "true", publicMode ? "public-demo" : "local-demo", false, Map.of());
            assertThat(started.failure() == null).isTrue();
            mvc = mvc(started); jdbc = new JdbcTemplate(started.context().getBean(DataSource.class));
            redis = started.context().getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
            store = started.context().getBean(com.potg.don.auth.demo.DemoSessionStore.class); jwt = started.context().getBean(JwtUtil.class);
            initialKeys = new java.util.HashSet<>(redis.keys("demo:session:*"));
        }
        Credentials login(String body) throws Exception {
            int users = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
            int sessions = redis.keys("demo:session:*").size();
            var result = mvc.perform(publicMode ? publicRequest("login") : postRequest("login", body)).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            Credentials c = credentials(this, result);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(users + 1);
            assertThat(redis.keys("demo:session:*").size()).isEqualTo(sessions + 1);
            return c;
        }
        void record(String name, Map<String, Object> values) throws Exception {
            assertThat(support.noOutbound(started.probe())).isTrue();
            var row = support.evidence(name, started); row.putAll(values); DemoAuthHttpTestSupport.emit(row);
        }
        @Override public void close() {
            try {
                var created = new java.util.HashSet<>(redis.keys("demo:session:*")); created.removeAll(initialKeys);
                if (!created.isEmpty()) redis.delete(created);
                assertThat(support.noOutbound(started.probe())).isTrue();
            } finally { started.close(); support.close(); }
        }
    }

    static MockMvc mvc(DemoAuthHttpTestSupport.Started started) {
        return MockMvcBuilders.webAppContextSetup((GenericWebApplicationContext)started.context()).apply(springSecurity()).build();
    }

    static long demoMappings(DemoAuthHttpTestSupport.Started started) {
        return started.context().getBean(RequestMappingHandlerMapping.class).getHandlerMethods().keySet().stream()
            .flatMap(info -> info.getPatternValues().stream()).filter(path -> path.startsWith("/auth/demo")).count();
    }
}
