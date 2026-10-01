package com.potg.don.global.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import com.zaxxer.hikari.HikariConfig;

/** Checks final bound settings before any ordinary singleton or connection is created. */
final class RenderRuntimeSettings {
	private static final String DIALECT = "org.hibernate.dialect.MySQLDialect";
	private static final Map<String, String> DRIVER_SETTINGS = Map.of(
		"connectTimeout", "5000", "socketTimeout", "10000", "connectionTimeZone", "Asia/Seoul",
		"forceConnectionTimeZoneToSession", "true");

	private RenderRuntimeSettings() { }

	static void validate(Environment environment) {
		try {
			check(environment.acceptsProfiles(Profiles.of("demo"))
				&& "true".equals(environment.getProperty("app.demo.enabled"))
				&& "public-demo".equals(environment.getProperty("app.deployment.kind")), "PROFILE");
			Binder binder = Binder.get(environment);
			String platformPort = environment.getProperty("PORT");
			check(platformPort != null && platformPort.matches("[1-9][0-9]{0,4}"), "PORT");
			int port = Integer.parseInt(platformPort);
			check(port <= 65535 && binder.bind("server.port", Integer.class).orElse(-1) == port, "PORT");
			check("0.0.0.0".equals(binder.bind("server.address", String.class).orElse(null)), "BIND");
			validateSchema(binder);
			validateJdbc(binder);
			validateRedis(binder);
		} catch (RuntimeException failure) {
			// Bind/URI errors can contain supplied credentials. Never chain or echo them.
			if (failure instanceof IllegalStateException && failure.getMessage() != null
				&& failure.getMessage().matches("RENDER_RUNTIME: [A-Z_]+")) throw failure;
			throw invalid("INVALID_SETTINGS");
		}
	}

	private static void validateSchema(Binder binder) {
		// Binder uses the same relaxed OS-variable precedence as Boot configuration beans.
		check("validate".equals(binder.bind("spring.jpa.hibernate.ddl-auto", String.class).orElse(null)), "DDL");
		check(!binder.bind("spring.jpa.generate-ddl", Boolean.class).orElse(false), "DDL");
		check("never".equals(binder.bind("spring.sql.init.mode", String.class).orElse(null)), "SQL_INIT");
		check(DIALECT.equals(binder.bind("spring.jpa.database-platform", String.class).orElse(null)), "DIALECT");
		Map<String, String> properties = binder.bind("spring.jpa.properties", Bindable.mapOf(String.class, String.class))
			.orElse(Map.of());
		for (var property : properties.entrySet()) {
			String name = property.getKey().toLowerCase(java.util.Locale.ROOT);
			if (name.equals("hibernate.hbm2ddl.auto")) check("validate".equals(property.getValue()), "DDL");
			else if (name.startsWith("hibernate.hbm2ddl.") || name.contains("schema-generation")) {
				throw invalid("DDL_OVERRIDE");
			} else if (name.equals("hibernate.dialect")) check(DIALECT.equals(property.getValue()), "DIALECT");
			else if (name.startsWith("hibernate.connection.") || name.startsWith("hibernate.hikari.")
				|| name.startsWith("jakarta.persistence.jdbc.") || name.startsWith("javax.persistence.jdbc.")
				|| name.endsWith(".jtadatasource") || name.endsWith(".nonjtadatasource")) {
				throw invalid("JDBC_OVERRIDE");
			}
		}
	}

