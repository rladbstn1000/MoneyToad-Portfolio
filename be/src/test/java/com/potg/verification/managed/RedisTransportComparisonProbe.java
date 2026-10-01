package com.potg.verification.managed;

import static com.potg.verification.managed.ManagedSqlContracts.require;

import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import io.netty.handler.ssl.SslHandler;
import io.lettuce.core.protocol.RedisHandshakeHandler;
import io.netty.channel.Channel;
import io.lettuce.core.resource.NettyCustomizer;
import io.lettuce.core.SslOptions;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.springframework.boot.autoconfigure.data.redis.RedisProperties;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ConnectionFuture;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.SslVerifyMode;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.metrics.CommandLatencyRecorder;
import io.lettuce.core.protocol.ProtocolKeyword;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.DnsResolver;
import io.lettuce.core.resource.Transports;

/** Finite transport controls only. Never starts an application or accesses Redis data keys. */
public final class RedisTransportComparisonProbe {
    static final String OPERATION = "JDK_TLS_LETTUCE_TRANSPORT_COMPARISON";
    private RedisTransportComparisonProbe() { }
    static final AtomicBoolean stopping = new AtomicBoolean();
    static final List<Runnable> stopClosers = new CopyOnWriteArrayList<>();
    static void running() { require(!stopping.get(), "INTERRUPTION_REQUESTED"); }


