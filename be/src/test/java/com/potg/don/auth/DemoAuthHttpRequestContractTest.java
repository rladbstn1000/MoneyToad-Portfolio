package com.potg.don.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;

import com.potg.don.auth.demo.DemoSessionService;
import com.potg.don.auth.jwt.JwtUtil;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;

/** Grouped HTTP matrices through actual product Security/MVC/MySQL/Redis, never standalone controllers. */
@Execution(ExecutionMode.SAME_THREAD)
class DemoAuthHttpRequestContractTest {
	private static final String ORIGIN = "http://localhost:5173";
	private static final String EXPECTED_CONTENT_TYPE_COUNT = "DEMO_TEST_EXPECTED_CONTENT_TYPE_COUNT";
	private static final List<String> POSTS = List.of("login", "reissue", "logout");
	private static final DefaultRedisScript<Long> ABSOLUTE_EXPIRY =
		new DefaultRedisScript<>("return redis.call('PEXPIRETIME', KEYS[1])", Long.class);

	@Test
	void rejectedBodyMatrixCannotCreateRotateOrRevokeAnything() throws Exception {
		try (var fixture = new Fixture()) {
			List<String> bodies = List.of("{\"userId\":1}", "{\"email\":\"chosen@example.invalid\"}",
				"{\"name\":\"chosen\"}", "{\"card\":{}}", "{\"refreshToken\":\"synthetic-input\"}",
				"{\"sid\":\"synthetic-input\"}", "null", "[]", "1", "\"value\"", "{", "{}{}",
				"{} null", "{\"userId\":1,\"userId\":2}", " \t\r\n", "{ /*comment*/ }", "\uFEFF{}");
			int attempts = 0;
			for (String endpoint : POSTS) {
				for (String body : bodies) {
					fixture.reject(fixture.post(endpoint).content(body), 400);
					attempts++;
				}
				fixture.reject(fixture.post(endpoint).content(" ".repeat(1023) + "{}"), 413);
				attempts++;
			}
			fixture.emit("request_rejected_body_matrix", attempts);
		}
	}

	@Test
	void originExplicitHeaderJsonTypeAndQueryAreRequiredBeforePostSideEffects() throws Exception {
		try (var fixture = new Fixture()) {
			List<RejectedInput> inputs = List.of(
				new RejectedInput(403, builder -> remove(builder, "Origin")),
				new RejectedInput(403, builder -> replace(builder, "Origin", "null")),
				new RejectedInput(403, builder -> replace(builder, "Origin", "https://other.example.invalid")),
				new RejectedInput(403, builder -> replace(builder, "Origin", ORIGIN, ORIGIN)),
				new RejectedInput(403, builder -> replace(builder, "Origin", ORIGIN + ", https://other.example.invalid")),
				new RejectedInput(403, builder -> remove(builder, "X-MoneyToad-Demo")),
				new RejectedInput(403, builder -> replace(builder, "X-MoneyToad-Demo", "0")),
				new RejectedInput(403, builder -> replace(builder, "X-MoneyToad-Demo", "1", "1")),
				new RejectedInput(403, builder -> replace(builder, "X-MoneyToad-Demo", "1,1")),
				new RejectedInput(415, builder -> remove(builder, "Content-Type")),
				new RejectedInput(415, builder -> replace(builder, "Content-Type", "application/x-www-form-urlencoded")),
				new RejectedInput(415, builder -> replace(builder, "Content-Type", "multipart/form-data; boundary=synthetic")),
				new RejectedInput(415, builder -> replace(builder, "Content-Type", "text/plain")),
				new RejectedInput(415, builder -> replace(builder, "Content-Type", "application/problem+json")),
				new RejectedInput(415, builder -> replace(builder, "Content-Type", "application/json; charset=UTF-16")),
				new RejectedInput(415, builder -> replace(builder, "Content-Type", "application/json; boundary=synthetic")),
				new RejectedInput(415, builder -> replace(builder, "Content-Type", "invalid")),
				new RejectedInput(415, builder -> replace(builder, "Content-Type", "application/json", "application/json")),
				new RejectedInput(400, builder -> builder.queryParam("userId", "1")),
				new RejectedInput(400, builder -> builder.queryParam("refreshToken", "synthetic-input")),
				new RejectedInput(400, builder -> builder.queryParam("unrecognized", "")));
			int attempts = 0;
			for (String endpoint : POSTS) {
				for (RejectedInput input : inputs) {
					var request = fixture.post(endpoint);
					input.change().accept(request);
					fixture.reject(request, input.status());
					attempts++;
				}
			}
			fixture.emit("request_origin_header_media_query_matrix", attempts);
		}
	}

