package com.potg.verification.managed;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisException;

/** Synthetic projection contracts only: no socket, input file, environment or provider access. */
class RedisActivationObservationTest {
    private static final String CANARY = "SYNTHETIC_PRIVATE_CANARY_NEVER_PROJECT";

    @Test void startsWithEveryStageUnobserved() {
        var observation = new RedisActivationObservation();
        var snapshot = observation.snapshot();
        assertEquals("NOT_OBSERVED", snapshot.get("phase"));
        assertEquals("NOT_OBSERVED", snapshot.get("failure_category"));
        assertEquals(6, ((Map<?, ?>) snapshot.get("stages")).size());
        assertTrue(((Map<?, ?>) snapshot.get("stages")).values().stream().allMatch("NOT_OBSERVED"::equals));
    }

    @Test void onlyExplicitlyObservedStagesChangeAndSnapshotsAreDetached() {
        var observation = new RedisActivationObservation();
        var first = observation.snapshot();
        observation.phase("CONNECT_ACTIVATE"); observation.stage("TCP", "PASS");
        assertEquals("NOT_OBSERVED", ((Map<?, ?>) first.get("stages")).get("TCP"));
        assertEquals("PASS", ((Map<?, ?>) observation.snapshot().get("stages")).get("TCP"));
        assertEquals("NOT_OBSERVED", ((Map<?, ?>) observation.snapshot().get("stages")).get("TLS"));
        assertThrows(UnsupportedOperationException.class, () -> first.put("phase", "INPUT"));
    }

    @Test void commandsAreCountedWithoutAnyArgumentOrUnknownNameProjection() {
        var observation = new RedisActivationObservation();
        observation.command("HELLO", "STARTED"); observation.command("HELLO", "SUCCEEDED");
        observation.command("AUTH", "FAILED"); observation.command(CANARY, CANARY);
        Map<?, ?> commands = (Map<?, ?>) observation.snapshot().get("commands");
        assertEquals(1L, ((Map<?, ?>) commands.get("HELLO")).get("STARTED"));
        assertEquals(1L, ((Map<?, ?>) commands.get("HELLO")).get("PASS"));
        assertEquals(1L, ((Map<?, ?>) commands.get("AUTH")).get("FAIL"));
        assertEquals(1L, ((Map<?, ?>) commands.get("UNEXPECTED_COMMAND")).get("UNEXPECTED_STATE"));
        assertFalse(observation.snapshot().toString().contains(CANARY));
    }

    @Test void unknownPhaseStageStateAndNullsAreSafeFixedLabels() {
        var observation = new RedisActivationObservation();
        observation.phase(CANARY); observation.stage(CANARY, CANARY); observation.stage("TLS", null);
        observation.command(null, null); observation.failure(null);
        assertEquals("UNEXPECTED_PHASE", observation.snapshot().get("phase"));
        assertEquals("UNKNOWN", observation.snapshot().get("failure_category"));
        assertFalse(observation.snapshot().toString().contains(CANARY));
    }

    @Test void eventsAreBoundedAndSequenceIsMonotonicWithExactDropCount() {
        var observation = new RedisActivationObservation();
        for (int i = 0; i < 100; i++) observation.command("PING", "STARTED");
        var snapshot = observation.snapshot();
        var events = (List<?>) snapshot.get("events");
        assertEquals(64, events.size()); assertEquals(100L, snapshot.get("events_total"));
        assertEquals(36L, snapshot.get("events_dropped"));
        for (int i = 0; i < events.size(); i++) assertEquals((long) i + 1, ((Map<?, ?>) events.get(i)).get("sequence"));
    }

    @Test void wrappedAndSuppressedFailuresKeepRelationshipsWithoutMessageLeakage() {
        var observation = new RedisActivationObservation();
        var root = new ExecutionException(CANARY, new RedisCommandExecutionException("WRONGPASS " + CANARY));
        root.addSuppressed(new ConnectException(CANARY));
        observation.failure(root);
        var snapshot = observation.snapshot();
        assertEquals("AUTH_REJECTED", snapshot.get("failure_category"));
        assertEquals(3, ((List<?>) snapshot.get("exception_nodes")).size());
        var relations = (List<?>) snapshot.get("exception_relations");
        assertTrue(relations.stream().anyMatch(row -> ((Map<?, ?>) row).get("relation").equals("CAUSE")));
        assertTrue(relations.stream().anyMatch(row -> ((Map<?, ?>) row).get("relation").equals("SUPPRESSED")));
        assertFalse(snapshot.toString().contains(CANARY));
    }

