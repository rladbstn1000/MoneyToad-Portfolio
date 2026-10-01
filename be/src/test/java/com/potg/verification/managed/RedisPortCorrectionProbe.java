package com.potg.verification.managed;

import static com.potg.verification.managed.ManagedSqlContracts.require;

import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;

/** One native verified TLS/PING after an independently approved local input correction. */
public final class RedisPortCorrectionProbe {
    static final String OPERATION = "REDIS_PORT_CORRECTION_NATIVE_TLS_PING";
    private RedisPortCorrectionProbe() { }

    static InetAddress[] firstIpv4(String host, int port, InetAddress[] resolved) {
        for (InetAddress address : resolved) if (address instanceof Inet4Address) {
            require(host.equals(new InetSocketAddress(address,port).getHostString()),"DNS_ORIGINAL_HOSTNAME_REQUIRED");
            return new InetAddress[] {address};
        }
        throw new ManagedSqlContracts.ContractFailure("IPV4_RESULT_REQUIRED");
    }

    static Map<String,Boolean> settingMatches(Map<String,String> input, RedisProperties settings) {
        var standalone = new RedisStandaloneConfiguration(settings.getHost(),settings.getPort());
        standalone.setUsername(settings.getUsername()); standalone.setPassword(RedisPassword.of(settings.getPassword()));
        return ManagedRedisConnectivityProbe.inputMatches(input,settings,standalone,RedisTransportComparisonProbe.uri(settings));
    }