	@Test
	void emptyAndOnlyEmptyJsonObjectBodiesIncludingWhitespaceAndOneKiBAreAccepted() throws Exception {
		try (var fixture = new Fixture()) {
			List<String> bodies = List.of("", "{}", " \t\n{ \t\r\n}\r\n", " ".repeat(1022) + "{}");
			int attempts = 0;
			for (String body : bodies) {
				long usersBefore = fixture.users.count();
				var login = fixture.mvc.perform(validPost("login").contentType("application/json; charset=UTF-8").content(body)).andReturn();
				assertThat(login.getResponse().getStatus()).isEqualTo(201);
				assertThat(fixture.users.count()).isEqualTo(usersBefore + 1);
				String access = access(login);
				String sid = fixture.track(access);
				try {
					var reissue = fixture.mvc.perform(validPost("reissue").contentType("application/json; charset=utf-8")
						.content(body).cookie(refresh(login))).andReturn();
					assertThat(reissue.getResponse().getStatus()).isEqualTo(200);
					assertThat(fixture.users.count()).isEqualTo(usersBefore + 1);
					var logout = fixture.mvc.perform(validPost("logout").content(body)
						.header("Authorization", "Bearer " + access(reissue))).andReturn();
					assertThat(logout.getResponse().getStatus()).isEqualTo(204);
					assertThat(fixture.redis.hasKey("demo:session:" + sid)).isFalse();
					assertThat(fixture.users.count()).isEqualTo(usersBefore + 1);
				} finally { fixture.sessions.revoke(sid); }
				attempts += 3;
			}
			fixture.emit("request_allowed_empty_json_and_size_boundary", attempts);
		}
	}

	@Test
	void authenticatedSessionRejectsEveryBodyAndNonemptyQueryWithoutChangingState() throws Exception {
		try (var fixture = new Fixture()) {
			for (String body : List.of("{}", " ", "[]", "null")) fixture.reject(fixture.session().content(body), 400);
			fixture.reject(fixture.session().queryParam("userId", "1"), 400);
			fixture.reject(fixture.session().queryParam("sid", "synthetic-input"), 400);
			fixture.reject(fixture.session().content(" ".repeat(1025)), 413);
			fixture.emit("request_session_body_query_rejection", 7);
		}
	}

