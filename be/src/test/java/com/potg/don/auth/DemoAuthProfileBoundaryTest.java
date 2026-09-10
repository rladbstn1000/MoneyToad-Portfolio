package com.potg.don.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potg.don.DonApplication;
import com.potg.don.auth.controller.AuthController;
import com.potg.don.auth.jwt.JwtAuthenticationFilter;
import com.potg.don.auth.jwt.JwtUtil;
import com.potg.don.auth.oauth.CustomOAuth2UserService;
import com.potg.don.auth.oauth.OAuth2SuccessHandler;
import com.potg.don.auth.service.AuthService;
import com.potg.don.transaction.client.CsvClient;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Mono;

/** Actual product ConfigData, security, JWT and MySQL. Only outbound HTTP boundaries are blocked. */
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoAuthProfileBoundaryTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String TEST_FILTER = "org.springframework.boot.test.context.filter.TestTypeExcludeFilter";
	private static final List<String> TABLES = List.of("users", "cards", "transactions", "budgets", "analysis_job");
	private HttpServer stub;
	private final AtomicInteger stubRequests = new AtomicInteger();
	private int stubPort;

	@BeforeEach
	void startOnlyOwnedLoopbackStub() throws IOException {
		stub = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
		stubPort = stub.getAddress().getPort();
		stub.createContext("/", exchange -> {
			stubRequests.incrementAndGet();
			try (exchange) {
				exchange.sendResponseHeaders(502, -1);
			}
		});
		stub.start();
	}

	@AfterEach
	void stopOnlyOwnedStub() {
		if (stub != null) stub.stop(0);
	}

	@Order(1)
	@ParameterizedTest(name = "original OAuth control {0} preserves real JWT and service behavior")
	@ValueSource(strings = {"default", "prod", "production"})
	void originalOAuthControl(String profile) throws Exception {
		try (Started started = start(profile.equals("default") ? "" : profile, "", null, null, true)) {
			verifySuccessfulBoundary("original_" + profile, started, false);
		}
	}

	static Stream<Arguments> demoCases() {
		return Stream.of(Arguments.of("demo_local", "demo", "", "local-demo"),
			Arguments.of("demo_public", "demo", "", "public-demo"),
			Arguments.of("demo_default_profile", "", "demo", "local-demo"));
	}

	@Order(2)
	@ParameterizedTest(name = "{0} loads product demo configuration without SSAFY inputs")
	@MethodSource("demoCases")
	void demoWithoutOAuth(String scenario, String active, String defaults, String kind) throws Exception {
		try (Started started = start(active, defaults, "true", kind, false)) {
			verifySuccessfulBoundary(scenario, started, true);
		}
	}

	private record InvalidMode(String scenario, String active, String defaults, String enabled, String kind) { }

	@Order(3)
	@Test
	void guardRejectsEveryInconsistentModeBeforeNormalSingletonOrDatabaseInitialization() throws Exception {
		List<InvalidMode> cases = List.of(
			new InvalidMode("demo_flag_missing", "demo", "", null, "local-demo"),
			new InvalidMode("demo_flag_false", "demo", "", "false", "local-demo"),
			new InvalidMode("demo_kind_missing", "demo", "", "true", null),
			new InvalidMode("demo_kind_standard", "demo", "", "true", "standard"),
			new InvalidMode("demo_bad_boolean", "demo", "", "maybe", "local-demo"),
			new InvalidMode("demo_bad_kind", "demo", "", "true", "unknown"),
			new InvalidMode("normal_flag_true", "", "", "true", "standard"),
			new InvalidMode("normal_local_kind", "", "", "false", "local-demo"),
			new InvalidMode("normal_public_kind", "", "", "false", "public-demo"),
			new InvalidMode("normal_bad_boolean", "", "", "maybe", "standard"),
			new InvalidMode("normal_bad_kind", "", "", "false", "unknown"),
			new InvalidMode("demo_plus_prod", "demo,prod", "", "true", "local-demo"),
			new InvalidMode("demo_plus_production", "demo,production", "", "true", "public-demo"),
			new InvalidMode("boolean_yes", "demo", "", "yes", "local-demo"),
			new InvalidMode("boolean_on", "demo", "", "on", "local-demo"),
			new InvalidMode("boolean_one", "demo", "", "1", "local-demo"),
			new InvalidMode("boolean_empty", "demo", "", "", "local-demo"),
			new InvalidMode("default_demo_false", "", "demo", "false", "local-demo"));
		List<Executable> assertions = new ArrayList<>();
		for (InvalidMode mode : cases) {
			// Supply synthetic OAuth even for inconsistent demo cases. Before the
			// product fix they must start, rather than fail accidentally on missing SSAFY.
			try (Started started = start(mode.active(), mode.defaults(), mode.enabled(), mode.kind(), true)) {
				boolean rejected = started.failure != null;
				boolean guardFailure = hasGuardPrefix(started.failure);
				boolean guardRegistered = started.probe.guardRegistered.get();
				boolean sentinelUntouched = !started.probe.singletonInitialized.get();
				boolean databaseUntouched = !started.probe.dataSourceInitialized.get();
				boolean noOutbound = noOutbound(started.probe);
				var evidence = evidence(mode.scenario(), started);
				evidence.put("guardRejected", guardFailure);
				evidence.put("normalSingletonUntouched", sentinelUntouched);
				evidence.put("databaseInitializationUntouched", databaseUntouched);
				emit(evidence);
				assertions.add(() -> assertAll(mode.scenario(),
					() -> assertThat(rejected).as("startup rejected").isTrue(),
					() -> assertThat(guardRegistered).as("authProfileGuard definition").isTrue(),
					() -> assertThat(guardFailure).as("DEMO_AUTH_PROFILE failure identity").isTrue(),
					() -> assertThat(sentinelUntouched).isTrue(), () -> assertThat(databaseUntouched).isTrue(),
					() -> assertThat(noOutbound).isTrue()));
			}
		}
		assertAll(assertions);
	}

	@Order(4)
	@Test
	void defaultOAuthMissingConfigurationDoesNotFallBackToDemo() throws Exception {
		try (Started started = start("", "", null, null, false)) {
			// The original binder can retain unresolved OAuth placeholders and start.
			// Preserve that behavior; this contract forbids switching to demo, not
			// a new strict validation policy for the original OAuth configuration.
			boolean originalMode = started.failure == null && !started.context.getEnvironment()
				.acceptsProfiles(org.springframework.core.env.Profiles.of("demo"));
			int registrations = started.failure == null
				? started.context.getBeansOfType(ClientRegistrationRepository.class).size() : 0;
			int userServices = started.failure == null
				? started.context.getBeansOfType(CustomOAuth2UserService.class).size() : 0;
			int successHandlers = started.failure == null
				? started.context.getBeansOfType(OAuth2SuccessHandler.class).size() : 0;
			long oauthFilters = started.failure == null ? started.context.getBeansOfType(SecurityFilterChain.class)
				.values().stream().flatMap(chain -> chain.getFilters().stream()).filter(filter ->
					filter.getClass().getSimpleName().equals("OAuth2AuthorizationRequestRedirectFilter")
						|| filter.getClass().getSimpleName().equals("OAuth2LoginAuthenticationFilter")).count() : 0;
			boolean demoChainAbsent = started.failure == null && !started.context.containsBean("demoFilterChain");
			long demoMappings = started.failure == null ? started.context.getBean(RequestMappingHandlerMapping.class)
				.getHandlerMethods().keySet().stream().flatMap(info -> info.getPatternValues().stream())
				.filter(path -> path.startsWith("/auth/demo") || path.equals("/demo") || path.startsWith("/demo/")).count() : -1;
			var evidence = evidence("default_oauth_missing", started);
			evidence.put("guardRejected", hasGuardPrefix(started.failure));
			evidence.put("originalModePreserved", originalMode);
			evidence.put("clientRegistrationRepositoryCount", registrations);
			evidence.put("oauthUserServiceCount", userServices);
			evidence.put("oauthSuccessHandlerCount", successHandlers);
			evidence.put("oauthLoginFilterCount", oauthFilters);
			evidence.put("demoChainAbsent", demoChainAbsent);
			evidence.put("demoEndpointCount", demoMappings);
			emit(evidence);
			assertAll(() -> assertThat(originalMode).isTrue(),
				() -> assertThat(registrations).isEqualTo(1), () -> assertThat(userServices).isEqualTo(1),
				() -> assertThat(successHandlers).isEqualTo(1), () -> assertThat(oauthFilters).isEqualTo(2),
				() -> assertThat(demoChainAbsent).isTrue(), () -> assertThat(demoMappings).isZero(),
				() -> assertThat(hasGuardPrefix(started.failure)).isFalse(),
				() -> assertThat(noOutbound(started.probe)).isTrue());
		}
	}

	@Order(5)
	@Test
	void outboundBlockersRejectBeforeDnsOrHttpUsingRealClientBoundaries() throws Exception {
		try (Started started = start("", "", null, null, true)) {
			boolean startedSuccessfully = started.failure == null;
			if (startedSuccessfully) {
				try {
					started.context.getBean(WebClient.class).get().uri("http://demo-auth-blocked.invalid/probe")
						.retrieve().bodyToMono(String.class).block();
				} catch (RuntimeException | AssertionError expected) { }
				try {
					started.context.getBean("blockedOAuthTokenClient", OAuth2AccessTokenResponseClient.class)
						.getTokenResponse(null);
				} catch (RuntimeException | AssertionError expected) { }
				var registration = started.context.getBean(ClientRegistrationRepository.class).findByRegistrationId("ssafy");
				try {
					started.context.getBean(CustomOAuth2UserService.class).loadUser(new OAuth2UserRequest(registration,
						new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "synthetic-probe-token",
							Instant.now(), Instant.now().plusSeconds(60))));
				} catch (RuntimeException | AssertionError expected) { }
			}
			var evidence = evidence("outbound_guard_self_test", started);
			emit(evidence);
			assertAll(() -> assertThat(startedSuccessfully).isTrue(),
				() -> assertThat(started.probe.webClientBlocked.get()).isEqualTo(1),
				() -> assertThat(started.probe.tokenBlocked.get()).isEqualTo(1),
				() -> assertThat(started.probe.userInfoBlocked.get()).isEqualTo(1),
				() -> assertThat(stubRequests.get()).isZero());
		}
	}

	private void verifySuccessfulBoundary(String scenario, Started started, boolean demo) throws Exception {
		if (started.failure != null) {
			emit(evidence(scenario, started));
			assertThat(false).as("product context must start for " + scenario).isTrue();
			return;
		}
		var context = started.context;
		var environment = context.getEnvironment();
		boolean productYaml = sourceNamed(context, "application.yml");
		boolean demoYaml = sourceNamed(context, "application-demo.yml");
		boolean inheritedA1Absent = !sourceNamed(context, "application-a1.yml")
			&& !environment.acceptsProfiles(org.springframework.core.env.Profiles.of("a1"));
		boolean isolatedSources = !environment.getPropertySources().contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
			&& !environment.getPropertySources().contains(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		int userServices = context.getBeansOfType(CustomOAuth2UserService.class).size();
		int successHandlers = context.getBeansOfType(OAuth2SuccessHandler.class).size();
		int registrations = context.getBeansOfType(ClientRegistrationRepository.class).size();
		int authorizedServices = context.getBeansOfType(OAuth2AuthorizedClientService.class).size();
		int authorizedRepositories = context.getBeansOfType(OAuth2AuthorizedClientRepository.class).size();
		int authControllers = context.getBeansOfType(AuthController.class).size();
		int authServices = context.getBeansOfType(AuthService.class).size();
		var chains = context.getBeansOfType(SecurityFilterChain.class).values();
		boolean jwtInChain = chains.size() == 1 && chains.iterator().next().getFilters()
			.contains(context.getBean(JwtAuthenticationFilter.class));
		long oauthFilters = chains.stream().flatMap(chain -> chain.getFilters().stream()).filter(filter ->
			filter.getClass().getSimpleName().equals("OAuth2AuthorizationRequestRedirectFilter")
				|| filter.getClass().getSimpleName().equals("OAuth2LoginAuthenticationFilter")).count();
		boolean noOAuthProperties = !environment.containsProperty("SSAFY_CLIENT_ID")
			&& !environment.containsProperty("spring.security.oauth2.client.registration.ssafy.client-id");
		var mappings = context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods();
		long authMappings = mappings.values().stream().filter(handler -> handler.getBeanType() == AuthController.class).count();
		long demoMappings = mappings.keySet().stream().flatMap(info -> info.getPatternValues().stream())
			.filter(path -> path.startsWith("/auth/demo") || path.equals("/demo") || path.startsWith("/demo/")).count();
		boolean noMockProducts = !org.mockito.Mockito.mockingDetails(context.getBean(CsvClient.class)).isMock()
			&& context.getBeansOfType(CustomOAuth2UserService.class).values().stream()
				.noneMatch(bean -> org.mockito.Mockito.mockingDetails(bean).isMock())
			&& context.getBeansOfType(OAuth2SuccessHandler.class).values().stream()
				.noneMatch(bean -> org.mockito.Mockito.mockingDetails(bean).isMock());
		JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_job", Integer.class)).isZero();
		var repository = context.getBean(UserRepository.class);
		User owner = repository.saveAndFlush(User.createUser("profile-owner@demo.invalid", "Synthetic Owner"));
		repository.saveAndFlush(User.createUser("profile-other@demo.invalid", "Synthetic Other"));
		var before = snapshot(jdbc);
		String token = demo
			? context.getBean(com.potg.don.auth.demo.DemoSessionService.class).startForUser(owner.getId()).accessToken()
			: context.getBean(JwtUtil.class).createAccessToken(owner.getId(), owner.getEmail());
		MockMvc mvc = MockMvcBuilders.webAppContextSetup((GenericWebApplicationContext)context).apply(springSecurity()).build();
		var authenticated = mvc.perform(get("/api/users").contextPath("/api").header("Authorization", "Bearer " + token)).andReturn();
		var unauthenticated = mvc.perform(get("/api/users").contextPath("/api")).andReturn();
		boolean principalMatches = authenticated.getResponse().getStatus() == 200
			&& JSON.readTree(authenticated.getResponse().getContentAsString()).path("email").asText().equals(owner.getEmail())
			&& JSON.readTree(authenticated.getResponse().getContentAsString()).path("name").asText().equals(owner.getName());
		boolean databaseUnchanged = before.equals(snapshot(jdbc));
		boolean oauthRedirectMatches = false;
		if (!demo) {
			var redirect = mvc.perform(get("/api/oauth2/authorization/ssafy").contextPath("/api")).andReturn();
			String location = redirect.getResponse().getHeader("Location");
			if (redirect.getResponse().getStatus() == 302 && location != null) {
				URI uri = URI.create(location);
				oauthRedirectMatches = "127.0.0.1".equals(uri.getHost()) && uri.getPort() == stubPort
					&& "/authorize".equals(uri.getPath());
			}
		}
		var evidence = evidence(scenario, started);
		evidence.put("productYamlLoaded", productYaml);
		evidence.put("demoYamlLoaded", demoYaml);
		evidence.put("a1ConfigurationAbsent", inheritedA1Absent);
		evidence.put("systemSourcesAbsent", isolatedSources);
		evidence.put("testTypeFilterPresent", context.containsBean(TEST_FILTER));
		evidence.put("oauthUserServiceCount", userServices);
		evidence.put("oauthSuccessHandlerCount", successHandlers);
		evidence.put("clientRegistrationRepositoryCount", registrations);
		evidence.put("authorizedClientServiceCount", authorizedServices);
		evidence.put("authorizedClientRepositoryCount", authorizedRepositories);
		evidence.put("authControllerCount", authControllers);
		evidence.put("authServiceCount", authServices);
		evidence.put("authMappingCount", authMappings);
		evidence.put("demoEndpointCount", demoMappings);
		evidence.put("securityChainCount", chains.size());
		evidence.put("jwtFilterPresent", jwtInChain);
		evidence.put("oauthLoginFilterCount", oauthFilters);
		evidence.put("oauthPropertiesAbsent", noOAuthProperties);
		evidence.put("productBeansNotMocked", noMockProducts);
		evidence.put("authenticatedStatus", authenticated.getResponse().getStatus());
		evidence.put("unauthenticatedStatus", unauthenticated.getResponse().getStatus());
		evidence.put("principalMatches", principalMatches);
		evidence.put("databaseUnchanged", databaseUnchanged);
		evidence.put("oauthRedirectMatches", oauthRedirectMatches);
		emit(evidence);
		if (demo) {
			String sid = context.getBean(JwtUtil.class).parse(token).getPayload().get("sid", String.class);
			context.getBean(com.potg.don.auth.demo.DemoSessionService.class).revoke(sid);
		}
		boolean originalRedirectMatches = oauthRedirectMatches;
		int expected = demo ? 0 : 1;
		assertAll(() -> assertThat(productYaml).isTrue(), () -> assertThat(demoYaml).isEqualTo(demo),
			() -> assertThat(inheritedA1Absent && isolatedSources).isTrue(),
			() -> assertThat(context.containsBean(TEST_FILTER)).isTrue(),
			() -> assertThat(started.probe.singletonInitialized.get()).isTrue(),
			() -> assertThat(userServices).isEqualTo(expected), () -> assertThat(successHandlers).isEqualTo(expected),
			() -> assertThat(registrations).isEqualTo(expected), () -> assertThat(authorizedServices).isEqualTo(expected),
			() -> assertThat(authorizedRepositories).isEqualTo(expected), () -> assertThat(authControllers).isEqualTo(expected),
			() -> assertThat(authServices).isEqualTo(expected), () -> assertThat(authMappings).isEqualTo(demo ? 0 : 2),
			() -> assertThat(demoMappings).isEqualTo(demo ? 4 : 0),
			() -> assertThat(mappings.keySet().stream().filter(info -> info.getPatternValues().stream().anyMatch(path -> path.startsWith("/auth/demo")))
				.flatMap(info -> info.getPatternValues().stream().flatMap(path -> info.getMethodsCondition().getMethods().stream().map(method -> method.name() + " " + path)))
				.collect(java.util.stream.Collectors.toSet())).isEqualTo(demo ? java.util.Set.of("POST /auth/demo/login", "POST /auth/demo/reissue", "GET /auth/demo/session", "POST /auth/demo/logout") : java.util.Set.of()),
			() -> assertThat(jwtInChain).isTrue(),
			() -> assertThat(oauthFilters).isEqualTo(demo ? 0 : 2),
			() -> assertThat(noOAuthProperties).isEqualTo(demo), () -> assertThat(noMockProducts).isTrue(),
			() -> assertThat(principalMatches).isTrue(), () -> assertThat(unauthenticated.getResponse().getStatus()).isEqualTo(401),
			() -> assertThat(databaseUnchanged).isTrue(), () -> assertThat(demo || originalRedirectMatches).isTrue(),
			() -> assertThat(noOutbound(started.probe)).isTrue());
	}

	private Started start(String active, String defaults, String enabled, String kind, boolean oauth) {
		Probe probe = new Probe();
		Map<String, Object> properties = infrastructure();
		properties.put("AI_BASE_URL", "http://127.0.0.1:" + stubPort);
		properties.put("logging.level.root", "OFF");
		properties.put("app.demo.browser-origin", "public-demo".equals(kind) ? "https://demo.example.invalid" : "http://localhost:5173");
		properties.put("spring.sql.init.mode", "never");
		if (enabled != null) properties.put("app.demo.enabled", enabled);
		if (kind != null) properties.put("app.deployment.kind", kind);
		if (oauth) {
			String origin = "http://127.0.0.1:" + stubPort;
			properties.put("SSAFY_CLIENT_ID", "synthetic-profile-client");
			properties.put("SSAFY_CLIENT_SECRET", "synthetic-profile-secret");
			properties.put("SSAFY_CLIENT_NAME", "synthetic-profile");
			properties.put("SSAFY_AUTH_GRANT_TYPE", "authorization_code");
			properties.put("SSAFY_REDIRECT_URI", origin + "/login/oauth2/code/ssafy");
			properties.put("SSAFY_SCOPES", "profile");
			properties.put("SSAFY_CLIENT_AUTH_METHOD", "client_secret_post");
			properties.put("SSAFY_AUTH_URI", origin + "/authorize");
			properties.put("SSAFY_TOKEN_URI", origin + "/token");
			properties.put("SSAFY_USER_INFO_URI", origin + "/userinfo");
			properties.put("SSAFY_USER_NAME_ATTR", "id");
		}
		StandardEnvironment environment = new StandardEnvironment() {
			@Override protected void customizePropertySources(MutablePropertySources sources) { }
		};
		environment.getPropertySources().addFirst(new MapPropertySource("owned-demo-auth-inputs", properties));
		SpringApplication app = new SpringApplication(DonApplication.class, BoundarySupport.class);
		app.setEnvironment(environment);
		app.setWebApplicationType(WebApplicationType.SERVLET);
		app.setApplicationContextFactory(type -> {
			GenericWebApplicationContext context = new GenericWebApplicationContext();
			context.setServletContext(new MockServletContext());
			return context;
		});
		app.setRegisterShutdownHook(false);
		app.setLogStartupInfo(false);
		app.setBannerMode(Banner.Mode.OFF);
		// Keep SpringApplication's factory initializers, including TestTypeExcludeFilter.
		app.addInitializers(context -> {
			context.getBeanFactory().registerSingleton("demoAuthProbe", probe);
			context.addBeanFactoryPostProcessor(factory -> probe.guardRegistered.set(factory.containsBeanDefinition("authProfileGuard")));
		});
		List<String> args = new ArrayList<>();
		args.add("--spring.config.location=classpath:/application.yml");
		if (!active.isEmpty()) args.add("--spring.profiles.active=" + active);
		if (!defaults.isEmpty()) args.add("--spring.profiles.default=" + defaults);
		try {
			return new Started(app.run(args.toArray(String[]::new)), null, probe);
		} catch (RuntimeException | AssertionError failure) {
			return new Started(null, failure, probe);
		}
	}

	private static Map<String, Object> infrastructure() {
		String url = required("A1_DB_URL");
		boolean safe = false;
		try {
			URI uri = URI.create(url.substring("jdbc:".length()));
			safe = url.startsWith("jdbc:mysql://") && "127.0.0.1".equals(uri.getHost())
				&& uri.getPort() > 0 && uri.getPort() <= 65535 && uri.getUserInfo() == null
				&& uri.getPath().matches("/moneytoad_a1_[a-z0-9_]+");
		} catch (RuntimeException ignored) { }
		int redisPort;
		try { redisPort = Integer.parseInt(required("A1_REDIS_PORT")); }
		catch (RuntimeException failure) { throw new IllegalStateException("Owned Redis test port required"); }
		if (!safe || redisPort <= 0 || redisPort > 65535 || redisPort == 6379) {
			throw new IllegalStateException("Owned loopback test infrastructure required");
		}
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("DB_URL", url);
		properties.put("DB_USERNAME", required("A1_DB_USERNAME"));
		properties.put("DB_PASSWORD", required("A1_DB_PASSWORD"));
		properties.put("JPA_DDL_AUTO", "create-drop");
		properties.put("REDIS_HOST", "127.0.0.1");
		properties.put("REDIS_PORT", redisPort);
		properties.put("JWT_SECRET", required("A1_JWT_SECRET"));
		properties.put("JWT_ACCESS_SECONDS", 300);
		properties.put("JWT_REFRESH_SECONDS", 600);
		properties.put("JWT_ISSUER", "demo-auth-profile-verification");
		return properties;
	}

	private static String required(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) throw new IllegalStateException("Required owned test input is missing: " + name);
		return value;
	}

	private static boolean sourceNamed(ConfigurableApplicationContext context, String filename) {
		return StreamSupport.sources(context).anyMatch(source -> source.contains("'" + filename + "'")
			|| source.contains("/" + filename) || source.contains("[" + filename + "]"));
	}

	private static final class StreamSupport {
		static Stream<String> sources(ConfigurableApplicationContext context) {
			return java.util.stream.StreamSupport.stream(context.getEnvironment().getPropertySources().spliterator(), false)
				.map(org.springframework.core.env.PropertySource::getName);
		}
	}

	private static boolean hasGuardPrefix(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause.getMessage() != null && cause.getMessage().startsWith("DEMO_AUTH_PROFILE:")) return true;
		}
		return false;
	}

	private static Map<String, List<Map<String, Object>>> snapshot(JdbcTemplate jdbc) {
		Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
		for (String table : TABLES) rows.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
		return rows;
	}

	private boolean noOutbound(Probe probe) {
		return probe.webClientBlocked.get() == 0 && probe.tokenBlocked.get() == 0
			&& probe.userInfoBlocked.get() == 0 && stubRequests.get() == 0;
	}

	private Map<String, Object> evidence(String scenario, Started started) {
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("scenario", scenario);
		evidence.put("contextStarted", started.failure == null);
		boolean ssafyFailure = false;
		Throwable rootCause = started.failure;
		for (Throwable cause = started.failure; cause != null; cause = cause.getCause()) {
			rootCause = cause;
			ssafyFailure |= cause.getMessage() != null && cause.getMessage().contains("SSAFY_");
		}
		evidence.put("ssafyConfigurationFailure", ssafyFailure);
		if (rootCause != null) evidence.put("failureRootType", rootCause.getClass().getName());
		evidence.put("guardBeanRegistered", started.probe.guardRegistered.get());
		evidence.put("normalSingletonInitialized", started.probe.singletonInitialized.get());
		evidence.put("dataSourceInitialized", started.probe.dataSourceInitialized.get());
		evidence.put("webClientBlocked", started.probe.webClientBlocked.get());
		evidence.put("oauthTokenBlocked", started.probe.tokenBlocked.get());
		evidence.put("oauthUserInfoBlocked", started.probe.userInfoBlocked.get());
		evidence.put("stubRequests", stubRequests.get());
		evidence.put("stubPort", stubPort);
		return evidence;
	}

	private static void emit(Map<String, Object> evidence) throws IOException {
		System.out.println("DEMO_AUTH_PROFILE_EVIDENCE " + JSON.writeValueAsString(evidence));
	}

	private record Started(ConfigurableApplicationContext context, Throwable failure, Probe probe) implements AutoCloseable {
		@Override public void close() { if (context != null) context.close(); }
	}

	static final class Probe {
		final AtomicBoolean guardRegistered = new AtomicBoolean();
		final AtomicBoolean singletonInitialized = new AtomicBoolean();
		final AtomicBoolean dataSourceInitialized = new AtomicBoolean();
		final AtomicInteger webClientBlocked = new AtomicInteger();
		final AtomicInteger tokenBlocked = new AtomicInteger();
		final AtomicInteger userInfoBlocked = new AtomicInteger();
	}

	static final class OrdinarySingleton { }

	@TestConfiguration(proxyBeanMethods = false)
	static class BoundarySupport {
		@Bean OrdinarySingleton normalSingletonSentinel(Probe probe) {
			probe.singletonInitialized.set(true);
			return new OrdinarySingleton();
		}

		@Bean WebClientCustomizer outboundWebClientBlocker(Probe probe) {
			return builder -> builder.filter((request, next) -> {
				probe.webClientBlocked.incrementAndGet();
				return Mono.error(new AssertionError("DEMO_AUTH_TEST: HTTP blocked before connector"));
			});
		}

		@Bean
		@Profile("!demo")
		OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> blockedOAuthTokenClient(Probe probe) {
			return request -> {
				probe.tokenBlocked.incrementAndGet();
				throw new IllegalStateException("DEMO_AUTH_TEST: OAuth token request blocked");
			};
		}

		@Bean static BeanPostProcessor outboundUserInfoBlockerAndInitializationProbe(Probe probe) {
			return new BeanPostProcessor() {
				@Override public Object postProcessBeforeInitialization(Object bean, String name) {
					if (bean instanceof DataSource) probe.dataSourceInitialized.set(true);
					if (bean instanceof CustomOAuth2UserService) {
						Object delegate = ReflectionTestUtils.getField(bean, "delegate");
						if (!(delegate instanceof DefaultOAuth2UserService service)) {
							throw new IllegalStateException("DEMO_AUTH_TEST: expected actual OAuth delegate");
						}
						service.setRestOperations(new RestTemplate((uri, method) -> {
							probe.userInfoBlocked.incrementAndGet();
							throw new IOException("DEMO_AUTH_TEST: OAuth userinfo request blocked");
						}));
					}
					return bean;
				}
			};
		}
	}
}
