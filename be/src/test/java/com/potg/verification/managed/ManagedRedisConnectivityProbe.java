package com.potg.verification.managed;

import static com.potg.verification.managed.ManagedSqlContracts.require;

import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;

import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SslVerifyMode;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.event.connection.ConnectionActivatedEvent;
import org.springframework.test.util.ReflectionTestUtils;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.resource.DefaultClientResources;

/** One verified native connection and PING. No SQL, key access, login or retry. */
public final class ManagedRedisConnectivityProbe {
    static final Set<String> INPUTS = Set.of("REDIS_HOST", "REDIS_PORT", "REDIS_USERNAME", "REDIS_PASSWORD", "REDIS_SSL_ENABLED");
    private ManagedRedisConnectivityProbe() { }

    static RedisProperties productRedisSettings(Map<String, String> input) {
        require(input.keySet().equals(INPUTS), "INPUT_ALLOWLIST");
        require(input.values().stream().allMatch(value -> value != null && !value.isBlank()), "INPUT_REQUIRED");
        require(input.get("REDIS_SSL_ENABLED").equals("true"), "TLS_REQUIRED");
        require(input.get("REDIS_HOST").matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")
            && input.get("REDIS_HOST").contains(".") && !input.get("REDIS_HOST").matches("[0-9.]+")
            && !input.get("REDIS_HOST").contains(".."), "MANAGED_DNS_REQUIRED");
        require(input.get("REDIS_PORT").matches("[1-9][0-9]{0,4}") && Integer.parseInt(input.get("REDIS_PORT")) <= 65535, "PORT_RANGE");
        var environment = new StandardEnvironment() {
            @Override protected void customizePropertySources(MutablePropertySources sources) { }
        };
        Map<String, Object> values = new LinkedHashMap<>(input);
        values.put("spring.config.location", "classpath:/application.yml");
        values.put("spring.profiles.active", "demo,render");
        environment.getPropertySources().addFirst(new MapPropertySource("read-only-diagnostic-inputs", values));
        try (var context = new GenericApplicationContext()) {
            context.setEnvironment(environment);
            new ConfigDataApplicationContextInitializer().initialize(context);
            for (String file : new String[] {"application.yml", "application-demo.yml", "application-render.yml"})
                require(environment.getPropertySources().stream().anyMatch(source -> source.getName().contains(file)), "ACTUAL_CONFIGDATA");
            RedisProperties settings = Binder.get(environment).bind("spring.data.redis", RedisProperties.class).orElseThrow(() -> new ManagedSqlContracts.ContractFailure("REDIS_CONFIG_BIND"));
            require(settings.getSsl().isEnabled() && settings.getDatabase() == 0 && settings.getUrl() == null
                && settings.getSentinel() == null && settings.getCluster() == null, "PRODUCT_VERIFIED_TLS_STANDALONE");
            require(Duration.ofSeconds(3).equals(settings.getConnectTimeout()) && Duration.ofSeconds(2).equals(settings.getTimeout()), "PRODUCT_TIMEOUT_CONTRACT");
            return settings;
        }
    }

    static Map<String, Boolean> inputMatches(Map<String, String> input, RedisProperties properties,
                                            RedisStandaloneConfiguration standalone, RedisURI uri) {
        return Map.of(
            "host_matches", Objects.equals(input.get("REDIS_HOST"), properties.getHost()) && Objects.equals(properties.getHost(), standalone.getHostName()) && Objects.equals(standalone.getHostName(), uri.getHost()),
            "port_matches", Integer.parseInt(input.get("REDIS_PORT")) == properties.getPort() && properties.getPort() == standalone.getPort() && standalone.getPort() == uri.getPort(),
            "username_matches", Objects.equals(input.get("REDIS_USERNAME"), properties.getUsername()) && Objects.equals(properties.getUsername(), standalone.getUsername()) && Objects.equals(standalone.getUsername(), uri.getUsername()),
            "password_matches", Objects.equals(input.get("REDIS_PASSWORD"), properties.getPassword()) && Arrays.equals(properties.getPassword().toCharArray(), standalone.getPassword().get()) && Arrays.equals(standalone.getPassword().get(), uri.getPassword()),
            "tls_matches", input.get("REDIS_SSL_ENABLED").equals("true") && properties.getSsl().isEnabled() && uri.isSsl(),
            "database_matches", properties.getDatabase() == 0 && standalone.getDatabase() == 0 && uri.getDatabase() == 0);
    }