    public static void main(String[] args) {
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        Map<String,Object> out = new LinkedHashMap<>();
        Map<String,Object> controls = new LinkedHashMap<>();
        out.put("status","FAIL"); out.put("read_only_operation",OPERATION);
        out.put("owned_remote_resources_created",false); out.put("remote_key_writes",0);
        out.put("database_operations",0); out.put("login_attempts",0); out.put("automatic_retry",false);
        out.put("product_condition_exact",false); out.put("ssl_provider","JDK_DEFAULT_TRUST");
        out.put("resolver","SHARED_JDK_HOSTNAME_LOOKUP"); out.put("address_selection","FIRST_IPV4");
        out.put("os_dns_physical_connections","NOT_OBSERVED"); out.put("probe_owned_dns_sockets",0);
        out.put("command_reservation",64); out.put("connection_limit",1); out.put("jdk_tcp_control_executed",false);
        var timeline = new RedisTransportComparisonProbe.Timeline();
        var budget = new RedisTransportComparisonProbe.ConnectionBudget();
        var failures = new RedisActivationObservation();
        var dns = Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"port-check-dns");thread.setDaemon(true);return thread;});
        Path output = null, ledger = null; boolean clean = true;
        Thread main = Thread.currentThread(); CountDownLatch finished = new CountDownLatch(1);
        Thread hook = new Thread(()->{
            RedisTransportComparisonProbe.stopping.set(true);
            for (Runnable close : RedisTransportComparisonProbe.stopClosers) try {close.run();} catch (RuntimeException ignored) { }
            main.interrupt();
            try {finished.await(20,TimeUnit.SECONDS);} catch (InterruptedException ignored) {Thread.currentThread().interrupt();}
        },"port-check-owned-cleanup");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            require(args.length==0,"NO_ARGUMENTS");
            Path input = ManagedProviderProbe.privateFile(System.getenv("MANAGED_REDIS_CONFIG"));
            output = ManagedProviderProbe.privateFile(System.getenv("MANAGED_REDIS_RESULT"));
            ledger = ManagedProviderProbe.privateFile(System.getenv("MANAGED_REDIS_LEDGER"));
            var node = ManagedProviderProbe.JSON.readTree(Files.readAllBytes(input));
            require(node.isObject(),"INPUT_OBJECT_REQUIRED");
            Map<String,String> values = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry->{require(entry.getValue().isTextual(),"INPUT_STRING_REQUIRED");values.put(entry.getKey(),entry.getValue().asText());});
            var settings = ManagedRedisConnectivityProbe.productRedisSettings(values);
            Map<String,Boolean> matches = settingMatches(values,settings);
            require(matches.values().stream().allMatch(Boolean::booleanValue),"ACTUAL_INPUT_MAPPING_MISMATCH");
            out.put("product_configdata","PASS"); out.put("setting_matches",matches); timeline.add("RUN","INPUT_READY");
            var options = RedisTransportComparisonProbe.options(settings);
            out.put("effective_settings",Map.ofEntries(Map.entry("connect_timeout_millis",settings.getConnectTimeout().toMillis()),Map.entry("activation_timeout_millis",settings.getTimeout().toMillis()),Map.entry("command_timeout_millis",settings.getTimeout().toMillis()),Map.entry("ssl_handshake_timeout_millis",options.getSslOptions().getHandshakeTimeout().toMillis()),Map.entry("tls_verify_mode","FULL"),Map.entry("protocol_configured","DEFAULT_NEGOTIATION"),Map.entry("protocol_effective_preconnect",options.getProtocolVersion().name()),Map.entry("command_timeout_enabled",options.getTimeoutOptions().isTimeoutCommands()),Map.entry("auto_reconnect",false),Map.entry("request_queue_limit",options.getRequestQueueSize()),Map.entry("jdk_dns_deadline_millis",5000),Map.entry("native_control_deadline_millis",20000)));
            out.put("versions",Map.of("java",Runtime.version().toString(),"lettuce",io.lettuce.core.RedisClient.class.getPackage().getImplementationVersion(),"netty",io.netty.util.Version.identify().get("netty-common").artifactVersion()));
            out.put("native_transport",RedisTransportComparisonProbe.transport()); out.put("environment_flags",RedisTransportComparisonProbe.environmentFlags());
            require(!Boolean.getBoolean("java.net.useSystemProxies") && System.getProperty("socksProxyHost")==null && System.getProperty("javax.net.ssl.trustStore")==null,"UNEXPECTED_JVM_NETWORK_OVERRIDE");
            failures.phase("DNS"); timeline.add("RUN","DNS_BEGIN");
            InetAddress[] resolved;
            try {resolved=dns.submit(()->InetAddress.getAllByName(settings.getHost())).get(5,TimeUnit.SECONDS);timeline.add("RUN","DNS_PASS");}
            catch (Exception failure) {timeline.add("RUN","DNS_FAIL");throw failure;}
            InetAddress[] selected = firstIpv4(settings.getHost(),settings.getPort(),resolved);
            out.put("dns_hostname_preserved",true); out.put("jdk_selected_address_family","IPV4");
            out.put("prior_run_dns_target_comparison","NOT_ESTABLISHED");
            RedisTransportComparisonProbe.running();
            var nativeResult = RedisTransportComparisonProbe.nativeControl("B",settings,selected,budget,timeline,false);
            controls.put("B",nativeResult); require(budget.used()<=1,"SINGLE_NATIVE_ATTEMPT_REQUIRED");
            if ("PASS".equals(nativeResult.get("status"))) out.put("status","PASS");
        } catch (Exception failure) {failures.failure(failure);}
        finally {
            Thread.interrupted(); timeline.add("RUN","RUN_CLEANUP_BEGIN"); dns.shutdownNow();
            try {clean=dns.awaitTermination(3,TimeUnit.SECONDS);} catch (InterruptedException ignored) {clean=false;}
            for (Object control : controls.values()) clean &= Boolean.TRUE.equals(((Map<?,?>)control).get("cleanup_complete"));
            controls.putIfAbsent("A",Map.of("status","NOT_RUN")); controls.putIfAbsent("B",Map.of("status","NOT_RUN")); controls.putIfAbsent("C",Map.of("status","NOT_RUN"));
            timeline.add("RUN","RUN_CLEANUP_DONE"); out.put("controls",controls); out.put("lifecycle",timeline.snapshot());
            out.put("target_connections_started",budget.used()); out.put("elapsed_millis",timeline.elapsed());
            out.put("cleanup_complete",clean); out.put("diagnostics",failures.snapshot());
            if (!clean || RedisTransportComparisonProbe.stopping.get()) out.put("status","FAIL");
            try {
                if (ledger!=null) Files.write(ledger,ManagedProviderProbe.JSON.writeValueAsBytes(Map.of("cleanupComplete",clean,"owned_remote_resources_created",false,"data_key_commands_allowed",false,"commands_reserved",64,"target_connections_reserved",1,"read_only_operation",OPERATION)));
                if (output!=null) Files.write(output,ManagedProviderProbe.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(out));
            } catch (Exception ignored) {out.put("status","FAIL");}
            finished.countDown(); if(!RedisTransportComparisonProbe.stopping.get()) Runtime.getRuntime().removeShutdownHook(hook);
        }
        System.exit("PASS".equals(out.get("status"))?0:1);
    }
}
