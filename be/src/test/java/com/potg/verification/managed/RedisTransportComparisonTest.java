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

import io.lettuce.core.SslOptions;

class RedisTransportComparisonTest {
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


    private static org.springframework.boot.autoconfigure.data.redis.RedisProperties settings(TlsRespFixture fixture) {
        var settings = new org.springframework.boot.autoconfigure.data.redis.RedisProperties();
        settings.setHost("localhost"); settings.setPort(fixture.server.getLocalPort());
        settings.setUsername("fixture-user"); settings.setPassword(new String(FIXTURE_PASSWORD));
        settings.setConnectTimeout(Duration.ofSeconds(3)); settings.setTimeout(Duration.ofSeconds(2)); settings.getSsl().setEnabled(true);
        return settings;
    }
    private static javax.net.ssl.SSLSocketFactory trustedFactory() throws Exception {
        var store = KeyStore.getInstance(KeyStore.getDefaultType()); store.load(null,null);
        try(var stream=Files.newInputStream(certificate)) { store.setCertificateEntry("fixture",java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(stream)); }
        var managers=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()); managers.init(store);
        var context=SSLContext.getInstance("TLS"); context.init(null,managers.getTrustManagers(),null); return context.getSocketFactory();
    }
    @Test void jdkControlUsesSameSocketVerifiedTlsAndNoRedisCommand() throws Exception {
        try(var fixture=new TlsRespFixture(false)) {
            var timeline=new RedisTransportComparisonProbe.Timeline(); var budget=new RedisTransportComparisonProbe.ConnectionBudget();
            var result=RedisTransportComparisonProbe.jdk("localhost",fixture.server.getLocalPort(),InetAddress.getByName("localhost"),trustedFactory(),budget,timeline);
            assertThat(result).containsEntry("status","PASS").containsEntry("tcp","PASS").containsEntry("tls","PASS").containsEntry("cleanup_complete",true).containsEntry("redis_commands_sent",0);
            assertThat(fixture.commands).isEmpty(); assertThat(budget.used()).isEqualTo(1);
            assertThat(timeline.snapshot().stream().map(event->event.get("event"))).containsSubsequence("TCP_BEGIN","TCP_PASS","TLS_BEGIN","TLS_PASS","SOCKET_CLOSE_BEGIN","SOCKET_CLOSED");
        }
    }
    @Test void jdkRejectsUntrustedCertificateAndClosesSocket() throws Exception {
        try(var fixture=new TlsRespFixture(false)) {
            var result=RedisTransportComparisonProbe.jdk("localhost",fixture.server.getLocalPort(),InetAddress.getByName("localhost"),(javax.net.ssl.SSLSocketFactory)javax.net.ssl.SSLSocketFactory.getDefault(),new RedisTransportComparisonProbe.ConnectionBudget(),new RedisTransportComparisonProbe.Timeline());
            assertThat(result).containsEntry("status","FAIL").containsEntry("tcp","PASS").containsEntry("tls","FAIL").containsEntry("cleanup_complete",true);
            assertThat(fixture.commands).isEmpty();
        }
    }
    @Test void jdkRejectsWrongHostnameDespiteTrustedCertificate() throws Exception {
        try(var fixture=new TlsRespFixture(false)) {
            var result=RedisTransportComparisonProbe.jdk("wrong.invalid",fixture.server.getLocalPort(),InetAddress.getByName("localhost"),trustedFactory(),new RedisTransportComparisonProbe.ConnectionBudget(),new RedisTransportComparisonProbe.Timeline());
            assertThat(result).containsEntry("status","FAIL").containsEntry("tcp","PASS").containsEntry("tls","FAIL").containsEntry("cleanup_complete",true);
            assertThat(fixture.commands).isEmpty();
        }
    }
    @Test void absoluteTlsStageDeadlineClosesStalledOwnedSocket() throws Exception {
        var worker=Executors.newSingleThreadExecutor();
        try(var server=new java.net.ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0));
            var accepted=worker.submit(()->{try(var socket=server.accept()){socket.setSoTimeout(3000);while(socket.getInputStream().read()!=-1){} }catch(Exception ignored){} });
            var timeline=new RedisTransportComparisonProbe.Timeline();
            var result=RedisTransportComparisonProbe.jdk("localhost",server.getLocalPort(),InetAddress.getByName("localhost"),trustedFactory(),new RedisTransportComparisonProbe.ConnectionBudget(),timeline,150);
            assertThat(result).containsEntry("status","FAIL").containsEntry("tcp","PASS").containsEntry("tls","FAIL").containsEntry("cleanup_complete",true).containsEntry("redis_commands_sent",0);
            assertThat(timeline.snapshot().stream().map(event->event.get("event"))).containsSubsequence("TLS_BEGIN","TLS_CONTROL_DEADLINE","SOCKET_CLOSED");
            accepted.get(3,TimeUnit.SECONDS);
        } finally { worker.shutdownNow();assertThat(worker.awaitTermination(3,TimeUnit.SECONDS)).isTrue(); }
    }
    @Test void nativeMinimalControlCountsHandshakeCompletionsAndOnePingWithoutPipelineObserver() throws Exception {
        try(var fixture=new TlsRespFixture(false)) {
            var timeline=new RedisTransportComparisonProbe.Timeline();
            var result=RedisTransportComparisonProbe.nativeControl("B",settings(fixture),InetAddress.getAllByName("localhost"),new RedisTransportComparisonProbe.ConnectionBudget(),timeline,false,SslOptions.builder().jdkSslProvider().trustManager(certificate.toFile()).build());
            assertThat(result).containsEntry("status","PASS").containsEntry("explicit_ping_count",1).containsEntry("cleanup_complete",true).containsEntry("channel_closed",true).containsEntry("custom_pipeline_observer",false).containsEntry("tls_peer_hostname_matches",true);
            assertThat(result.get("completion_counts")).isEqualTo(Map.of("HELLO",1,"AUTH",0,"CLIENT",2,"PING",1,"SELECT",0,"UNEXPECTED_COMMAND",0));
            assertThat(fixture.commands).containsExactly("HELLO","CLIENT","CLIENT","PING");
            assertThat(timeline.snapshot().stream().map(event->event.get("event"))).contains("TLS_FUTURE_SUCCESS","HANDSHAKE_FUTURE_SUCCESS","CHANNEL_CLOSED","CLIENT_SHUTDOWN_DONE","RESOURCES_SHUTDOWN_DONE");
            assertThat(result.toString()).doesNotContain("fixture-user","synthetic-fixture-only","localhost");
        }
    }
    @Test void nativeAuthFailureDoesNotSendExplicitPingAndCleans() throws Exception {
        try(var fixture=new TlsRespFixture(true)) {
            var result=RedisTransportComparisonProbe.nativeControl("B",settings(fixture),InetAddress.getAllByName("localhost"),new RedisTransportComparisonProbe.ConnectionBudget(),new RedisTransportComparisonProbe.Timeline(),false,SslOptions.builder().jdkSslProvider().trustManager(certificate.toFile()).build());
            assertThat(result).containsEntry("status","FAIL").containsEntry("explicit_ping_count",0).containsEntry("cleanup_complete",true).containsEntry("channel_closed",true);
            assertThat(fixture.commands).containsExactly("HELLO");
        }
    }
    @Test void observerOnlyComparisonKeepsSameSuccessfulNativeContract() throws Exception {
        try(var fixture=new TlsRespFixture(false)) {
            var result=RedisTransportComparisonProbe.nativeControl("C",settings(fixture),InetAddress.getAllByName("localhost"),new RedisTransportComparisonProbe.ConnectionBudget(),new RedisTransportComparisonProbe.Timeline(),true,SslOptions.builder().jdkSslProvider().trustManager(certificate.toFile()).build());
            assertThat(result).containsEntry("status","PASS").containsEntry("explicit_ping_count",1).containsEntry("cleanup_complete",true).containsEntry("command_start_count",4);
        }
    }
    @Test void activationTimeoutClosesOwnedChannelWithoutExplicitPing() throws Exception {
        try(var fixture=new TlsRespFixture(false,true)) {
            var timeline=new RedisTransportComparisonProbe.Timeline();
            var result=RedisTransportComparisonProbe.nativeControl("B",settings(fixture),InetAddress.getAllByName("localhost"),new RedisTransportComparisonProbe.ConnectionBudget(),timeline,false,SslOptions.builder().jdkSslProvider().trustManager(certificate.toFile()).build());
            assertThat(result).containsEntry("status","FAIL").containsEntry("explicit_ping_count",0).containsEntry("cleanup_complete",true).containsEntry("channel_closed",true);
            assertThat(((Map<?,?>)result.get("handshake_future_diagnostics")).get("failure_category")).isEqualTo("HANDSHAKE_TIMEOUT");
            assertThat(fixture.commands).containsExactly("HELLO");
            assertThat(timeline.snapshot().stream().map(event->event.get("event"))).contains("HANDSHAKE_FUTURE_FAILURE","CHANNEL_CLOSED","CLIENT_SHUTDOWN_DONE","RESOURCES_SHUTDOWN_DONE");
        }
    }
    @Test void interruptionGatePreventsAnyNextTargetConnection() {
        try {
            RedisTransportComparisonProbe.stopping.set(true); var budget=new RedisTransportComparisonProbe.ConnectionBudget();
            org.assertj.core.api.Assertions.assertThatThrownBy(budget::start).hasMessage("INTERRUPTION_REQUESTED"); assertThat(budget.used()).isZero();
        } finally { RedisTransportComparisonProbe.stopping.set(false); }
    }
    @Test void connectionLimitAndResolverDoNotPermitAnotherTarget() throws Exception {
        var budget=new RedisTransportComparisonProbe.ConnectionBudget(); budget.start();budget.start();budget.start();
        org.assertj.core.api.Assertions.assertThatThrownBy(budget::start).hasMessage("TARGET_CONNECTION_LIMIT"); assertThat(budget.used()).isEqualTo(3);
        var addresses=InetAddress.getAllByName("localhost");var resolver=RedisTransportComparisonProbe.sharedDns("localhost",addresses);
        assertThat(resolver.resolve("localhost")).containsExactly(addresses);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->resolver.resolve("other.invalid")).hasMessage("DNS_TARGET_MISMATCH");
    }
    @Test void timeoutClassificationUsesActualTcpStage() {
        var observation=new RedisActivationObservation(); observation.phase("TCP"); observation.failure(new java.net.SocketTimeoutException("synthetic sensitive content"));
        assertThat(observation.snapshot()).containsEntry("failure_category","TCP_TIMEOUT");
        assertThat(observation.snapshot().toString()).doesNotContain("synthetic sensitive content");
    }
    @Test void timelineRejectsArbitraryValueAndKeepsMonotonicTimes() {
        var timeline=new RedisTransportComparisonProbe.Timeline();timeline.add("A","TCP_BEGIN"); timeline.add("A","TCP_PASS");
        assertThat((long)timeline.snapshot().get(1).get("elapsed_millis")).isGreaterThanOrEqualTo((long)timeline.snapshot().get(0).get("elapsed_millis"));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->timeline.add("A","secret-value")).hasMessage("EVENT_ALLOWLIST");
    }
    private static final class TlsRespFixture implements AutoCloseable {
        private final SSLServerSocket server;
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final List<String> commands = new CopyOnWriteArrayList<>();
        private volatile SSLSocket connected;
        TlsRespFixture(boolean rejectAuth) throws Exception { this(rejectAuth,false); }
        TlsRespFixture(boolean rejectAuth, boolean stallHandshake) throws Exception {
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
                        if(stallHandshake && verb.equals("HELLO")) continue;
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
