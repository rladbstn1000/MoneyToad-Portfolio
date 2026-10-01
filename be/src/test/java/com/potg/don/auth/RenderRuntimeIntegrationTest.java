package com.potg.don.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potg.don.analysisJob.entity.AnalysisJob;
import com.potg.don.analysisJob.repository.AnalysisJobRepository;
import com.potg.don.analysisJob.scheduler.AnalysisJobScheduler;
import com.potg.don.auth.controller.AuthController;
import com.potg.don.auth.demo.DemoAuthController;
import com.potg.don.auth.demo.DemoRefreshCookie;
import com.potg.don.auth.demo.DemoSessionGuard;
import com.potg.don.auth.demo.DemoSessionService;
import com.potg.don.auth.jwt.JwtAuthenticationFilter;
import com.potg.don.auth.jwt.JwtUtil;
import com.potg.don.auth.oauth.CustomOAuth2UserService;
import com.potg.don.auth.oauth.OAuth2SuccessHandler;
import com.potg.don.auth.service.AuthService;
import com.zaxxer.hikari.HikariDataSource;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.Cookie;

/**
 * Actual product ConfigData, TLS MySQL/Redis and the real JWT/Guard/HTTP boundary.
 * Run only after RenderSchemaPreparation in its separate process. The runner owns
 * all infrastructure and supplies RUNNER_RENDER_* inputs; no API/security doubles.
 * Diagnostics deliberately compare booleans/counts, never dump tokens or identities.
 */
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RenderRuntimeIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> TABLES = List.of("users", "cards", "transactions", "budgets", "analysis_job");
    private final List<String> sensitiveValues = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private RenderRuntimeTestSupport fixture;
    private ConfigurableApplicationContext context;
    private MockMvc mvc;
    private JdbcTemplate jdbc;
    private StringRedisTemplate redis;
    private JwtUtil jwt;
    private Logger authLogger;
    private String access;
    private String refresh;
    private String sid;
    private long userId;
    private Instant expiresAt;

    @BeforeAll void startActualRenderContext() throws Exception {
        fixture = new RenderRuntimeTestSupport();
        try {
            context = fixture.start(false);
            jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            redis = context.getBean(StringRedisTemplate.class);
            jwt = context.getBean(JwtUtil.class);
            mvc = MockMvcBuilders.webAppContextSetup((GenericWebApplicationContext) context).apply(springSecurity())
                .defaultRequest(RenderRuntimeTestSupport.gateway(get("/"))).build();
            authLogger = (Logger) LoggerFactory.getLogger("com.potg.don");
            logs.start();
            authLogger.addAppender(logs);
        } catch (Throwable failure) {
            fixture.close();
            throw failure;
        }
    }

    @AfterAll void closeOnlyOwnedContext() {
        try {
            if (context != null && sid != null) context.getBean(DemoSessionService.class).revoke(sid);
        } finally {
            if (authLogger != null) authLogger.detachAppender(logs);
            logs.stop();
            if (fixture != null) fixture.close();
        }
    }

    @Test @Order(1) void actualProductConfigDataUsesValidateBoundedPoolAndVerifiedTls() throws Exception {
        var env = context.getEnvironment();
        for (String file : List.of("application.yml", "application-demo.yml", "application-render.yml")) {
            assertThat(env.getPropertySources().stream().anyMatch(source -> source.getName().contains(file)))
                .as("actual product ConfigData resource loaded: " + file).isTrue();
        }
        assertThat(env.acceptsProfiles(Profiles.of("demo & render"))).isTrue();
        assertThat(env.containsProperty("SSAFY_CLIENT_ID")).isFalse();
        assertThat(env.getProperty("server.port", Integer.class)).isEqualTo(Integer.parseInt(System.getenv("RUNNER_RENDER_PORT")));
        assertThat(env.getProperty("server.address")).isEqualTo("0.0.0.0");
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(env.getProperty("spring.sql.init.mode")).isEqualTo("never");
        HikariDataSource dataSource = context.getBean(HikariDataSource.class);
        assertThat(dataSource.getMaximumPoolSize()).isEqualTo(3);
        assertThat(dataSource.getMinimumIdle()).isZero();
        assertThat(dataSource.getMaxLifetime()).isEqualTo(300_000);
        assertThat(dataSource.getJdbcUrl().contains("sslMode=VERIFY_IDENTITY")).isTrue();
        String cipher = jdbc.queryForObject("SHOW SESSION STATUS LIKE 'Ssl_cipher'", (rs, row) -> rs.getString(2));
        assertThat(cipher != null && !cipher.isBlank()).as("actual JDBC connection negotiated TLS").isTrue();
        RedisProperties properties = context.getBean(RedisProperties.class);
        assertThat(properties.getSsl().isEnabled()).isTrue();
        assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.getUsername() != null && !properties.getUsername().isBlank()).isTrue();
        assertThat(properties.getPassword() != null && !properties.getPassword().isBlank()).isTrue();
        LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
        assertThat(factory.getClientConfiguration().isUseSsl()).isTrue();
        assertThat(factory.getClientConfiguration().isVerifyPeer()).isTrue();
        try (var connection = factory.getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
        assertThat(counts()).containsExactly(0L, 0L, 0L, 0L, 0L);
    }

    @Test @Order(2) void demoHasOneRealJwtChainGuardAndNoOauthBeansOrFilters() throws Exception {
        assertThat(context.getBeansOfType(CustomOAuth2UserService.class)).isEmpty();
        assertThat(context.getBeansOfType(OAuth2SuccessHandler.class)).isEmpty();
        assertThat(context.getBeansOfType(ClientRegistrationRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(AuthController.class)).isEmpty();
        assertThat(context.getBeansOfType(AuthService.class)).isEmpty();
        assertThat(context.getBeansOfType(DemoSessionGuard.class)).hasSize(1);
        var chains = context.getBeansOfType(SecurityFilterChain.class).values();
        assertThat(chains).hasSize(1);
        var chain = chains.iterator().next();
        assertThat(chain.getFilters().contains(context.getBean(JwtAuthenticationFilter.class))).isTrue();
        assertThat(chain.getFilters().stream().noneMatch(filter -> filter.getClass().getSimpleName().startsWith("OAuth2"))).isTrue();
        long demoMappings = context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().values().stream()
            .filter(handler -> handler.getBeanType().equals(DemoAuthController.class)).count();
        assertThat(demoMappings).isEqualTo(4);
        var beforeReadiness = counts();
        var readiness = mvc.perform(get("/api/auth/demo/ready").contextPath("/api")).andReturn().getResponse();
        assertThat(readiness.getStatus()).isEqualTo(200);
        assertThat(readiness.getContentAsString()).isEqualTo("{\"ready\":true}");
        assertThat(readiness.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(readiness.getHeaders("Set-Cookie")).isEmpty();
        assertThat(counts()).isEqualTo(beforeReadiness);
        assertThat(mvc.perform(get("/api/users").contextPath("/api")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/auth/demo/session").contextPath("/api")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test @Order(3) void schedulerCannotPollEvenWithAnEligibleJobAndNoAiCallOccurs() throws Exception {
        assertThat(context.getBeansOfType(AnalysisJobScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).values().stream()
            .flatMap(processor -> processor.getScheduledTasks().stream()).count()).isZero();
        AnalysisJob job = new AnalysisJob();
        job.setUserId(1L);
        job.setFileId("owned-render-poll-observation");
        job.setStatus(AnalysisJob.Status.QUEUED);
        job.setRetryCount(0);
        job.setNextPollAt(Instant.now().minusSeconds(10));
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        var repository = context.getBean(AnalysisJobRepository.class);
        job = repository.saveAndFlush(job);
        long jobId = job.getId();
        try {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_job WHERE status='QUEUED' AND next_poll_at <= CURRENT_TIMESTAMP AND leased_until IS NULL", Long.class)).isEqualTo(1);
            Map<String, Object> before = jdbc.queryForMap("SELECT * FROM analysis_job WHERE id=?", jobId);
            // This bounded negative-observation interval exceeds the product's 2s
            // poll period; startup readiness is handled separately by the runner.
            boolean polled = fixture.probe.pollObserved.await(2400, TimeUnit.MILLISECONDS);
            assertThat(polled).as("no poll with actual queued work").isFalse();
            assertThat(before.equals(jdbc.queryForMap("SELECT * FROM analysis_job WHERE id=?", jobId)))
                .as("queued job remains unchanged").isTrue();
            assertNoAnalysisCalls();
        } finally {
            repository.deleteById(jobId);
            repository.flush();
        }
    }

    @Test @Order(4) void actualHttpLoginCommitsSeedAndReturnsPublicCookieWithGuardedPrincipal() throws Exception {
        List<Long> before = counts();
        MvcResult result = mvc.perform(demoPost("login")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.size()).isEqualTo(1);
        access = body.path("accessToken").asText();
        String header = result.getResponse().getHeader("Set-Cookie");
        refresh = refreshValue(header);
        remember(access, refresh);
        assertPublicCookie(header, false);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        var accessClaims = jwt.validateDemoAccessToken(jwt.parse(access), Instant.now());
        var refreshClaims = jwt.validateDemoRefreshToken(jwt.parse(refresh), Instant.now());
        userId = accessClaims.userId();
        sid = accessClaims.sid();
        expiresAt = refreshClaims.expiresAt();
        remember(sid);
        assertThat(accessClaims.userId() == refreshClaims.userId() && sid.equals(refreshClaims.sid())).isTrue();
        assertThat(counts()).containsExactly(before.get(0) + 1, before.get(1) + 1, before.get(2) + 240, before.get(3) + 72, before.get(4));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cards WHERE user_id=? AND card_no IS NULL AND cvc IS NULL", Long.class, userId)).isEqualTo(1);
        Map<String, Object> owner = jdbc.queryForMap("SELECT email, name FROM users WHERE id=?", userId);
        remember((String) owner.get("email"), (String) owner.get("name"));
        MvcResult session = mvc.perform(get("/api/auth/demo/session").contextPath("/api").header("Authorization", "Bearer " + access)).andReturn();
        assertThat(session.getResponse().getStatus()).isEqualTo(200);
        JsonNode sessionBody = JSON.readTree(session.getResponse().getContentAsString());
        assertThat(sessionBody.size()).isEqualTo(2);
        assertThat(sessionBody.path("demo").asBoolean()).isTrue();
        assertThat(Instant.parse(sessionBody.path("expiresAt").asText())).isEqualTo(expiresAt);
        // The real controller only responds after Authentication.details is the
        // guard's AuthorizedSession; /users independently checks the principal.
        MvcResult user = mvc.perform(get("/api/users").contextPath("/api").header("Authorization", "Bearer " + access)).andReturn();
        assertThat(user.getResponse().getStatus()).isEqualTo(200);
        JsonNode principal = JSON.readTree(user.getResponse().getContentAsString());
        assertThat(principal.path("email").asText().equals(owner.get("email"))
            && principal.path("name").asText().equals(owner.get("name"))).isTrue();
        assertThat(redis.hasKey("demo:session:" + sid)).isTrue();
        Object hash = redis.opsForHash().get("demo:session:" + sid, "refreshHash");
        if (hash instanceof String text) remember(text);
        assertNoAnalysisCalls();
    }

    @Test @Order(5) void realRotationAndLogoutPreserveExpiryAndRevokeExistingAccess() throws Exception {
        assertThat(access != null && refresh != null && sid != null).as("login must have completed").isTrue();
        assertThat(mvc.perform(demoPost("login").cookie(new Cookie(DemoRefreshCookie.NAME, refresh)))
            .andReturn().getResponse().getStatus()).isEqualTo(409);
        MvcResult result = mvc.perform(demoPost("reissue").cookie(new Cookie(DemoRefreshCookie.NAME, refresh))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String nextAccess = JSON.readTree(result.getResponse().getContentAsString()).path("accessToken").asText();
        String nextRefresh = refreshValue(result.getResponse().getHeader("Set-Cookie"));
        remember(nextAccess, nextRefresh);
        var rotated = jwt.validateDemoRefreshToken(jwt.parse(nextRefresh), Instant.now());
        assertThat(rotated.expiresAt()).isEqualTo(expiresAt);
        assertThat(rotated.sid().equals(sid) && rotated.userId() == userId).isTrue();
        assertThat(nextRefresh.equals(refresh)).isFalse();
        assertPublicCookie(result.getResponse().getHeader("Set-Cookie"), false);
        MvcResult logout = mvc.perform(demoPost("logout").header("Authorization", "Bearer " + nextAccess))
            .andReturn();
        assertThat(logout.getResponse().getStatus()).isEqualTo(204);
        assertPublicCookie(logout.getResponse().getHeader("Set-Cookie"), true);
        assertThat(redis.hasKey("demo:session:" + sid)).isFalse();
        assertThat(mvc.perform(get("/api/users").contextPath("/api").header("Authorization", "Bearer " + access))
            .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/auth/demo/session").contextPath("/api").header("Authorization", "Bearer " + nextAccess))
            .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(demoPost("reissue").cookie(new Cookie(DemoRefreshCookie.NAME, nextRefresh)))
            .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(counts()).containsExactly(1L, 1L, 240L, 72L, 0L);
        assertNoAnalysisCalls();
    }

    @Test @Order(6) void actualEnabledAuthLogsNeverEmitTokenCookieOrVisitorIdentifiers() {
        assertThat(authLogger.isInfoEnabled() && authLogger.isWarnEnabled()).as("auth logging remains enabled").isTrue();
        assertThat(logs.list.stream().anyMatch(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN)))
            .as("real rejection was logged").isTrue();
        String captured = logs.list.stream().map(ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));
        assertThat(captured.contains("DEMO_AUTH_TOKEN_REQUIRED")).isTrue();
        assertThat(captured.contains("DEMO_AUTHENTICATED")).as("real successful authentication was logged").isTrue();
        boolean identityFound = sensitiveValues.stream().filter(value -> value != null && !value.isEmpty()).anyMatch(captured::contains);
        boolean jwtFound = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+").matcher(captured).find();
        boolean headerValueFound = Pattern.compile("(?i)(?:authorization\\s*[:=]|bearer\\s+|demoRefreshToken=)\\S+").matcher(captured).find();
        boolean emailFound = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").matcher(captured).find();
        boolean identifierField = Pattern.compile("(?i)(?:user[_ ]?id|\\bsid|refresh[_ ]?hash)\\s*[:=]\\s*[^\\s,]+")
            .matcher(captured).find();
        assertThat(identityFound || jwtFound || headerValueFound || emailFound || identifierField)
            .as("captured actual authentication logs contain no sensitive values").isFalse();
        assertNoAnalysisCalls();
    }

    private static MockHttpServletRequestBuilder demoPost(String action) {
        return post("/api/auth/demo/" + action).contextPath("/api").contentType(MediaType.APPLICATION_JSON)
            .header("Origin", RenderRuntimeTestSupport.ORIGIN).header("X-MoneyToad-Demo", "1").content("{}");
    }

    private static String refreshValue(String header) {
        boolean valid = header != null && header.startsWith(DemoRefreshCookie.NAME + "=") && header.contains(";");
        assertThat(valid).as("refresh cookie exists, value intentionally redacted").isTrue();
        return header.substring((DemoRefreshCookie.NAME + "=").length(), header.indexOf(';'));
    }

    private static void assertPublicCookie(String header, boolean deletion) {
        assertThat(header != null).as("Set-Cookie present").isTrue();
        String lower = header.toLowerCase(Locale.ROOT);
        assertThat(header.startsWith(DemoRefreshCookie.NAME + "=") && header.contains("HttpOnly")
            && header.contains("Secure") && header.contains("SameSite=Lax")
            && header.contains("Path=/api/auth/demo") && !lower.contains("domain="))
            .as("public cookie security attributes, value intentionally redacted").isTrue();
        java.util.regex.Matcher age = Pattern.compile("(?:^|; )Max-Age=(\\d+)(?:;|$)").matcher(header);
        assertThat(age.find()).as("bounded cookie Max-Age exists").isTrue();
        long seconds = Long.parseLong(age.group(1));
        assertThat(deletion ? seconds == 0 : seconds > 0 && seconds <= 3600).isTrue();
        if (deletion) assertThat(header.startsWith(DemoRefreshCookie.NAME + "=;")).isTrue();
    }

    private List<Long> counts() {
        return TABLES.stream().map(table -> jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).toList();
    }
    private void remember(String... values) { sensitiveValues.addAll(List.of(values)); }
    private void assertNoAnalysisCalls() {
        assertThat(fixture.probe.repositoryPollCalls.get()).isZero();
        assertThat(fixture.probe.servicePollCalls.get()).isZero();
        assertThat(fixture.probe.csvCalls.get()).isZero();
        assertThat(fixture.probe.webClientCalls.get()).isZero();
        assertThat(fixture.probe.httpCalls.get()).isZero();
    }
}
