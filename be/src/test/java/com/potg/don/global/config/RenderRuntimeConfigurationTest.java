package com.potg.don.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.zaxxer.hikari.HikariConfig;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** Actual product ConfigData and early guard. No datasource, Redis or external network is created. */
class RenderRuntimeConfigurationTest {
	private record Rejection(String label, String key, String value, boolean osEnvironment) {
		@Override public String toString() { return label; }
	}

	@Test
	void loadsTheThreeProductYamlFilesAndBindsRenderSettings() {
		AtomicBoolean ordinarySingleton = new AtomicBoolean();
		runner(Map.of(), Map.of(), ordinarySingleton).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(ordinarySingleton).isTrue();
			var env = context.getEnvironment();
			List<String> names = new ArrayList<>();
			env.getPropertySources().forEach(source -> names.add(source.getName()));
			for (String file : List.of("application.yml", "application-demo.yml", "application-render.yml")) {
				assertThat(names.stream().anyMatch(name -> name.contains(file))).as(file + " was loaded by ConfigData").isTrue();
			}
			assertThat(env.getProperty("server.port", Integer.class)).isEqualTo(18082);
			assertThat(env.getProperty("server.address")).isEqualTo("0.0.0.0");
			assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
			assertThat(env.getProperty("spring.sql.init.mode")).isEqualTo("never");
			var pool = Binder.get(env).bind("spring.datasource.hikari", HikariConfig.class).get();
			assertThat(pool.getMaximumPoolSize()).isEqualTo(3);
			assertThat(pool.getMinimumIdle()).isZero();
			assertThat(pool.getMaxLifetime()).isEqualTo(300000);
			assertThat(pool.getKeepaliveTime()).isZero();
			var redis = Binder.get(env).bind("spring.data.redis", RedisProperties.class).get();
			assertThat(redis.getSsl().isEnabled()).isTrue();
			assertThat(redis.getConnectTimeout()).hasSeconds(3);
			assertThat(redis.getTimeout()).hasSeconds(2);
		});
	}

	static Stream<Rejection> rejectedSettings() {
		List<Rejection> cases = new ArrayList<>();
		for (String port : List.of("", "0", "-1", "65536", "1x", "1.5", " 18082", "999999999999999999")) {
			cases.add(new Rejection("invalid platform port " + cases.size(), "PORT", port, false));
		}
		for (String profile : List.of("render", "render,prod", "demo,render,prod", "demo,render,production")) {
			cases.add(new Rejection("profile conflict " + profile, "spring.profiles.active", profile, false));
		}
		Map<String, String> incompatible = new LinkedHashMap<>();
		incompatible.put("app.demo.enabled", "false");
		incompatible.put("app.deployment.kind", "local-demo");
		incompatible.put("app.demo.browser-origin", "http://127.0.0.1:5173");
		incompatible.put("server.port", "18083");
		incompatible.put("server.address", "127.0.0.1");
		incompatible.put("spring.jpa.hibernate.ddl-auto", "update");
		incompatible.put("spring.jpa.generate-ddl", "true");
		incompatible.put("spring.sql.init.mode", "always");
		incompatible.put("spring.jpa.database-platform", "org.hibernate.dialect.H2Dialect");
		incompatible.put("spring.jpa.properties.hibernate.hbm2ddl.auto", "create");
		incompatible.put("spring.jpa.properties.jakarta.persistence.schema-generation.database.action", "drop-and-create");
		incompatible.put("spring.jpa.properties.javax.persistence.schema-generation.database.action", "create");
		incompatible.put("spring.jpa.properties.hibernate.dialect", "org.hibernate.dialect.H2Dialect");
		incompatible.put("spring.jpa.properties.jakarta.persistence.nonJtaDataSource", "java:comp/env/jdbc/other");
		incompatible.put("spring.jpa.properties.hibernate.connection.url", "jdbc:mysql://127.0.0.1:3307/other");
		incompatible.put("spring.datasource.hikari.jdbc-url", "jdbc:mysql://127.0.0.1:3307/other");
		incompatible.put("spring.datasource.jndi-name", "java:comp/env/jdbc/other");
		incompatible.put("spring.datasource.hikari.data-source-class-name", "com.mysql.cj.jdbc.MysqlDataSource");
		incompatible.put("spring.datasource.hikari.username", "unexpected-override");
		incompatible.put("spring.datasource.hikari.password", "synthetic-override-only");
		incompatible.put("spring.datasource.hikari.data-source-properties.sslMode", "DISABLED");
		incompatible.put("spring.datasource.hikari.data-source-properties[verifyServerCertificate]", "false");
		incompatible.put("spring.datasource.hikari.data-source-properties.password", "synthetic-override-only");
		incompatible.put("spring.datasource.hikari.data-source-properties.socketTimeout", "0");
		incompatible.put("spring.datasource.hikari.connection-init-sql", "CREATE TABLE rejected_fixture (id int)");
		incompatible.put("spring.datasource.hikari.connection-test-query", "CREATE TABLE rejected_fixture (id int)");
		incompatible.put("spring.datasource.hikari.maximum-pool-size", "4");
		incompatible.put("spring.datasource.hikari.keepalive-time", "30000");
		incompatible.put("spring.datasource.username", "");
		incompatible.put("spring.datasource.password", "");
		incompatible.put("spring.data.redis.ssl.enabled", "false");
		incompatible.put("spring.data.redis.url", "redis://127.0.0.1:6380");
		incompatible.put("spring.data.redis.cluster.nodes[0]", "127.0.0.1:6380");
		incompatible.put("spring.data.redis.sentinel.master", "unapproved-master");
		incompatible.put("spring.data.redis.username", "");
		incompatible.put("spring.data.redis.password", "");
		incompatible.put("spring.data.redis.port", "0");
		incompatible.put("spring.data.redis.database", "1");
		incompatible.put("spring.data.redis.lettuce.read-from", "replica");
		incompatible.put("spring.data.redis.client-type", "jedis");
		incompatible.put("spring.data.redis.timeout", "1s");
		incompatible.put("spring.data.redis.connect-timeout", "1s");
		incompatible.forEach((key, value) -> cases.add(new Rejection("effective " + key, key, value, false)));
		Map.of("SERVER_PORT", "18083", "SPRING_JPA_HIBERNATE_DDLAUTO", "update",
			"SPRING_SQL_INIT_MODE", "always", "SPRING_DATA_REDIS_SSL_ENABLED", "false",
			"SPRING_JPA_DATABASEPLATFORM", "org.hibernate.dialect.H2Dialect",
			"SPRING_DATA_REDIS_URL", "redis://127.0.0.1:6380",
			"SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_SSLMODE", "DISABLED")
			.forEach((key, value) -> cases.add(new Rejection("OS override " + key, key, value, true)));
		return cases.stream();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("rejectedSettings")
	void rejectsEffectiveOverridesBeforeOrdinarySingletons(Rejection rejection) {
		AtomicBoolean ordinarySingleton = new AtomicBoolean();
		Map<String, Object> override = Map.of(rejection.key(), rejection.value());
		runner(rejection.osEnvironment() ? Map.of() : override,
			rejection.osEnvironment() ? override : Map.of(), ordinarySingleton).run(context -> {
			assertThat(context).hasFailed();
			assertThat(ordinarySingleton).isFalse();
			assertThat(hasGuardFailure(context.getStartupFailure())).as("sanitized early guard error").isTrue();
		});
	}

	@Test
	void missingPlatformPortFailsEvenWhenServerPortIsSeparatelySupplied() {
		AtomicBoolean sentinel = new AtomicBoolean();
		var inputs = validInputs();
		inputs.remove("PORT");
		runner(inputs, Map.of("server.port", "18082"), Map.of(), sentinel).run(context -> {
			assertThat(context).hasFailed();
			assertThat(sentinel).isFalse();
			assertThat(hasGuardFailure(context.getStartupFailure())).isTrue();
		});
	}

	static Stream<String> rejectedUrls() {
		String base = "jdbc:mysql://127.0.0.1:3307/render_fixture";
		return Stream.of(base, base + "?sslMode=REQUIRED", base + "?sslMode=VERIFY_CA", base + "?sslMode=DISABLED",
			base + "?sslMode=VERIFY_IDENTITY&sslMode=DISABLED", base + "?sslMode=VERIFY_IDENTITY&useSSL=false",
			base + "?sslMode=VERIFY_IDENTITY&verifyServerCertificate=false", base + "?sslMode=VERIFY_IDENTITY&password=" + java.util.UUID.randomUUID() + "",
			base + "?sslMode=VERIFY_IDENTITY&user=synthetic", base + "?sslMode=VERIFY_IDENTITY&socketTimeout=0",
			base + "?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:unapproved",
			base + "?sslMode=VERIFY_IDENTITY&readOnlyPropagatesToServer=false",
			"jdbc:mysql://synthetic:synthetic@127.0.0.1:3307/render_fixture?sslMode=VERIFY_IDENTITY",
			"jdbc:mysql:loadbalance://127.0.0.1:3307/render_fixture?sslMode=VERIFY_IDENTITY");
	}

	@ParameterizedTest(name = "unsafe JDBC settings case {index}")
	@MethodSource("rejectedUrls")
	void rejectsTlsAndCredentialBypassesWithoutEchoingSuppliedValues(String url) {
		runner(Map.of("spring.datasource.url", url), Map.of(), new AtomicBoolean()).run(context -> {
			assertThat(context).hasFailed();
			Throwable root = context.getStartupFailure();
			while (root.getCause() != null) root = root.getCause();
			assertThat(root.getMessage()).matches("RENDER_RUNTIME: [A-Z_]+");
		});
	}

	@Test
	void rejectedDriverClassNeverReachesHikariLoggingOrAnExceptionMessage() {
		String canary = "UnapprovedRuntimeDriverCanary";
		Logger logger = (Logger) LoggerFactory.getLogger(HikariConfig.class);
		Level previous = logger.getLevel();
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		logger.setLevel(Level.INFO);
		try {
			runner(Map.of("spring.datasource.hikari.driver-class-name", canary), Map.of(), new AtomicBoolean())
				.run(context -> {
					assertThat(context).hasFailed();
					Throwable root = context.getStartupFailure();
					for (Throwable failure = root; failure != null; failure = failure.getCause()) {
						assertThat(failure.getMessage() == null || !failure.getMessage().contains(canary)).isTrue();
					}
					while (root.getCause() != null) root = root.getCause();
					assertThat(root.getMessage()).isEqualTo("RENDER_RUNTIME: JDBC_OVERRIDE");
					assertThat(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains(canary))).isTrue();
				});
		} finally {
			logger.setLevel(previous);
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void profileOrderCannotSilentlyReplaceRenderBindOrTimeouts() {
		runner(Map.of("spring.profiles.active", "render,demo"), Map.of(), new AtomicBoolean()).run(context -> {
			assertThat(context).hasFailed();
			assertThat(hasGuardFailure(context.getStartupFailure())).isTrue();
		});
	}

	@Test
	void originalLocalDemoRemainsIndependentOfRenderSettings() {
		AtomicBoolean sentinel = new AtomicBoolean();
		var inputs = validInputs();
		inputs.remove("PORT");
		runner(inputs, Map.of("spring.profiles.active", "demo", "app.deployment.kind", "local-demo",
			"app.demo.browser-origin", "http://localhost:5173", "spring.datasource.url", "jdbc:mysql://127.0.0.1/test",
			"spring.data.redis.ssl.enabled", "false", "spring.jpa.hibernate.ddl-auto", "create-drop"), Map.of(), sentinel)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(sentinel).isTrue();
				assertThat(context.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
				assertThat(context.getEnvironment().getProperty("spring.data.redis.timeout")).isEqualTo("1s");
			});
	}

	private static ApplicationContextRunner runner(Map<String, Object> overrides, Map<String, Object> osOverrides, AtomicBoolean sentinel) {
		return runner(validInputs(), overrides, osOverrides, sentinel);
	}

	private static ApplicationContextRunner runner(Map<String, Object> inputs, Map<String, Object> overrides,
		Map<String, Object> osOverrides, AtomicBoolean sentinel) {
		return new ApplicationContextRunner().withInitializer(context -> {
			var env = context.getEnvironment();
			env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
			env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
			env.getPropertySources().addFirst(new MapPropertySource("owned-inputs", inputs));
			env.getPropertySources().addFirst(new MapPropertySource("effective-test-overrides", overrides));
			env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("owned-systemEnvironment", osOverrides));
			new ConfigDataApplicationContextInitializer().initialize(context);
		}).withBean("ordinarySingleton", Object.class, () -> { sentinel.set(true); return new Object(); })
			.withUserConfiguration(AuthProfileGuardConfiguration.class);
	}

	private static Map<String, Object> validInputs() {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("spring.config.location", "classpath:/application.yml");
		values.put("spring.profiles.active", "demo,render");
		values.put("PORT", "18082");
		values.put("APP_DEMO_ENABLED", "true");
		values.put("APP_DEPLOYMENT_KIND", "public-demo");
		values.put("DEMO_GATEWAY_SECRET", com.potg.don.auth.SyntheticGatewayTestSupport.secret());
		values.put("APP_DEMO_BROWSER_ORIGIN", "https://demo.example.invalid");
		values.put("DB_URL", "jdbc:mysql://127.0.0.1:3307/render_fixture?sslMode=VERIFY_IDENTITY");
		values.put("DB_USERNAME", "synthetic-render-user");
		values.put("DB_PASSWORD", "synthetic-render-password");
		values.put("JPA_DDL_AUTO", "update"); // Render overrides the ordinary placeholder with validate.
		values.put("REDIS_HOST", "127.0.0.1");
		values.put("REDIS_PORT", "6380");
		values.put("REDIS_USERNAME", "synthetic-render-user");
		values.put("REDIS_PASSWORD", "synthetic-render-password");
		values.put("REDIS_SSL_ENABLED", "true");
		return values;
	}

	private static boolean hasGuardFailure(Throwable error) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			String message = current.getMessage();
			if (message != null && (message.startsWith("RENDER_RUNTIME: ") || message.startsWith("DEMO_AUTH_PROFILE: "))) return true;
		}
		return false;
	}
}
