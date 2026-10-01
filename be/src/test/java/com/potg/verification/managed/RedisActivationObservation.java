package com.potg.verification.managed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded, value-free observation only. Never changes or rejects client commands. */
final class RedisActivationObservation {
    private static final int MAX_EVENTS = 64;
    private static final int MAX_EXCEPTIONS = 12;
    private static final int MAX_RELATIONS = 24;
    private static final List<String> STAGES = List.of("DNS", "TCP", "TLS", "HANDSHAKE", "ACTIVE", "EXPLICIT_PING");
    private static final List<String> COMMANDS = List.of("HELLO", "AUTH", "PING", "CLIENT", "SELECT", "UNEXPECTED_COMMAND");
    private static final Set<String> PHASES = Set.of("NOT_OBSERVED", "INPUT", "CONFIGDATA", "CLIENT_RESOURCES",
        "CLIENT_OPTIONS", "FACTORY_START", "CONNECT_ACTIVATE", "PING", "CLEANUP", "COMPLETE",
        "DNS", "TCP", "TLS", "HANDSHAKE", "ACTIVE", "EXPLICIT_PING");
    private static final Set<String> CLASSES = Set.of(
        "java.lang.Exception", "java.lang.RuntimeException", "java.lang.IllegalStateException", "java.lang.IllegalArgumentException",
        "java.util.concurrent.ExecutionException", "java.util.concurrent.CompletionException", "java.util.concurrent.TimeoutException",
        "java.util.concurrent.CancellationException", "java.io.IOException", "java.io.EOFException",
        "java.nio.channels.ClosedChannelException", "java.net.UnknownHostException", "java.net.ConnectException",
        "java.net.NoRouteToHostException", "java.net.SocketException", "java.net.SocketTimeoutException",
        "javax.net.ssl.SSLException", "javax.net.ssl.SSLHandshakeException", "javax.net.ssl.SSLPeerUnverifiedException",
        "java.security.cert.CertificateException", "java.security.cert.CertificateExpiredException",
        "java.security.cert.CertificateNotYetValidException", "java.security.cert.CertPathValidatorException",
        "sun.security.validator.ValidatorException", "sun.security.provider.certpath.SunCertPathBuilderException",
        "org.springframework.data.redis.RedisConnectionFailureException", "org.springframework.data.redis.RedisSystemException",
        "org.springframework.dao.DataAccessResourceFailureException", "org.springframework.dao.InvalidDataAccessApiUsageException",
        "org.springframework.dao.QueryTimeoutException", "org.springframework.boot.context.properties.bind.BindException",
        "io.lettuce.core.RedisException", "io.lettuce.core.RedisConnectionException", "io.lettuce.core.RedisCommandExecutionException",
        "io.lettuce.core.RedisCommandTimeoutException", "io.lettuce.core.RedisCommandInterruptedException", "io.lettuce.core.RedisProtocolException",
        "io.netty.channel.AbstractChannel$AnnotatedConnectException", "io.netty.channel.ConnectTimeoutException",
        "io.netty.resolver.dns.DnsNameResolverException", "io.netty.resolver.dns.DnsNameResolverTimeoutException",
        "io.netty.handler.ssl.SslHandshakeTimeoutException", "io.netty.handler.ssl.NotSslRecordException",
        "io.netty.handler.codec.DecoderException", "io.netty.handler.codec.CorruptedFrameException", "io.netty.handler.codec.TooLongFrameException",
        "com.potg.verification.managed.ManagedSqlContracts$ContractFailure");
    // Exact DefaultEndpoint messages verified in the installed Lettuce 6.6.0.RELEASE JAR.
    private static final Pattern QUEUE_LIMIT = Pattern.compile(
        "(?:Request queue|Command buffer) size exceeded: [0-9]{1,10}\\. Commands are not accepted until the queue size drops\\.");

    private String phase = "NOT_OBSERVED";
    private String failureCategory = "NOT_OBSERVED";
    private final Map<String, String> stages = new LinkedHashMap<>();
    private final Map<String, Map<String, Long>> commands = new LinkedHashMap<>();
    private final List<Map<String, Object>> events = new ArrayList<>();
    private long eventSequence;
    private List<Map<String, Object>> exceptionNodes = List.of();
    private List<Map<String, Object>> exceptionRelations = List.of();
    private boolean exceptionsTruncated;

