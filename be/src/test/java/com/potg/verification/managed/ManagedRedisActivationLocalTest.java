package com.potg.verification.managed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.SslOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.resource.DefaultClientResources;

/** Real TLS and Lettuce activation against an owned loopback RESP fixture, never a provider. */
class ManagedRedisActivationLocalTest {
    @TempDir static Path temporary;
    private static final char[] FIXTURE_PASSWORD = java.util.UUID.randomUUID().toString().toCharArray();
    private static SSLContext serverContext;
    private static Path certificate;

    @BeforeAll static void prepareOwnedCertificate() throws Exception {
        Path keyStore = temporary.resolve("fixture.p12");
        certificate = temporary.resolve("fixture.crt");
        String tool = Path.of(System.getProperty("java.home"), "bin/keytool").toString();
        runTool(List.of(tool, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
            "-storetype", "PKCS12", "-keystore", keyStore.toString(), "-storepass", new String(FIXTURE_PASSWORD),
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "1", "-noprompt"));
        runTool(List.of(tool, "-exportcert", "-rfc", "-alias", "fixture", "-keystore", keyStore.toString(),
            "-storepass", new String(FIXTURE_PASSWORD), "-file", certificate.toString()));
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keyStore)) { keys.load(input, FIXTURE_PASSWORD); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, FIXTURE_PASSWORD);
        serverContext = SSLContext.getInstance("TLS");
        serverContext.init(managers.getKeyManagers(), null, null);
    }

    private static void runTool(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    @Test void defaultProductQueueActivatesThroughVerifiedTlsHelloAndPing() throws Exception {
        try (var fixture = new TlsRespFixture(false)) {
            Result result = connect(fixture, "localhost", true, false);
            assertThat(result.pong()).isTrue();
            assertThat(result.failure()).isNull();
            assertStage(result, "TCP", "PASS"); assertStage(result, "TLS", "PASS");
            assertThat(fixture.commands).contains("HELLO", "PING");
            assertThat(fixture.commands.stream().filter("CLIENT"::equals).count()).isEqualTo(2);
            Map<?, ?> counts = (Map<?, ?>) result.snapshot().get("commands");
            for (String command : List.of("HELLO", "CLIENT", "PING")) {
                long expected = command.equals("CLIENT") ? 2 : 1;
                Map<?, ?> states = (Map<?, ?>) counts.get(command);
                assertThat(states.get("STARTED")).isEqualTo(expected);
                assertThat(states.get("PASS")).isEqualTo(expected);
                assertThat(states.get("FAIL")).isEqualTo(0L);
                assertThat(eventSequence(result, command, "STARTED")).isLessThan(eventSequence(result, command, "PASS"));
            }
            assertThat(((Map<?, ?>) counts.get("UNEXPECTED_COMMAND")).get("STARTED")).isEqualTo(0L);
            assertThat(eventSequence(result, "HELLO", "STARTED")).isLessThan(eventSequence(result, "CLIENT", "STARTED"));
            assertThat(eventSequence(result, "CLIENT", "STARTED")).isLessThan(eventSequence(result, "PING", "STARTED"));
            assertThat(fixture.commands).allMatch(command -> List.of("HELLO", "AUTH", "CLIENT", "PING").contains(command));
            assertThat(result.snapshot().toString()).doesNotContain("synthetic-fixture-only", "fixture-user", "localhost");
        }
    }

    @Test void previousQueueSizeOneIsExercisedSeparatelyFromProductDefault() throws Exception {
        try (var fixture = new TlsRespFixture(false)) {
            Result result = connect(fixture, "localhost", true, true);
            // Lettuce treats CLIENT metadata failure as non-fatal; one-command
            // diagnostic queue is not a faithful product queue configuration.
            assertThat(result.pong()).isTrue();
            assertStage(result, "TLS", "PASS");
            assertThat(fixture.commands).contains("HELLO", "PING").doesNotContain("CLIENT");
            var commands = (Map<?, ?>) result.snapshot().get("commands");
            assertThat(((Map<?, ?>) commands.get("CLIENT")).get("FAIL")).isEqualTo(2L);
        }
    }

    @Test void untrustedCertificateFailsBeforeExplicitPing() throws Exception {
        try (var fixture = new TlsRespFixture(false)) {
            Result result = connect(fixture, "localhost", false, false);
            assertThat(result.pong()).isFalse(); assertThat(result.failure()).isNotNull();
            assertStage(result, "TCP", "PASS"); assertStage(result, "TLS", "FAIL");
            assertThat(fixture.commands).doesNotContain("PING");
        }
    }

    @Test void wrongHostnameFailsDespiteTrustedCertificate() throws Exception {
        try (var fixture = new TlsRespFixture(false)) {
            Result result = connect(fixture, "127.0.0.1", true, false);
            assertThat(result.pong()).isFalse(); assertThat(result.failure()).isNotNull();
            assertStage(result, "TLS", "FAIL");
            assertThat(fixture.commands).doesNotContain("PING");
        }
    }

    @Test void rejectedHandshakeAuthIsDistinctFromSuccessfulTls() throws Exception {
        try (var fixture = new TlsRespFixture(true)) {
            Result result = connect(fixture, "localhost", true, false);
            assertThat(result.pong()).isFalse(); assertThat(result.failure()).isNotNull();
            assertStage(result, "TLS", "PASS");
            assertThat(fixture.commands).contains("HELLO").doesNotContain("PING");
            assertThat(result.snapshot().get("failure_category")).isEqualTo("AUTH_REJECTED");
        }
    }

    @Test void observationDoesNotChangePositiveOrNegativeConnectionResults() throws Exception {
        for (int variant = 0; variant < 4; variant++) {
            boolean rejectAuth = variant == 3;
            boolean trusted = variant != 1;
            String host = variant == 2 ? "127.0.0.1" : "localhost";
            Result plain;
            Result observed;
            try (var fixture = new TlsRespFixture(rejectAuth)) {
                plain = connect(fixture, host, trusted, false, false);
            }
            try (var fixture = new TlsRespFixture(rejectAuth)) {
                observed = connect(fixture, host, trusted, false, true);
            }
            assertThat(observed.pong()).isEqualTo(plain.pong()).isEqualTo(variant == 0);
            assertThat(observed.failure() == null).isEqualTo(plain.failure() == null);
            assertThat(observed.snapshot().get("failure_category")).isEqualTo(plain.snapshot().get("failure_category"));
        }
    }

    private static long eventSequence(Result result, String command, String state) {
        for (Object item : (List<?>) result.snapshot().get("events")) {
            Map<?, ?> event = (Map<?, ?>) item;
            if ("COMMAND".equals(event.get("kind")) && command.equals(event.get("code")) && state.equals(event.get("state")))
                return ((Number) event.get("sequence")).longValue();
        }
        throw new AssertionError("MISSING_SYNTHETIC_COMMAND_EVENT");
    }

    @SuppressWarnings("unchecked")
    private static void assertStage(Result result, String stage, String expected) {
        assertThat(((Map<String, String>) result.snapshot().get("stages")).get(stage)).isEqualTo(expected);
    }

    private Result connect(TlsRespFixture fixture, String host, boolean trustFixture, boolean tinyQueue) throws Exception {
        return connect(fixture, host, trustFixture, tinyQueue, true);
    }

    private Result connect(TlsRespFixture fixture, String host, boolean trustFixture, boolean tinyQueue, boolean observe) throws Exception {
        var observation = new RedisActivationObservation();
        var builder = DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2);
        if (observe) builder.nettyCustomizer(new RedisActivationTransport(observation));
        var resources = builder.build();
        var ssl = SslOptions.builder().jdkSslProvider();
        if (trustFixture) ssl.trustManager(certificate.toFile());
        var options = ClientOptions.builder().autoReconnect(false).disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(3)).build()).sslOptions(ssl.build())
            .timeoutOptions(TimeoutOptions.enabled());
        if (tinyQueue) options.requestQueueSize(1);
        var client = LettuceClientConfiguration.builder().clientResources(resources).clientOptions(options.build())
            .commandTimeout(Duration.ofSeconds(2)).shutdownQuietPeriod(Duration.ZERO).shutdownTimeout(Duration.ofSeconds(2))
            .useSsl().and().build();
        assertThat(client.isVerifyPeer()).isTrue();
        var standalone = new RedisStandaloneConfiguration(host, fixture.server.getLocalPort());
        standalone.setUsername("fixture-user"); standalone.setPassword(RedisPassword.of(FIXTURE_PASSWORD));
        var factory = new LettuceConnectionFactory(standalone, client);
        boolean pong = false;
        Throwable failure = null;
        try {
            factory.afterPropertiesSet(); factory.start();
            try (var connection = factory.getConnection()) {
                observation.stage("ACTIVE", "PASS");
                observation.stage("EXPLICIT_PING", "STARTED");
                pong = "PONG".equals(connection.ping());
                observation.stage("EXPLICIT_PING", pong ? "PASS" : "FAIL");
            }
        } catch (Exception caught) { failure = caught; observation.failure(caught); }
        finally {
            factory.destroy();
            assertThat(resources.shutdown(0, 2, TimeUnit.SECONDS).get(3, TimeUnit.SECONDS)).isTrue();
        }
        return new Result(pong, failure, observation.snapshot());
    }

    private record Result(boolean pong, Throwable failure, Map<String, Object> snapshot) { }

    private static final class TlsRespFixture implements AutoCloseable {
        private final SSLServerSocket server;
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final List<String> commands = new CopyOnWriteArrayList<>();
        private volatile SSLSocket connected;
        TlsRespFixture(boolean rejectAuth) throws Exception {
            server = (SSLServerSocket) serverContext.getServerSocketFactory().createServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            worker.submit(() -> {
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                    connected = socket; socket.setSoTimeout(5000); socket.startHandshake();
                    InputStream input = new BufferedInputStream(socket.getInputStream());
                    OutputStream output = socket.getOutputStream();
                    while (!server.isClosed()) {
                        List<String> request = command(input);
                        String verb = request.getFirst(); commands.add(verb);
                        String response = switch (verb) {
                            case "HELLO" -> rejectAuth ? "-WRONGPASS synthetic fixture denial\r\n"
                                : "%7\r\n+server\r\n+redis\r\n+version\r\n+7.4.0\r\n+proto\r\n:3\r\n+id\r\n:1\r\n+mode\r\n+standalone\r\n+role\r\n+master\r\n+modules\r\n*0\r\n";
                            case "AUTH" -> rejectAuth ? "-WRONGPASS synthetic fixture denial\r\n" : "+OK\r\n";
                            case "CLIENT" -> "+OK\r\n";
                            case "PING" -> "+PONG\r\n";
                            default -> "-ERR unsupported synthetic fixture command\r\n";
                        };
                        output.write(response.getBytes(StandardCharsets.US_ASCII)); output.flush();
                    }
                } catch (Exception expectedDuringTlsRejectionOrClose) { /* No raw socket/command values are reported. */ }
            });
        }
        private static List<String> command(InputStream input) throws Exception {
            String count = line(input);
            if (!count.startsWith("*")) throw new IllegalStateException("FIXTURE_RESP_ARRAY");
            int length = Integer.parseInt(count.substring(1));
            if (length < 1 || length > 16) throw new IllegalStateException("FIXTURE_ARGUMENT_LIMIT");
            List<String> args = new ArrayList<>();
            for (int i = 0; i < length; i++) {
                String header = line(input);
                if (!header.startsWith("$")) throw new IllegalStateException("FIXTURE_RESP_BULK");
                int bytes = Integer.parseInt(header.substring(1));
                if (bytes < 0 || bytes > 1024) throw new IllegalStateException("FIXTURE_BYTES_LIMIT");
                byte[] body = input.readNBytes(bytes);
                if (body.length != bytes || input.read() != '\r' || input.read() != '\n') throw new EOFException();
                args.add(new String(body, StandardCharsets.US_ASCII));
            }
            return args;
        }
        private static String line(InputStream input) throws Exception {
            StringBuilder line = new StringBuilder();
            for (int value; (value = input.read()) != -1;) {
                if (value == '\r') { if (input.read() != '\n') throw new EOFException(); return line.toString(); }
                if (line.length() >= 1024) throw new IllegalStateException("FIXTURE_LINE_LIMIT");
                line.append((char) value);
            }
            throw new EOFException();
        }
        @Override public void close() throws Exception {
            server.close();
            if (connected != null) connected.close();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