    @Test void deepestRecognizedCauseWinsOverOuterMessageCategory() {
        var outer = new RedisCommandExecutionException("NOPERM " + CANARY);
        outer.initCause(new UnknownHostException(CANARY));
        assertCategory(outer, "DNS_FAILURE");
    }

    @Test void causeCyclesAreFiniteAndUnlistedClassNamesStayHidden() {
        var one = new SyntheticCanaryException(); var two = new SyntheticCanaryException();
        one.next = two; two.next = one; one.addSuppressed(two);
        var observation = new RedisActivationObservation(); observation.failure(one);
        var snapshot = observation.snapshot();
        assertEquals(2, ((List<?>) snapshot.get("exception_nodes")).size());
        assertEquals(3, ((List<?>) snapshot.get("exception_relations")).size());
        assertEquals("UNKNOWN", snapshot.get("failure_category"));
        assertFalse(snapshot.toString().contains("SyntheticCanaryException"));
        assertFalse(snapshot.toString().contains(CANARY));
    }

    @Test void causeAndSuppressedGraphsAreBounded() {
        Throwable root = new RuntimeException(CANARY);
        for (int i = 0; i < 30; i++) root = new RuntimeException(CANARY, root);
        for (int i = 0; i < 30; i++) root.addSuppressed(new RuntimeException(CANARY));
        var observation = new RedisActivationObservation(); observation.failure(root);
        var snapshot = observation.snapshot();
        assertEquals(12, ((List<?>) snapshot.get("exception_nodes")).size());
        assertTrue(((List<?>) snapshot.get("exception_relations")).size() <= 24);
        assertEquals(true, snapshot.get("exceptions_truncated"));
        assertFalse(snapshot.toString().contains(CANARY));
    }

    @Test void queueLimitMatchesOnlyInstalledDriverWording() {
        for (String kind : List.of("Request queue", "Command buffer"))
            assertCategory(new RedisException(kind + " size exceeded: 1. Commands are not accepted until the queue size drops."), "LOCAL_PROBE_REJECTED");
        assertCategory(new RedisException("Request queue size exceeded: " + CANARY), "UNKNOWN");
        assertCategory(new RuntimeException("Request queue size exceeded: 1. Commands are not accepted until the queue size drops."), "UNKNOWN");
    }

    @Test void providerErrorsUseOnlyFixedCategoriesAndIgnoreUnknownPrefixes() {
        Map<String, String> expected = Map.of("NOAUTH", "AUTH_REQUIRED", "WRONGPASS", "AUTH_REJECTED",
            "NOPERM", "ACL_DENIED", "NOPROTO", "PROTOCOL_UNSUPPORTED", "ERR unknown command", "COMMAND_UNSUPPORTED");
        expected.forEach((prefix, category) -> assertCategory(new RedisCommandExecutionException(prefix + " " + CANARY), category));
        assertCategory(new RedisCommandExecutionException("WRONGPASSWORD " + CANARY), "UNKNOWN");
        assertCategory(new RedisCommandExecutionException("ERR max number of clients reached"), "CONNECTION_LIMIT");
        assertCategory(new RedisCommandExecutionException("ERR max requests limit exceeded " + CANARY), "QUOTA_REJECTED");
    }

    @Test void timeoutCategoryUsesObservedPhaseAndTlsHostnameCauseIsSpecific() {
        var observation = new RedisActivationObservation(); observation.phase("HANDSHAKE");
        observation.failure(new TimeoutException(CANARY));
        assertEquals("HANDSHAKE_TIMEOUT", observation.snapshot().get("failure_category"));
        observation.phase("EXPLICIT_PING"); observation.failure(new TimeoutException(CANARY));
        assertEquals("COMMAND_TIMEOUT", observation.snapshot().get("failure_category"));
        assertCategory(new CertificateException("No subject alternative DNS name matching " + CANARY + " found."), "TLS_HOSTNAME_FAILURE");
        assertCategory(new CertificateException(CANARY), "TLS_TRUST_FAILURE");
    }

    private static void assertCategory(Throwable failure, String expected) {
        var observation = new RedisActivationObservation(); observation.failure(failure);
        assertEquals(expected, observation.snapshot().get("failure_category"));
        assertFalse(observation.snapshot().toString().contains(CANARY));
    }

    private static final class SyntheticCanaryException extends RuntimeException {
        private Throwable next;
        @Override public synchronized Throwable getCause() { return next; }
        @Override public String getMessage() { return CANARY; }
    }
}