    static String classify(Throwable failure) {
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof UnknownHostException) return "DNS";
            if (cursor instanceof SSLException || cursor instanceof CertificateException) return "TLS_CERTIFICATE_OR_HOSTNAME";
            if (cursor instanceof ConnectException) return "TCP_CONNECT";
            if (cursor instanceof RedisCommandTimeoutException || cursor instanceof TimeoutException
                || cursor instanceof java.net.SocketTimeoutException) return "TIMEOUT";
            if (cursor instanceof RedisCommandExecutionException) {
                // Inspect only standard error prefixes; never publish the raw message.
                String message = cursor.getMessage();
                if (message != null && (message.startsWith("WRONGPASS") || message.startsWith("NOAUTH"))) return "AUTH_REJECTED";
                if (message != null && message.startsWith("NOPERM")) return "ACL_REJECTED";
                return "REDIS_PROTOCOL_OR_COMMAND";
            }
        }
        return "CONNECTION_ACTIVATION_OTHER";
    }

    public static void main(String[] args) {
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "FAIL"); result.put("remote_key_writes", 0); result.put("database_operations", 0);
        result.put("login_attempts", 0); result.put("automatic_retry", false);
        result.put("read_only_operation", "NATIVE_TLS_AUTH_SINGLE_PING");
        Path output = null, ledger = null;
        LettuceConnectionFactory factory = null;
        DefaultClientResources resources = null;
        var executor = Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task, "read-only-connectivity"); thread.setDaemon(true); return thread; });
        AtomicInteger pingAttempts = new AtomicInteger();
        var observation = new RedisActivationObservation();
        var transport = new RedisActivationTransport(observation);
        reactor.core.Disposable events = null;
        long start = System.nanoTime();
        try {
            observation.phase("INPUT");
            require(args.length == 0, "NO_ARGUMENTS");
            Path input = ManagedProviderProbe.privateFile(System.getenv("MANAGED_REDIS_CONFIG"));
            output = ManagedProviderProbe.privateFile(System.getenv("MANAGED_REDIS_RESULT"));
            ledger = ManagedProviderProbe.privateFile(System.getenv("MANAGED_REDIS_LEDGER"));
            Map<String, String> values = new LinkedHashMap<>();
            ManagedProviderProbe.JSON.readTree(Files.readAllBytes(input)).fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
            observation.phase("CONFIGDATA");
            RedisProperties settings = productRedisSettings(values);
            result.put("product_configdata", "PASS"); result.put("verified_tls_enabled", true);
            result.put("connect_timeout_millis", settings.getConnectTimeout().toMillis());
            result.put("command_timeout_millis", settings.getTimeout().toMillis());
            var standalone = new RedisStandaloneConfiguration(settings.getHost(), settings.getPort());
            standalone.setUsername(settings.getUsername()); standalone.setPassword(RedisPassword.of(settings.getPassword()));
            observation.phase("CLIENT_RESOURCES");
            resources = DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2)
                .nettyCustomizer(transport).build();
            events = resources.eventBus().get().subscribe(event -> {
                if (event instanceof ConnectionActivatedEvent) {
                    observation.stage("HANDSHAKE", "PASS"); observation.stage("ACTIVE", "PASS");
                }
            });
            observation.phase("CLIENT_OPTIONS");
            var options = ClientOptions.builder().autoReconnect(false).disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .timeoutOptions(TimeoutOptions.enabled())
                .socketOptions(SocketOptions.builder().connectTimeout(settings.getConnectTimeout()).build()).build();
            var client = LettuceClientConfiguration.builder().clientResources(resources).clientOptions(options)
                .commandTimeout(settings.getTimeout()).shutdownQuietPeriod(Duration.ZERO).shutdownTimeout(Duration.ofSeconds(2)).useSsl().and().build();
            require(client.isUseSsl() && client.isVerifyPeer(), "VERIFIED_TLS_REQUIRED");
            observation.phase("FACTORY_START");
            factory = new LettuceConnectionFactory(standalone, client); factory.afterPropertiesSet(); factory.start();
            RedisClient nativeClient = (RedisClient) factory.getNativeClient();
            RedisURI uri = (RedisURI) ReflectionTestUtils.getField(nativeClient, "redisURI");
            require(uri != null && uri.getVerifyMode() == SslVerifyMode.FULL, "ACTUAL_URI_FULL_TLS");
            Map<String, Boolean> matches = inputMatches(values, settings, standalone, uri);
            result.put("setting_matches", matches);
            require(matches.values().stream().allMatch(Boolean::booleanValue), "ACTUAL_INPUT_MAPPING_MISMATCH");
            result.put("effective_settings", Map.ofEntries(
                Map.entry("tls_verify_mode", uri.getVerifyMode().name()), Map.entry("database_index", uri.getDatabase()),
                Map.entry("protocol_configured", options.getConfiguredProtocolVersion() == null ? "DEFAULT_NEGOTIATION" : options.getConfiguredProtocolVersion().name()),
                Map.entry("protocol_effective_preconnect", options.getProtocolVersion().name()),
                Map.entry("activation_timeout_millis", uri.getTimeout().toMillis()),
                Map.entry("auto_reconnect", options.isAutoReconnect()), Map.entry("request_queue_limit", options.getRequestQueueSize()),
                Map.entry("command_timeout_enabled", options.getTimeoutOptions().isTimeoutCommands()),
                Map.entry("ping_before_activation", options.isPingBeforeActivateConnection()),
                Map.entry("ssl_handshake_timeout_millis", options.getSslOptions().getHandshakeTimeout().toMillis())));
            result.put("versions", Map.of("lettuce", RedisClient.class.getPackage().getImplementationVersion(),
                "spring_data_redis", LettuceConnectionFactory.class.getPackage().getImplementationVersion(),
                "netty", io.netty.util.Version.identify().get("netty-common").artifactVersion(), "java", Runtime.version().toString()));
            var ownedFactory = factory;
            observation.phase("CONNECT_ACTIVATE");
            var check = executor.submit(() -> {
                try (var connection = ownedFactory.getConnection()) {
                    observation.phase("EXPLICIT_PING");
                    observation.stage("EXPLICIT_PING", "STARTED");
                    pingAttempts.incrementAndGet();
                    return "PONG".equals(connection.ping());
                }
            });
            require(check.get(30, TimeUnit.SECONDS), "PING_RESPONSE");
            observation.stage("EXPLICIT_PING", "PASS");
            result.put("status", "PASS"); result.put("tls_auth_ping", "PASS");
        } catch (Exception failure) {
            observation.failure(failure);
            if (pingAttempts.get() > 0) observation.stage("EXPLICIT_PING", "FAIL");
        } finally {
            boolean clean = true;
            executor.shutdownNow();
            if (factory != null) try { factory.destroy(); } catch (Exception failure) { clean = false; }
            if (events != null) events.dispose();
            if (resources != null) try { resources.shutdown(0, 2, TimeUnit.SECONDS).get(3, TimeUnit.SECONDS); } catch (Exception failure) { clean = false; }
            try { clean = executor.awaitTermination(3, TimeUnit.SECONDS) && clean; } catch (InterruptedException failure) { clean = false; }
            result.put("diagnostics", observation.snapshot());
            result.put("native_connections_started", transport.connectionCount());
            result.put("command_attempts_observed", transport.commandCount());
            result.put("ping_attempts", pingAttempts.get()); result.put("elapsed_millis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            result.put("cleanup_complete", clean); result.put("native_client_closed", clean); result.put("owned_remote_resources_created", false);
            if (!clean) result.put("status", "FAIL");
            try {
                if (ledger != null) Files.write(ledger, ManagedProviderProbe.JSON.writeValueAsBytes(Map.of("cleanupComplete", clean, "owned_remote_resources_created", false)));
                if (output != null) Files.write(output, ManagedProviderProbe.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
            } catch (Exception failure) { System.exit(2); }
        }
        System.exit("PASS".equals(result.get("status")) ? 0 : 1);
    }
}