	private static void validateJdbc(Binder binder) {
		DataSourceProperties source = binder.bind("spring.datasource", DataSourceProperties.class)
			.orElseThrow(() -> invalid("JDBC"));
		check(nonblank(source.getUsername()) && nonblank(source.getPassword()), "JDBC_CREDENTIALS");
		check(source.getJndiName() == null && source.getXa().getDataSourceClassName() == null
			&& source.getXa().getProperties().isEmpty(), "JDBC_OVERRIDE");
		check(source.getType() == null || source.getType().getName().equals("com.zaxxer.hikari.HikariDataSource"), "JDBC_OVERRIDE");
		check(source.getDriverClassName() == null || source.getDriverClassName().equals("com.mysql.cj.jdbc.Driver"), "JDBC_OVERRIDE");
		validateJdbcUrl(source.getUrl());

		// Hikari's driver setter can log a rejected class name before binding throws.
		// Validate this scalar first so arbitrary configuration values never reach it.
		String driverClass = binder.bind("spring.datasource.hikari.driver-class-name", String.class).orElse(null);
		check(driverClass == null || "com.mysql.cj.jdbc.Driver".equals(driverClass), "JDBC_OVERRIDE");
		HikariConfig pool = binder.bind("spring.datasource.hikari", HikariConfig.class)
			.orElseThrow(() -> invalid("POOL"));
		check(pool.getJdbcUrl() == null && pool.getDataSourceClassName() == null && pool.getDataSourceJNDI() == null
			&& pool.getUsername() == null && pool.getPassword() == null && pool.getConnectionInitSql() == null
			&& pool.getConnectionTestQuery() == null && (pool.getDriverClassName() == null
				|| "com.mysql.cj.jdbc.Driver".equals(pool.getDriverClassName())),
			"JDBC_OVERRIDE");
		check(pool.getMaximumPoolSize() == 3 && pool.getMinimumIdle() == 0 && pool.getMaxLifetime() == 300000
			&& pool.getConnectionTimeout() == 5000 && pool.getValidationTimeout() == 2000 && pool.getKeepaliveTime() == 0,
			"POOL");
		// A second properties channel must not override TLS or silently supply credentials.
		Map<String, String> actual = new HashMap<>();
		pool.getDataSourceProperties().forEach((key, value) -> actual.put(key.toString(), value.toString()));
		check(actual.equals(DRIVER_SETTINGS), "JDBC_PROPERTIES");
	}

	private static void validateJdbcUrl(String url) {
		check(nonblank(url) && url.startsWith("jdbc:mysql://"), "JDBC_URL");
		URI uri = URI.create(url.substring(5));
		check(uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawFragment() == null
			&& uri.getPort() != 0 && uri.getPort() <= 65535 && uri.getRawPath() != null
			&& uri.getRawPath().matches("/[A-Za-z0-9_-]+"), "JDBC_URL");
		Map<String, String> query = new HashMap<>();
		check(uri.getRawQuery() != null, "JDBC_TLS");
		for (String pair : uri.getRawQuery().split("&", -1)) {
			String[] parts = pair.split("=", 2);
			check(parts.length == 2, "JDBC_URL");
			String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
			String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
			check(query.putIfAbsent(name, value) == null, "JDBC_URL");
			// A small supported URL contract rejects user/password, legacy SSL flags,
			// trust-all hooks, local-infile hooks and alternate connection factories.
			check(name.equals("sslMode") || DRIVER_SETTINGS.containsKey(name), "JDBC_URL_PROPERTY");
			if (DRIVER_SETTINGS.containsKey(name)) check(DRIVER_SETTINGS.get(name).equals(value), "JDBC_PROPERTIES");
		}
		check("VERIFY_IDENTITY".equals(query.get("sslMode")), "JDBC_TLS");
	}

	private static void validateRedis(Binder binder) {
		RedisProperties redis = binder.bind("spring.data.redis", RedisProperties.class)
			.orElseThrow(() -> invalid("REDIS"));
		check(nonblank(redis.getHost()) && !redis.getHost().contains("://")
			&& redis.getPort() > 0 && redis.getPort() <= 65535, "REDIS_ENDPOINT");
		check(nonblank(redis.getUsername()) && nonblank(redis.getPassword()), "REDIS_CREDENTIALS");
		check(redis.getSsl().isEnabled(), "REDIS_TLS");
		check(redis.getUrl() == null && redis.getSentinel() == null && redis.getCluster() == null
			&& redis.getDatabase() == 0 && (redis.getClientType() == null
				|| redis.getClientType() == RedisProperties.ClientType.LETTUCE), "REDIS_OVERRIDE");
		check(redis.getLettuce().getReadFrom() == null, "REDIS_OVERRIDE");
		check(Duration.ofSeconds(3).equals(redis.getConnectTimeout())
			&& Duration.ofSeconds(2).equals(redis.getTimeout()), "REDIS_TIMEOUT");
	}

	private static boolean nonblank(String value) {
		return value != null && !value.isBlank() && !value.contains("${");
	}

	private static void check(boolean valid, String reason) {
		if (!valid) throw invalid(reason);
	}

	private static IllegalStateException invalid(String reason) {
		return new IllegalStateException("RENDER_RUNTIME: " + reason);
	}
}
