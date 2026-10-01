package com.potg.verification.managed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.lettuce.core.RedisCommandExecutionException;

/** ConfigData and error projection only; never establishes any connection. */
class ManagedRedisConnectivityProbeTest {
    private Map<String, String> input() {
        return new HashMap<>(Map.of("REDIS_HOST", "fixture.example.invalid", "REDIS_PORT", "6379", "REDIS_USERNAME", "fixture-user",
            "REDIS_PASSWORD", "synthetic-test-only", "REDIS_SSL_ENABLED", "true"));
    }
    @Test void loadsActualDemoRenderRedisSettingsWithoutStartingProductBeans() {
        var settings = ManagedRedisConnectivityProbe.productRedisSettings(input());
        assertThat(settings.getConnectTimeout().toMillis()).isEqualTo(3000);
        assertThat(settings.getTimeout().toMillis()).isEqualTo(2000);
        assertThat(settings.getSsl().isEnabled()).isTrue();
        assertThat(settings.getUsername()).isEqualTo("fixture-user");
    }
    @Test void rejectsTlsOffAndUnexpectedInputs() {
        var values = input(); values.put("REDIS_SSL_ENABLED", "false");
        assertThatThrownBy(() -> ManagedRedisConnectivityProbe.productRedisSettings(values)).hasMessage("TLS_REQUIRED");
        var extra = input(); extra.put("EXTRA", "anything");
        assertThatThrownBy(() -> ManagedRedisConnectivityProbe.productRedisSettings(extra)).hasMessage("INPUT_ALLOWLIST");
    }
    @Test void rejectsIpAndMalformedEndpointBeforeAnyConnection() {
        for (String host : new String[] {"127.0.0.1", "https://fixture.example.invalid", "fixture..example.invalid"}) {
            var values = input(); values.put("REDIS_HOST", host);
            assertThatThrownBy(() -> ManagedRedisConnectivityProbe.productRedisSettings(values)).hasMessage("MANAGED_DNS_REQUIRED");
        }
    }
    @Test void reportsOnlyFixedCategoriesIncludingNestedFailures() {
        assertThat(ManagedRedisConnectivityProbe.classify(new Exception(new java.net.UnknownHostException("synthetic-private")))).isEqualTo("DNS");
        assertThat(ManagedRedisConnectivityProbe.classify(new javax.net.ssl.SSLHandshakeException("synthetic-private"))).isEqualTo("TLS_CERTIFICATE_OR_HOSTNAME");
        assertThat(ManagedRedisConnectivityProbe.classify(new java.net.ConnectException("synthetic-private"))).isEqualTo("TCP_CONNECT");
        assertThat(ManagedRedisConnectivityProbe.classify(new java.util.concurrent.TimeoutException("synthetic-private"))).isEqualTo("TIMEOUT");
        assertThat(ManagedRedisConnectivityProbe.classify(new RedisCommandExecutionException("WRONGPASS synthetic-private"))).isEqualTo("AUTH_REJECTED");
        assertThat(ManagedRedisConnectivityProbe.classify(new RedisCommandExecutionException("NOPERM synthetic-private"))).isEqualTo("ACL_REJECTED");
        assertThat(ManagedRedisConnectivityProbe.classify(new IllegalStateException("synthetic-private"))).isEqualTo("CONNECTION_ACTIVATION_OTHER");
    }
    @Test void actualFactoryUriMatchesEveryInputWithoutExposingValues() {
        var values = input();
        var properties = ManagedRedisConnectivityProbe.productRedisSettings(values);
        var standalone = new org.springframework.data.redis.connection.RedisStandaloneConfiguration(properties.getHost(), properties.getPort());
        standalone.setUsername(properties.getUsername());
        standalone.setPassword(org.springframework.data.redis.connection.RedisPassword.of(properties.getPassword()));
        var config = org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder().useSsl().and().commandTimeout(properties.getTimeout()).build();
        var factory = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(standalone, config);
        try {
            factory.afterPropertiesSet();
            var uri = (io.lettuce.core.RedisURI) org.springframework.test.util.ReflectionTestUtils.getField(factory.getNativeClient(), "redisURI");
            var matches = ManagedRedisConnectivityProbe.inputMatches(values, properties, standalone, uri);
            assertThat(matches.values()).allMatch(Boolean::booleanValue);
            assertThat(matches.toString()).doesNotContain("synthetic-test-only", "fixture-user", "fixture.example.invalid");
            assertThat(uri.getVerifyMode()).isEqualTo(io.lettuce.core.SslVerifyMode.FULL);
            values.put("REDIS_PASSWORD", "different-synthetic-value");
            assertThat(ManagedRedisConnectivityProbe.inputMatches(values, properties, standalone, uri).get("password_matches")).isFalse();
        } finally { factory.destroy(); }
    }
}
