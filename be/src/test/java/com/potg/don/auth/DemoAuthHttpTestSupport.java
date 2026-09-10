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
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.net.ServerSocket;
import java.util.Base64;
import java.security.SecureRandom;
import org.springframework.aop.framework.ProxyFactory;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import com.potg.don.auth.demo.DemoSessionService;
import com.potg.don.auth.demo.DemoSessionStore;
import com.potg.don.auth.demo.DemoAuthException;
import com.potg.don.auth.jwt.RefreshTokenStore;
import jakarta.servlet.http.Cookie;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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


/** Shared owned product ConfigData fixture. No alternative authentication or product service doubles. */
public class DemoAuthHttpTestSupport implements AutoCloseable {
	public static final ObjectMapper JSON = new ObjectMapper();
	private static final String TEST_FILTER = "org.springframework.boot.test.context.filter.TestTypeExcludeFilter";
	public static final List<String> TABLES = List.of("users", "cards", "transactions", "budgets", "analysis_job");
	private HttpServer stub;
	public final AtomicInteger stubRequests = new AtomicInteger();
	public int stubPort;

	public DemoAuthHttpTestSupport() throws IOException {
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

	@Override public void close() {
		if (stub != null) stub.stop(0);
	}

	public Started startDemo() {
		return start("demo", "", "true", "local-demo", false, Map.of());
	}

	public Started start(String active, String defaults, String enabled, String kind, boolean oauth, Map<String, Object> overrides, Class<?>... extraSources) {
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
		properties.putAll(overrides);
		StandardEnvironment environment = new StandardEnvironment() {
			@Override protected void customizePropertySources(MutablePropertySources sources) { }
		};
		environment.getPropertySources().addFirst(new MapPropertySource("owned-demo-auth-inputs", properties));
		List<Class<?>> sources = new ArrayList<>(List.of(DonApplication.class, BoundarySupport.class));
		sources.addAll(List.of(extraSources));
		SpringApplication app = new SpringApplication(sources.toArray(Class<?>[]::new));
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

	public static Map<String, List<Map<String, Object>>> snapshot(JdbcTemplate jdbc) {
		Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
		for (String table : TABLES) rows.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
		return rows;
	}

	public boolean noOutbound(Probe probe) {
		return probe.webClientBlocked.get() == 0 && probe.tokenBlocked.get() == 0
			&& probe.userInfoBlocked.get() == 0 && stubRequests.get() == 0;
	}

	public Map<String, Object> evidence(String scenario, Started started) {
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

	public static void emit(Map<String, Object> evidence) throws IOException {
		System.out.println("DEMO_AUTH_HTTP_EVIDENCE " + JSON.writeValueAsString(evidence));
	}

	public record Started(ConfigurableApplicationContext context, Throwable failure, Probe probe) implements AutoCloseable {
		@Override public void close() { if (context != null) context.close(); }
	}

	public static final class Probe {
		public final AtomicBoolean guardRegistered = new AtomicBoolean();
		public final AtomicBoolean singletonInitialized = new AtomicBoolean();
		public final AtomicBoolean dataSourceInitialized = new AtomicBoolean();
		public final AtomicInteger webClientBlocked = new AtomicInteger();
		public final AtomicInteger userLookups = new AtomicInteger();
		public final AtomicInteger tokenBlocked = new AtomicInteger();
		public final AtomicInteger userInfoBlocked = new AtomicInteger();
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
				@Override public Object postProcessAfterInitialization(Object bean, String name) {
					if (bean instanceof UserRepository) {
						ProxyFactory proxy = new ProxyFactory(bean);
						proxy.addAdvice((MethodInterceptor) invocation -> {
							if (invocation.getMethod().getName().equals("findById")) probe.userLookups.incrementAndGet();
							return invocation.proceed();
						});
						return proxy.getProxy();
					}
					return bean;
				}

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