    static final class Timeline {
        private final long origin = System.nanoTime();
        private final List<Map<String,Object>> events = new ArrayList<>();
        synchronized void add(String control, String event) {
            require(Set.of("RUN", "A", "B", "C").contains(control), "CONTROL_ALLOWLIST");
            require(EVENTS.contains(event), "EVENT_ALLOWLIST");
            require(events.size() < 128, "LIFECYCLE_LIMIT");
            events.add(Map.of("control", control, "event", event, "elapsed_millis", elapsed()));
        }
        long elapsed() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - origin); }
        synchronized List<Map<String,Object>> snapshot() { return List.copyOf(events); }
    }
    private static final Set<String> EVENTS = Set.of("INPUT_READY", "DNS_BEGIN", "DNS_PASS", "DNS_FAIL", "TCP_BEGIN", "TCP_PASS", "TLS_BEGIN", "TLS_PASS", "CONTROL_FAIL", "CONTROL_CLEANUP_BEGIN", "SOCKET_CLOSE_BEGIN", "SOCKET_CLOSED", "NATIVE_CONNECT_BEGIN", "CONNECTION_CREATED", "CONNECT_EVENT", "CONNECTED_EVENT", "ACTIVATED_EVENT", "DEACTIVATED_EVENT", "DISCONNECTED_EVENT", "CONNECT_FUTURE_SUCCESS", "CONNECT_FUTURE_FAILURE", "CONNECT_FUTURE_CANCEL_REQUEST", "PING_BEGIN", "PING_PASS", "CONNECTION_CLOSE_BEGIN", "CONNECTION_CLOSED", "CLIENT_SHUTDOWN_BEGIN", "CLIENT_SHUTDOWN_DONE", "RESOURCES_SHUTDOWN_BEGIN", "RESOURCES_SHUTDOWN_DONE", "CHANNEL_CREATED", "CHANNEL_CLOSED", "CHANNEL_CLOSE_BEGIN", "TLS_FUTURE_SUCCESS", "TLS_FUTURE_FAILURE", "HANDSHAKE_FUTURE_SUCCESS", "HANDSHAKE_FUTURE_FAILURE", "A_CONTROL_DEADLINE", "TLS_CONTROL_DEADLINE", "RUN_CLEANUP_BEGIN", "RUN_CLEANUP_DONE");

    static final class ConnectionBudget {
        private final AtomicInteger attempts = new AtomicInteger();
        synchronized int start() { running(); require(attempts.get()<3,"TARGET_CONNECTION_LIMIT"); return attempts.incrementAndGet(); }
        int used() { return attempts.get(); }
    }

    static DnsResolver sharedDns(String expectedHost, InetAddress[] resolved) {
        require(resolved.length > 0, "DNS_EMPTY");
        InetAddress[] copy = resolved.clone();
        return host -> { require(expectedHost.equals(host), "DNS_TARGET_MISMATCH"); return copy.clone(); };
    }

    static RedisURI uri(RedisProperties settings) {
        var uri = RedisURI.Builder.redis(settings.getHost(), settings.getPort()).withSsl(true)
            .withAuthentication(settings.getUsername(), settings.getPassword()).withDatabase(0)
            .withTimeout(settings.getTimeout()).build();
        uri.setVerifyPeer(SslVerifyMode.FULL);
        return uri;
    }

    static ClientOptions options(RedisProperties settings) {
        return ClientOptions.builder().autoReconnect(false)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .timeoutOptions(TimeoutOptions.enabled())
            .socketOptions(SocketOptions.builder().connectTimeout(settings.getConnectTimeout()).build()).build();
    }

    static Map<String,Object> jdk(String host, int port, InetAddress address, SSLSocketFactory factory,
                                  ConnectionBudget budget, Timeline timeline) {
        return jdk(host,port,address,factory,budget,timeline,10000);
    }
    static Map<String,Object> jdk(String host, int port, InetAddress address, SSLSocketFactory factory,
                                  ConnectionBudget budget, Timeline timeline, int tlsDeadlineMillis) {
        require(tlsDeadlineMillis>0 && tlsDeadlineMillis<=10000,"TLS_DEADLINE_LIMIT");
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("status", "FAIL"); out.put("tcp", "NOT_RUN"); out.put("tls", "NOT_RUN");
        Socket tcp = null; SSLSocket tls = null; long start = System.nanoTime();
        var observation = new RedisActivationObservation(); boolean clean = true;
        var socketReference = new AtomicReference<Socket>();
        var deadline = Executors.newSingleThreadScheduledExecutor(task->{var thread=new Thread(task,"jdk-control-deadline");thread.setDaemon(true);return thread;});
        Runnable closeOwned = () -> { Socket socket=socketReference.get(); if (socket!=null) try {socket.close();} catch (Exception ignored) { } };
        stopClosers.add(closeOwned);
        AtomicBoolean deadlineTriggered=new AtomicBoolean();
        var timeout = deadline.schedule(()->{deadlineTriggered.set(true);timeline.add("A","A_CONTROL_DEADLINE"); closeOwned.run();}, 14,TimeUnit.SECONDS);
        java.util.concurrent.ScheduledFuture<?> tlsDeadline=null;
        try {
            budget.start(); tcp = new Socket(); socketReference.set(tcp); running(); observation.phase("TCP"); timeline.add("A", "TCP_BEGIN");
            tcp.connect(new InetSocketAddress(address, port), 3000);
            timeline.add("A", "TCP_PASS"); out.put("tcp", "PASS");
            observation.phase("TLS"); timeline.add("A", "TLS_BEGIN"); out.put("tls", "FAIL");
            tlsDeadline=deadline.schedule(()->{deadlineTriggered.set(true);timeline.add("A","TLS_CONTROL_DEADLINE");closeOwned.run();},tlsDeadlineMillis,TimeUnit.MILLISECONDS);
            tls = (SSLSocket) factory.createSocket(tcp, host, port, true);
            tls.setSoTimeout(10000);
            var parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setServerNames(List.of(new SNIHostName(host))); tls.setSSLParameters(parameters);
            tls.startHandshake(); timeline.add("A", "TLS_PASS");
            out.put("tls", "PASS"); out.put("status", "PASS");
        } catch (Exception failure) {
            if (!"PASS".equals(out.get("tcp"))) out.put("tcp", "FAIL");
            observation.failure(failure); timeline.add("A", "CONTROL_FAIL");
        } finally {
            timeline.add("A", "CONTROL_CLEANUP_BEGIN"); timeline.add("A", "SOCKET_CLOSE_BEGIN");
            if (tls != null) try { tls.close(); } catch (Exception ignored) { clean = false; }
            if (tcp != null) try { tcp.close(); } catch (Exception ignored) { clean = false; }
            clean &= (tls == null || tls.isClosed()) && (tcp == null || tcp.isClosed());
            timeout.cancel(false); if(tlsDeadline!=null)tlsDeadline.cancel(false); deadline.shutdownNow(); stopClosers.remove(closeOwned);
            try { clean &= deadline.awaitTermination(2,TimeUnit.SECONDS); } catch (InterruptedException ignored) {clean=false;Thread.interrupted();}
            if (clean) timeline.add("A", "SOCKET_CLOSED");
            out.put("cleanup_complete", clean); out.put("redis_commands_sent", 0);
            out.put("elapsed_millis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
            out.put("diagnostics", observation.snapshot());
            if (!clean || deadlineTriggered.get()) out.put("status", "FAIL");
        }
        return out;
    }

    static final class CompletionCounts implements CommandLatencyRecorder {
        private final Map<String,Integer> counts = new LinkedHashMap<>();
        CompletionCounts() { for (String name : List.of("HELLO","AUTH","CLIENT","PING","SELECT","UNEXPECTED_COMMAND")) counts.put(name,0); }
        @Override public synchronized void recordCommandLatency(SocketAddress local, SocketAddress remote, ProtocolKeyword keyword, long first, long complete) {
            String name = keyword.toString(); if (!counts.containsKey(name)) name = "UNEXPECTED_COMMAND";
            counts.compute(name, (key,value)->value+1);
        }
        synchronized Map<String,Integer> snapshot() { return Map.copyOf(counts); }
    }

    static Map<String,Object> nativeControl(String control, RedisProperties settings, InetAddress[] addresses,
                                            ConnectionBudget budget, Timeline timeline, boolean observe) {
        return nativeControl(control,settings,addresses,budget,timeline,observe,null);
    }

    static Map<String,Object> nativeControl(String control, RedisProperties settings, InetAddress[] addresses,
                                            ConnectionBudget budget, Timeline timeline, boolean observe, SslOptions fixtureSsl) {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("status", "FAIL"); out.put("activation", "NOT_RUN"); out.put("ping", "NOT_RUN");
        out.put("custom_pipeline_observer", observe); out.put("command_start_count", "NOT_OBSERVED");
        out.put("automatic_and_explicit_command_upper_bound", 5);
        out.put("conservative_command_allowance",64);
        var observation = new RedisActivationObservation(); var counts = new CompletionCounts();
        var observer = new RedisActivationTransport(observation);
        var ownedChannel = new AtomicReference<Channel>();
        out.put("read_only_channel_lifetime_observer",true);
        var tlsFailure = new RedisActivationObservation(); tlsFailure.phase("TLS"); var handshakeFailure = new RedisActivationObservation(); handshakeFailure.phase("HANDSHAKE");
        AtomicBoolean peerVerified = new AtomicBoolean();
        Runnable closeOwned = () -> { Channel channel=ownedChannel.get(); if(channel!=null) channel.close(); };
        stopClosers.add(closeOwned);
        DefaultClientResources resources = null; RedisClient client = null;
        StatefulRedisConnection<String,String> connection = null; ConnectionFuture<StatefulRedisConnection<String,String>> future = null;
        reactor.core.Disposable events = null; boolean clean = true; int pings = 0; long start = System.nanoTime();
        try {
            var resourceBuilder = DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2)
                .dnsResolver(sharedDns(settings.getHost(), addresses)).commandLatencyRecorder(counts);
            resourceBuilder.nettyCustomizer(new NettyCustomizer() {
                @Override public void afterChannelInitialized(Channel channel) {
                    require(ownedChannel.compareAndSet(null,channel), "ONE_CHANNEL_PER_CONTROL");
                    timeline.add(control,"CHANNEL_CREATED");
                    channel.closeFuture().addListener(ignored->timeline.add(control,"CHANNEL_CLOSED"));
                    SslHandler ssl=channel.pipeline().get(SslHandler.class);
                    require(ssl!=null && settings.getHost().equals(ssl.engine().getPeerHost()) && "HTTPS".equals(ssl.engine().getSSLParameters().getEndpointIdentificationAlgorithm()),"SSL_ORIGINAL_HOSTNAME_REQUIRED");
                    peerVerified.set(true);
                    ssl.handshakeFuture().addListener(done->{ if(!done.isSuccess()) tlsFailure.failure(done.cause()); timeline.add(control,done.isSuccess()?"TLS_FUTURE_SUCCESS":"TLS_FUTURE_FAILURE"); });
                    RedisHandshakeHandler handshake=channel.pipeline().get(RedisHandshakeHandler.class);
                    require(handshake!=null,"NATIVE_HANDSHAKE_HANDLER_REQUIRED");
                    handshake.channelInitialized().whenComplete((value,failure)->{ if(failure!=null) handshakeFailure.failure(failure); timeline.add(control,failure==null?"HANDSHAKE_FUTURE_SUCCESS":"HANDSHAKE_FUTURE_FAILURE"); });
                    if(stopping.get()) channel.close();
                    if (observe) observer.afterChannelInitialized(channel);
                }
            });
            resources = resourceBuilder.build();
            events = resources.eventBus().get().subscribe(event -> {
                String code = switch (event.getClass().getSimpleName()) {
                    case "ConnectionCreatedEvent" -> "CONNECTION_CREATED";
                    case "ConnectEvent" -> "CONNECT_EVENT";
                    case "ConnectedEvent" -> "CONNECTED_EVENT";
                    case "ConnectionActivatedEvent" -> "ACTIVATED_EVENT";
                    case "ConnectionDeactivatedEvent" -> "DEACTIVATED_EVENT";
                    case "DisconnectedEvent" -> "DISCONNECTED_EVENT";
                    default -> null;
                };
                if (code != null) timeline.add(control, code);
            });
            RedisURI uri = uri(settings); ClientOptions options = options(settings);
            if (fixtureSsl!=null) options=options.mutate().sslOptions(fixtureSsl).build();
            require(uri.getHost().equals(settings.getHost()) && uri.getPort()==settings.getPort()
                && uri.getVerifyMode()==SslVerifyMode.FULL && uri.isSsl() && uri.getDatabase()==0, "NATIVE_MAPPING");
            client = RedisClient.create(resources, uri); client.setOptions(options);
            budget.start(); observation.phase("CONNECT_ACTIVATE"); timeline.add(control, "NATIVE_CONNECT_BEGIN"); out.put("activation", "FAIL");
            future = client.connectAsync(StringCodec.UTF8, uri);
            future.whenComplete((value,failure)->timeline.add(control, failure == null ? "CONNECT_FUTURE_SUCCESS" : "CONNECT_FUTURE_FAILURE"));
            connection = future.get(20,TimeUnit.SECONDS); out.put("activation", "PASS");
            SocketAddress selected = future.getRemoteAddress();
            out.put("same_dns_target_as_a", selected instanceof InetSocketAddress remote && remote.getAddress()!=null ? (addresses[0].equals(remote.getAddress())?"MATCH":"MISMATCH") : "NOT_OBSERVED");
            out.put("selected_address_family", selected instanceof InetSocketAddress remote && remote.getAddress()!=null ? family(remote.getAddress()) : "NOT_OBSERVED");
            running(); pings++; observation.phase("PING"); timeline.add(control,"PING_BEGIN"); out.put("ping","FAIL");
            require("PONG".equals(connection.async().ping().get(3,TimeUnit.SECONDS)),"PING_RESPONSE");
            timeline.add(control,"PING_PASS"); out.put("ping","PASS"); out.put("status","PASS");
        } catch (Exception failure) {
            observation.failure(failure); timeline.add(control,"CONTROL_FAIL");
        } finally {
            timeline.add(control,"CONTROL_CLEANUP_BEGIN");
            if (future != null && !future.isDone()) { timeline.add(control,"CONNECT_FUTURE_CANCEL_REQUEST"); future.cancel(true); }
            out.put("connect_future_cancelled",future != null && future.isCancelled());
            if (future != null) {
                SocketAddress selected=future.getRemoteAddress();
                if(selected instanceof InetSocketAddress remote && remote.getAddress()!=null) {
                    out.put("same_dns_target_as_a",addresses[0].equals(remote.getAddress())?"MATCH":"MISMATCH");
                    out.put("selected_address_family",family(remote.getAddress()));
                }
            }
            if (connection != null) try { timeline.add(control,"CONNECTION_CLOSE_BEGIN"); connection.closeAsync().get(3,TimeUnit.SECONDS); timeline.add(control,"CONNECTION_CLOSED"); } catch (Exception ignored) { clean=false; }
            Channel channel = ownedChannel.get();
            if (channel != null) try { timeline.add(control,"CHANNEL_CLOSE_BEGIN"); channel.close().await(3,TimeUnit.SECONDS); clean &= !channel.isOpen() && channel.closeFuture().isDone(); } catch (Exception ignored) { clean=false; }
            out.put("channel_closed",channel==null || !channel.isOpen());
            if (client != null) try { timeline.add(control,"CLIENT_SHUTDOWN_BEGIN"); client.shutdownAsync(0,2,TimeUnit.SECONDS).get(3,TimeUnit.SECONDS); timeline.add(control,"CLIENT_SHUTDOWN_DONE"); } catch (Exception ignored) { clean=false; }
            if (resources != null) try { timeline.add(control,"RESOURCES_SHUTDOWN_BEGIN"); clean &= Boolean.TRUE.equals(resources.shutdown(0,2,TimeUnit.SECONDS).get(3,TimeUnit.SECONDS)); timeline.add(control,"RESOURCES_SHUTDOWN_DONE"); } catch (Exception ignored) { clean=false; }
            if (events != null) events.dispose();
            stopClosers.remove(closeOwned); out.put("tls_peer_hostname_matches",peerVerified.get());
            out.put("tls_future_diagnostics",tlsFailure.snapshot()); out.put("handshake_future_diagnostics",handshakeFailure.snapshot());
            out.put("completion_counts",counts.snapshot()); out.put("explicit_ping_count",pings);
            if (observe) out.put("command_start_count",observer.commandCount());
            out.put("diagnostics",observation.snapshot()); out.put("cleanup_complete",clean);
            out.put("elapsed_millis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
            out.putIfAbsent("same_dns_target_as_a","NOT_OBSERVED"); out.putIfAbsent("selected_address_family","NOT_OBSERVED");
            if (!clean) out.put("status","FAIL");
        }
        return out;
    }

    static String family(InetAddress address) { return address.getAddress().length==4 ? "IPV4" : "IPV6"; }
    static String transport() {
        String value = Transports.socketChannelClass().getSimpleName();
        return Set.of("NioSocketChannel","EpollSocketChannel","KQueueSocketChannel").contains(value) ? value : "UNLISTED_TRANSPORT";
    }
    static Map<String,Boolean> environmentFlags() {
        var out = new LinkedHashMap<String,Boolean>();
        for (String key : List.of("http.proxyHost","https.proxyHost","socksProxyHost","javax.net.ssl.trustStore","java.net.preferIPv4Stack","java.net.preferIPv6Addresses","io.netty.transport.noNative"))
            out.put(key.replace('.','_')+"_configured",System.getProperty(key)!=null);
        out.put("system_proxies_enabled",Boolean.getBoolean("java.net.useSystemProxies"));
        return out;
    }

    public static void main(String[] args) {
        System.setOut(new PrintStream(OutputStream.nullOutputStream())); System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        Map<String,Object> out = new LinkedHashMap<>(); var controls = new LinkedHashMap<String,Object>();
        out.put("status","FAIL"); out.put("read_only_operation",OPERATION);
        out.put("owned_remote_resources_created",false); out.put("remote_key_writes",0); out.put("database_operations",0); out.put("login_attempts",0);
        out.put("automatic_retry",false); out.put("product_condition_exact",false);
        out.put("ssl_provider","JDK_DEFAULT_TRUST");
        out.put("resolver","SHARED_JDK_HOSTNAME_LOOKUP"); out.put("os_dns_physical_connections","NOT_OBSERVED");
        out.put("probe_owned_dns_sockets",0); out.put("command_reservation",256); out.put("connection_limit",3);
        out.put("conditional_c_reason","NOT_RUN_NO_SUPPORTED_BASIS");
        var budget = new ConnectionBudget(); var timeline = new Timeline(); var failures = new RedisActivationObservation();
        var executor = Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"bounded-jdk-dns");thread.setDaemon(true);return thread;});
        Path result = null, ledger = null; boolean clean = true;
        Thread mainThread=Thread.currentThread(); CountDownLatch finished=new CountDownLatch(1);
        Thread hook=new Thread(()->{stopping.set(true); for(Runnable close:stopClosers)close.run(); mainThread.interrupt(); try{finished.await(20,TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}},"transport-owned-cleanup");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            require(args.length==0,"NO_ARGUMENTS");
            Path input = ManagedProviderProbe.privateFile(System.getenv("MANAGED_REDIS_CONFIG"));
            result = ManagedProviderProbe.privateFile(System.getenv("MANAGED_REDIS_RESULT"));
            ledger = ManagedProviderProbe.privateFile(System.getenv("MANAGED_REDIS_LEDGER"));
            Map<String,String> values = new LinkedHashMap<>();
            ManagedProviderProbe.JSON.readTree(Files.readAllBytes(input)).fields().forEachRemaining(entry->values.put(entry.getKey(),entry.getValue().asText()));
            RedisProperties settings = ManagedRedisConnectivityProbe.productRedisSettings(values);
            out.put("product_configdata","PASS"); timeline.add("RUN","INPUT_READY");
            var uri = uri(settings); var options = options(settings);
            out.put("setting_matches",Map.of("host_matches",values.get("REDIS_HOST").equals(uri.getHost()),"port_matches",Integer.parseInt(values.get("REDIS_PORT"))==uri.getPort(),"username_matches",values.get("REDIS_USERNAME").equals(uri.getUsername()),"password_matches",Arrays.equals(values.get("REDIS_PASSWORD").toCharArray(),uri.getPassword()),"tls_matches",uri.isSsl()&&uri.getVerifyMode()==SslVerifyMode.FULL,"database_matches",uri.getDatabase()==0));
            out.put("effective_settings",Map.ofEntries(Map.entry("connect_timeout_millis",3000),Map.entry("activation_timeout_millis",2000),Map.entry("command_timeout_millis",2000),Map.entry("ssl_handshake_timeout_millis",options.getSslOptions().getHandshakeTimeout().toMillis()),Map.entry("tls_verify_mode","FULL"),Map.entry("protocol_configured","DEFAULT_NEGOTIATION"),Map.entry("protocol_effective_preconnect",options.getProtocolVersion().name()),Map.entry("command_timeout_enabled",options.getTimeoutOptions().isTimeoutCommands()),Map.entry("auto_reconnect",false),Map.entry("request_queue_limit",options.getRequestQueueSize()),Map.entry("jdk_dns_deadline_millis",5000),Map.entry("native_control_deadline_millis",20000)));
            out.put("versions",Map.of("java",Runtime.version().toString(),"lettuce",RedisClient.class.getPackage().getImplementationVersion(),"netty",io.netty.util.Version.identify().get("netty-common").artifactVersion()));
            out.put("native_transport",transport()); out.put("environment_flags",environmentFlags());
            require(!Boolean.getBoolean("java.net.useSystemProxies") && System.getProperty("socksProxyHost")==null && System.getProperty("javax.net.ssl.trustStore")==null,"UNEXPECTED_JVM_NETWORK_OVERRIDE");
            failures.phase("DNS"); timeline.add("RUN","DNS_BEGIN");
            InetAddress[] addresses;
            try { addresses=executor.submit(()->InetAddress.getAllByName(settings.getHost())).get(5,TimeUnit.SECONDS); timeline.add("RUN","DNS_PASS"); }
            catch (Exception failure) { timeline.add("RUN","DNS_FAIL"); throw failure; }
            require(addresses.length>0,"DNS_EMPTY");
            boolean hostPreserved = new InetSocketAddress(addresses[0],settings.getPort()).getHostString().equals(settings.getHost());
            require(hostPreserved,"DNS_ORIGINAL_HOSTNAME_REQUIRED"); out.put("dns_hostname_preserved",hostPreserved); out.put("jdk_selected_address_family",family(addresses[0]));
            out.put("prior_run_dns_target_comparison","NOT_ESTABLISHED");
            var a = jdk(settings.getHost(),settings.getPort(),addresses[0],(SSLSocketFactory)SSLSocketFactory.getDefault(),budget,timeline); controls.put("A",a);
            if ("PASS".equals(a.get("status"))) {
                running();
                var b = nativeControl("B",settings,addresses,budget,timeline,false); controls.put("B",b);
                if ("PASS".equals(b.get("status"))) {
                    running();
                    out.put("conditional_c_reason","B_SUCCESS_OBSERVER_ONLY_COMPARISON");
                    var c = nativeControl("C",settings,addresses,budget,timeline,true); controls.put("C",c);
                    if ("PASS".equals(c.get("status"))) out.put("status","PASS");
                }
            }
        } catch (Exception failure) { failures.failure(failure); }
        finally {
            Thread.interrupted(); timeline.add("RUN","RUN_CLEANUP_BEGIN"); executor.shutdownNow();
            try { clean=executor.awaitTermination(3,TimeUnit.SECONDS); } catch (InterruptedException failure) { clean=false; }
            for (Object control : controls.values()) clean &= Boolean.TRUE.equals(((Map<?,?>)control).get("cleanup_complete"));
            controls.putIfAbsent("A",Map.of("status","NOT_RUN")); controls.putIfAbsent("B",Map.of("status","NOT_RUN")); controls.putIfAbsent("C",Map.of("status","NOT_RUN"));
            timeline.add("RUN","RUN_CLEANUP_DONE"); out.put("controls",controls); out.put("lifecycle",timeline.snapshot());
            out.put("target_connections_started",budget.used()); out.put("elapsed_millis",timeline.elapsed()); out.put("cleanup_complete",clean); out.put("diagnostics",failures.snapshot());
            if (!clean || stopping.get()) out.put("status","FAIL");
            try {
                if (ledger!=null) Files.write(ledger,ManagedProviderProbe.JSON.writeValueAsBytes(Map.of("cleanupComplete",clean,"owned_remote_resources_created",false,"data_key_commands_allowed",false,"commands_reserved",256,"target_connections_reserved",3,"read_only_operation",OPERATION)));
                if (result!=null) Files.write(result,ManagedProviderProbe.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(out));
            } catch (Exception failure) { out.put("status","FAIL"); }
            finished.countDown(); if(!stopping.get()) Runtime.getRuntime().removeShutdownHook(hook);
        }
        System.exit("PASS".equals(out.get("status"))?0:1);
    }
}