    RedisActivationObservation() {
        STAGES.forEach(stage -> stages.put(stage, "NOT_OBSERVED"));
        COMMANDS.forEach(command -> {
            var counts = new LinkedHashMap<String, Long>();
            for (String state : List.of("STARTED", "PASS", "FAIL", "UNEXPECTED_STATE")) counts.put(state, 0L);
            commands.put(command, counts);
        });
    }

    synchronized void phase(String value) {
        phase = value != null && PHASES.contains(value) ? value : "UNEXPECTED_PHASE";
        event("PHASE", phase, "NOT_OBSERVED");
    }

    synchronized void stage(String stage, String state) {
        String code = stage != null && STAGES.contains(stage) ? stage : "UNEXPECTED_STAGE";
        String fixedState = state(state, true);
        if (stages.containsKey(code)) stages.put(code, fixedState);
        event("STAGE", code, fixedState);
    }

    synchronized void command(String command, String state) {
        String code = command != null && COMMANDS.contains(command) ? command : "UNEXPECTED_COMMAND";
        String fixedState = state(state, false);
        commands.get(code).compute(fixedState, (ignored, count) -> count + 1);
        event("COMMAND", code, fixedState);
    }

    synchronized void failure(Throwable failure) {
        var graph = new ExceptionGraph();
        if (failure != null) graph.visit(failure, 0, true);
        exceptionNodes = List.copyOf(graph.nodes);
        exceptionRelations = List.copyOf(graph.relations);
        exceptionsTruncated = graph.truncated;
        failureCategory = graph.category;
        event("FAILURE", failureCategory, "FAIL");
    }