	@Test
	void validPreflightsForAllFourEndpointsNeedNoBearerCookieOrActualDemoHeader() throws Exception {
		try (var fixture = new Fixture()) {
			for (String endpoint : List.of("login", "reissue", "logout", "session")) {
				String method = endpoint.equals("session") ? "GET" : "POST";
				String requested = endpoint.equals("session") ? "Authorization" : "Content-Type,X-MoneyToad-Demo,Authorization";
				var before = fixture.snapshot();
				var result = fixture.mvc.perform(options("/api/auth/demo/" + endpoint).contextPath("/api")
					.header("Origin", ORIGIN).header("Access-Control-Request-Method", method)
					.header("Access-Control-Request-Headers", requested)).andReturn();
				assertThat(result.getResponse().getStatus()).isEqualTo(200);
				assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo(ORIGIN);
				assertThat(result.getResponse().getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
				assertThat(result.getResponse().getHeader("Access-Control-Allow-Methods").contains(method)).isTrue();
				String allowed = result.getResponse().getHeader("Access-Control-Allow-Headers").toLowerCase(Locale.ROOT);
				for (String header : requested.split(",")) assertThat(allowed.contains(header.toLowerCase(Locale.ROOT))).isTrue();
				assertThat(result.getResponse().getHeaders("Set-Cookie").isEmpty()).isTrue();
				fixture.unchanged(before);
			}
			fixture.emit("request_valid_four_endpoint_preflights", 4);
		}
	}

	@Test
	void disallowedPreflightOriginsHeadersAndMethodsAreRejectedWithoutSideEffects() throws Exception {
		try (var fixture = new Fixture()) {
			List<MockHttpServletRequestBuilder> requests = List.of(
				preflight("https://other.example.invalid", "POST", "Content-Type,X-MoneyToad-Demo"),
				preflight("null", "POST", "Content-Type,X-MoneyToad-Demo"),
				preflight(ORIGIN, "POST", "X-Unrecognized-Header"),
				preflight(ORIGIN, "TRACE", "Content-Type"));
			for (var request : requests) fixture.reject(request, 403);
			fixture.emit("request_rejected_preflight_matrix", requests.size());
		}
	}

	@Test
	void demoMissingBearer401PreservesExactCredentialCorsInsteadOfWildcard() throws Exception {
		try (var fixture = new Fixture()) {
			for (var request : List.of(get("/api/auth/demo/session").contextPath("/api").header("Origin", ORIGIN),
				validPost("logout"), get("/api/users").contextPath("/api").header("Origin", ORIGIN))) {
				var before = fixture.snapshot();
				var result = fixture.mvc.perform(request).andReturn();
				assertThat(result.getResponse().getStatus()).isEqualTo(401);
				assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo(ORIGIN);
				assertThat(result.getResponse().getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
				assertThat(result.getResponse().getHeaders("Set-Cookie").isEmpty()).isTrue();
				fixture.unchanged(before);
			}
			fixture.emit("request_demo_401_exact_cors", 3);
		}
	}

	@Test
	void invalidHttpConfigurationFailsBeforeDatabaseOrOrdinarySingletonInitialization() throws Exception {
		try (var support = new DemoAuthHttpTestSupport()) {
			List<InvalidSettings> settings = new ArrayList<>();
			Map<String, Object> missing = new LinkedHashMap<>();
			missing.put("app.demo.browser-origin", null);
			settings.add(new InvalidSettings("local-demo", missing));
			for (String origin : List.of("", "null", "*", "http://other.example.invalid", "https://localhost:5173",
				"http://localhost:5173/", "http://localhost:5173/path", "http://localhost:5173?query=1",
				"http://localhost:5173#fragment", "http://synthetic@localhost:5173", "http://LOCALHOST:5173",
				"http://localhost:80", "http://localhost:0", "http://localhost:65536")) {
				settings.add(new InvalidSettings("local-demo", Map.of("app.demo.browser-origin", origin)));
			}
			settings.add(new InvalidSettings("public-demo", Map.of("app.demo.browser-origin", "http://demo.example.invalid")));
			settings.add(new InvalidSettings("local-demo", Map.of("server.address", "0.0.0.0")));
			settings.add(new InvalidSettings("local-demo", Map.of("server.address", "")));
			settings.add(new InvalidSettings("local-demo", Map.of("server.ssl.enabled", "true")));
			for (InvalidSettings settingsCase : settings) {
				try (var started = support.start("demo", "", "true", settingsCase.kind(), false, settingsCase.overrides())) {
					assertThat(started.failure() != null).as("Invalid HTTP settings rejected").isTrue();
					assertThat(hasEarlyGuardFailure(started.failure())).isTrue();
					assertThat(started.probe().dataSourceInitialized.get()).isFalse();
					assertThat(started.probe().singletonInitialized.get()).isFalse();
					assertThat(support.noOutbound(started.probe())).isTrue();
				}
			}
			DemoAuthHttpTestSupport.emit(Map.of("scenario", "request_invalid_http_config_before_db", "matrixRequests", settings.size(),
				"databaseInitializationUntouched", true, "outboundRequests", 0));
		}
	}

	@Test
	void localDemoRequiresMatchingLoopbackHostAndPlainHttpRequestAuthority() throws Exception {
		try (var fixture = new Fixture()) {
			List<Consumer<MockHttpServletRequestBuilder>> changes = List.of(
				builder -> builder.with(request -> { request.setServerName("127.0.0.1"); return request; }),
				builder -> builder.with(request -> { request.setServerName("other.example.invalid"); return request; }),
				builder -> builder.with(request -> { request.setScheme("https"); return request; }),
				builder -> builder.secure(true));
			for (String endpoint : POSTS) {
				for (var change : changes) {
					var request = fixture.post(endpoint);
					change.accept(request);
					fixture.reject(request, 403);
				}
			}
			fixture.emit("request_local_actual_authority", POSTS.size() * changes.size());
		}
	}

	private record RejectedInput(int status, Consumer<MockHttpServletRequestBuilder> change) { }
	private record InvalidSettings(String kind, Map<String, Object> overrides) { }
	private static MockHttpServletRequestBuilder validPost(String endpoint) {
		return post("/api/auth/demo/" + endpoint).contextPath("/api").header("Origin", ORIGIN)
			.header("X-MoneyToad-Demo", "1").contentType("application/json").content("{}");
	}
	private static MockHttpServletRequestBuilder preflight(String origin, String method, String headers) {
		return options("/api/auth/demo/login").contextPath("/api").header("Origin", origin)
			.header("Access-Control-Request-Method", method).header("Access-Control-Request-Headers", headers);
	}
	private static void remove(MockHttpServletRequestBuilder builder, String name) {
		builder.with(request -> {
			if (name.equalsIgnoreCase("Content-Type")) {
				// Mock keeps a separate contentType field; encoding changes can restore a removed header.
				request.setContentType(null);
				request.setAttribute(EXPECTED_CONTENT_TYPE_COUNT, 0);
			}
			request.removeHeader(name);
			return request;
		});
	}
	private static void replace(MockHttpServletRequestBuilder builder, String name, String... values) {
		builder.with(request -> {
			boolean contentType = name.equalsIgnoreCase("Content-Type");
			if (contentType) request.setContentType(null);
			request.removeHeader(name);
			if (contentType && values.length > 1) {
				// Mock's public addHeader treats Content-Type as a replacing setter. Populate only its
				// raw HeaderValueHolder; keeping the parsed field null prevents encoding normalization.
				ReflectionTestUtils.invokeMethod(request, "doAddHeaderValue", name, List.of(values), true);
			} else {
				for (String value : values) request.addHeader(name, value);
			}
			if (contentType) {
				request.setAttribute(EXPECTED_CONTENT_TYPE_COUNT, values.length);
				assertThat(Collections.list(request.getHeaders(name)).size()).isEqualTo(values.length);
			}
			return request;
		});
	}
	private static String access(MvcResult result) throws Exception {
		var json = DemoAuthHttpTestSupport.JSON.readTree(result.getResponse().getContentAsString());
		assertThat(json.size()).isEqualTo(1);
		assertThat(json.hasNonNull("accessToken")).isTrue();
		return json.get("accessToken").asText();
	}
	private static Cookie refresh(MvcResult result) {
		Cookie cookie = result.getResponse().getCookie("demoRefreshToken");
		assertThat(cookie != null).isTrue();
		return new Cookie("demoRefreshToken", cookie.getValue());
	}
	private static boolean hasEarlyGuardFailure(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause.getMessage() != null && cause.getMessage().startsWith("DEMO_AUTH_PROFILE: INVALID_HTTP_SETTINGS")) return true;
		}
		return false;
	}