    synchronized Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase", phase);
        result.put("stages", Collections.unmodifiableMap(new LinkedHashMap<>(stages)));
        Map<String, Object> commandCopy = new LinkedHashMap<>();
        commands.forEach((command, counts) -> commandCopy.put(command, Collections.unmodifiableMap(new LinkedHashMap<>(counts))));
        result.put("commands", Collections.unmodifiableMap(commandCopy));
        result.put("events", List.copyOf(events));
        result.put("events_total", eventSequence);
        result.put("events_dropped", eventSequence - events.size());
        result.put("failure_category", failureCategory);
        result.put("exception_nodes", exceptionNodes);
        result.put("exception_relations", exceptionRelations);
        result.put("exceptions_truncated", exceptionsTruncated);
        return Collections.unmodifiableMap(result);
    }

    private static String state(String value, boolean allowUnobserved) {
        if ("SUCCEEDED".equals(value)) return "PASS";
        if ("FAILED".equals(value)) return "FAIL";
        if ("STARTED".equals(value) || "PASS".equals(value) || "FAIL".equals(value)) return value;
        if (allowUnobserved && "NOT_OBSERVED".equals(value)) return value;
        return "UNEXPECTED_STATE";
    }

    private void event(String kind, String code, String state) {
        long sequence = ++eventSequence;
        if (events.size() < MAX_EVENTS)
            events.add(Map.of("sequence", sequence, "kind", kind, "code", code, "state", state));
    }

    private final class ExceptionGraph {
        private final IdentityHashMap<Throwable, Integer> positions = new IdentityHashMap<>();
        private final List<Map<String, Object>> nodes = new ArrayList<>();
        private final List<Map<String, Object>> relations = new ArrayList<>();
        private boolean truncated;
        private String category = "UNKNOWN";
        private int categoryDepth = -1;
        private boolean categoryOnCausePath;

        private int visit(Throwable failure, int depth, boolean causePath) {
            Integer existing = positions.get(failure);
            if (existing != null) return existing;
            if (nodes.size() >= MAX_EXCEPTIONS) { truncated = true; return -1; }
            int position = nodes.size();
            positions.put(failure, position);
            String rawClass = failure.getClass().getName();
            String className = CLASSES.contains(rawClass) ? rawClass : "UNLISTED_EXCEPTION_CLASS";
            nodes.add(Map.of("position", position, "exception_class", className, "depth", depth));
            String found = classify(failure, className);
            if (!found.equals("UNKNOWN") && (categoryDepth < 0 || causePath && !categoryOnCausePath
                    || causePath == categoryOnCausePath && depth > categoryDepth)) {
                category = found; categoryDepth = depth; categoryOnCausePath = causePath;
            }
            Throwable cause = failure.getCause();
            if (cause != null) link(position, cause, depth + 1, causePath, "CAUSE");
            Throwable[] suppressed = failure.getSuppressed();
            for (int index = 0; index < suppressed.length; index++) {
                if (relations.size() >= MAX_RELATIONS) { truncated = true; break; }
                link(position, suppressed[index], depth + 1, false, "SUPPRESSED");
                if (nodes.size() >= MAX_EXCEPTIONS && index + 1 < suppressed.length) { truncated = true; break; }
            }
            return position;
        }

        private void link(int from, Throwable target, int depth, boolean causePath, String relation) {
            if (relations.size() >= MAX_RELATIONS) { truncated = true; return; }
            int to = visit(target, depth, causePath);
            if (to >= 0 && relations.size() < MAX_RELATIONS)
                relations.add(Map.of("from", from, "to", to, "relation", relation));
            else if (to >= 0) truncated = true;
        }
    }

    private String classify(Throwable failure, String className) {
        if (className.equals("UNLISTED_EXCEPTION_CLASS")) return "UNKNOWN";
        if (className.equals("java.net.UnknownHostException") || className.startsWith("io.netty.resolver.dns.")) return "DNS_FAILURE";
        if (Set.of("java.net.ConnectException", "java.net.NoRouteToHostException", "java.net.SocketException",
                "java.nio.channels.ClosedChannelException", "java.io.EOFException", "io.netty.channel.AbstractChannel$AnnotatedConnectException",
                "io.netty.channel.ConnectTimeoutException").contains(className)) return "TCP_FAILURE";
        if (className.equals("java.net.SocketTimeoutException") && phase.equals("TCP")) return "TCP_TIMEOUT";
        if (className.equals("io.netty.handler.ssl.SslHandshakeTimeoutException")) return "HANDSHAKE_TIMEOUT";
        if (Set.of("java.util.concurrent.TimeoutException", "java.net.SocketTimeoutException", "io.lettuce.core.RedisCommandTimeoutException",
                "org.springframework.dao.QueryTimeoutException").contains(className))
            return phase.equals("PING") || phase.equals("EXPLICIT_PING") ? "COMMAND_TIMEOUT" : "HANDSHAKE_TIMEOUT";
        if (className.startsWith("io.netty.handler.codec.") || className.equals("io.lettuce.core.RedisProtocolException")) return "DECODE_FAILURE";
        if (className.equals("com.potg.verification.managed.ManagedSqlContracts$ContractFailure")
                || className.equals("org.springframework.boot.context.properties.bind.BindException")) return "LOCAL_PROBE_REJECTED";
        // Messages are inspected in memory only for reviewed patterns; never projected.
        String message = failure.getMessage();
        if (className.startsWith("io.lettuce.core.") && message != null) {
            if (QUEUE_LIMIT.matcher(message).matches()) return "LOCAL_PROBE_REJECTED";
            if (prefix(message, "NOAUTH")) return "AUTH_REQUIRED";
            if (prefix(message, "WRONGPASS")) return "AUTH_REJECTED";
            if (prefix(message, "NOPERM")) return "ACL_DENIED";
            if (prefix(message, "NOPROTO")) return "PROTOCOL_UNSUPPORTED";
            if (message.startsWith("ERR unknown command ")) return "COMMAND_UNSUPPORTED";
            if (message.equals("ERR max number of clients reached") || message.equals("ERR max concurrent connections exceeded")) return "CONNECTION_LIMIT";
            if (message.startsWith("ERR max requests limit exceeded") || message.startsWith("ERR max daily request limit exceeded")) return "QUOTA_REJECTED";
        }
        if (className.startsWith("javax.net.ssl.") || className.startsWith("java.security.cert.")
                || className.startsWith("sun.security.")) {
            if (message != null && (message.startsWith("No subject alternative DNS name matching ")
                    || message.startsWith("No name matching ") || message.startsWith("No subject alternative names matching IP address ")))
                return "TLS_HOSTNAME_FAILURE";
            if (className.startsWith("java.security.cert.") || className.startsWith("sun.security.")) return "TLS_TRUST_FAILURE";
            return "TLS_FAILURE";
        }
        if (className.equals("io.netty.handler.ssl.NotSslRecordException")) return "TLS_FAILURE";
        return "UNKNOWN";
    }

    private static boolean prefix(String message, String prefix) {
        return message.equals(prefix) || message.startsWith(prefix + " ");
    }
}