	/** Captured session hashes and identifiers are never rendered in assertions or evidence. */
	private record Snapshot(Map<String, List<Map<String, Object>>> database,
		Map<String, Map<Object, Object>> sessions, Map<String, Long> expirations) {
		@Override public String toString() { return "Snapshot[redacted]"; }
	}
	private static final class Fixture implements AutoCloseable {
		final DemoAuthHttpTestSupport support = new DemoAuthHttpTestSupport();
		final DemoAuthHttpTestSupport.Started started = support.startDemo();
		final MockMvc mvc;
		final JdbcTemplate jdbc;
		final StringRedisTemplate redis;
		final UserRepository users;
		final DemoSessionService sessions;
		final JwtUtil jwt;
		final DemoSessionService.IssuedTokens fixtureTokens;
		final List<String> ownedSids = new ArrayList<>();
		Fixture() throws Exception {
			try {
			assertThat(started.failure() == null).as("Owned demo context starts").isTrue();
			var context = started.context();
			mvc = MockMvcBuilders.webAppContextSetup((GenericWebApplicationContext)context).apply(springSecurity()).build();
			jdbc = new JdbcTemplate(context.getBean(DataSource.class));
			redis = context.getBean(StringRedisTemplate.class);
			users = context.getBean(UserRepository.class);
			sessions = context.getBean(DemoSessionService.class);
			jwt = context.getBean(JwtUtil.class);
			var user = users.saveAndFlush(User.createUser("request-fixture@moneytoad.invalid", "Synthetic Request Fixture"));
			fixtureTokens = sessions.startForUser(user.getId());
			track(fixtureTokens.accessToken());
			} catch (RuntimeException | AssertionError failedSetup) {
				started.close();
				support.close();
				throw failedSetup;
			}
		}
		String track(String access) {
			String sid = jwt.validateDemoAccessToken(jwt.parse(access), Instant.now()).sid();
			ownedSids.add(sid);
			return sid;
		}
		MockHttpServletRequestBuilder post(String endpoint) {
			var request = validPost(endpoint);
			if (endpoint.equals("reissue")) request.cookie(new Cookie("demoRefreshToken", fixtureTokens.refreshToken()));
			if (endpoint.equals("logout")) request.header("Authorization", "Bearer " + fixtureTokens.accessToken());
			return request;
		}
		MockHttpServletRequestBuilder session() {
			return get("/api/auth/demo/session").contextPath("/api").header("Origin", ORIGIN)
				.header("Authorization", "Bearer " + fixtureTokens.accessToken());
		}
		Snapshot snapshot() {
			Map<String, Map<Object, Object>> hashes = new LinkedHashMap<>();
			Map<String, Long> expirations = new LinkedHashMap<>();
			Set<String> keys = redis.keys("demo:session:*");
			if (keys != null) for (String key : keys) {
				hashes.put(key, redis.opsForHash().entries(key));
				expirations.put(key, redis.execute(ABSOLUTE_EXPIRY, List.of(key)));
			}
			return new Snapshot(DemoAuthHttpTestSupport.snapshot(jdbc), hashes, expirations);
		}
		void unchanged(Snapshot before) {
			var after = snapshot();
			assertThat(before.database().equals(after.database())).as("Database rows unchanged").isTrue();
			assertThat(before.sessions().equals(after.sessions())).as("Redis session hashes and key set unchanged").isTrue();
			assertThat(before.expirations().equals(after.expirations())).as("Redis absolute expiration unchanged").isTrue();
			assertThat(support.noOutbound(started.probe())).isTrue();
		}
		void reject(MockHttpServletRequestBuilder request, int status) throws Exception {
			var before = snapshot();
			var result = mvc.perform(request).andReturn();
			Object expectedTypes = result.getRequest().getAttribute(EXPECTED_CONTENT_TYPE_COUNT);
			if (expectedTypes instanceof Integer count) {
				assertThat(Collections.list(result.getRequest().getHeaders("Content-Type")).size())
					.as("Exact raw Content-Type count remains through the real filter/MVC chain").isEqualTo(count);
			}
			assertThat(result.getResponse().getStatus()).isEqualTo(status);
			assertThat(result.getResponse().getHeaders("Set-Cookie").isEmpty()).isTrue();
			unchanged(before);
		}
		void emit(String scenario, int requests) throws Exception {
			assertThat(support.noOutbound(started.probe())).isTrue();
			DemoAuthHttpTestSupport.emit(Map.of("scenario", scenario, "matrixRequests", requests, "outboundRequests", 0));
		}
		@Override public void close() {
			try {
				if (!ownedSids.isEmpty()) redis.delete(ownedSids.stream().map(sid -> "demo:session:" + sid).toList());
			} finally { started.close(); support.close(); }
		}
	}
}
