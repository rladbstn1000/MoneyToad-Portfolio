package com.potg.don.auth;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.security.SecureRandom;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;

import org.hibernate.SessionFactory;

import com.potg.don.auth.jwt.JwtUtil;

import io.lettuce.core.RedisCommandExecutionException;
import jakarta.persistence.EntityManagerFactory;

/**
 * Test-only direct Redis failure classifier. Uses actual product ConfigData and
 * its StringRedisTemplate; no alternate connector, mock, trust-all or retry.
 * Input: RenderRuntimeTestSupport's owned RUNNER_RENDER_* / JVM truststore
 * contract plus RUNNER_REDIS_EXPECTED_FAILURE=CA|HOSTNAME|CREDENTIAL.
 * The only diagnostic emitted by this probe is a fixed classification tag.
 * Framework startup logs stay in the runner's private, subsequently removed log.
 * Also sends a runtime-signed synthetic DEMO_ACCESS through the actual security
 * chain: Redis failure must reject a protected endpoint before any Hibernate SQL.
 * HTTP login/token absence/SQL rollback remain separate packaged-app checks.
 */
public final class RenderRedisFailureProbe {
    private enum Kind { CA, HOSTNAME, CREDENTIAL }

    private RenderRedisFailureProbe() { }

    public static void main(String[] args) {
        int exit = run(args);
        if (exit != 0) System.exit(exit);
    }

    private static int run(String[] args) {
        Kind expected;
        try {
            if (args.length != 0) throw new IllegalArgumentException();
            expected = Kind.valueOf(System.getenv("RUNNER_REDIS_EXPECTED_FAILURE"));
        } catch (RuntimeException invalid) {
            System.out.println("RENDER_REDIS_FAILURE: INVALID_INPUT");
            return 2;
        }

        Kind observed;
        try (RenderRuntimeTestSupport fixture = new RenderRuntimeTestSupport()) {
            var context = fixture.start(false);
            StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
            EnumSet<Kind> classifications;
            try (var connection = redis.getRequiredConnectionFactory().getConnection()) {
                connection.ping();
                System.out.println("RENDER_REDIS_FAILURE: UNEXPECTED_SUCCESS");
                return 2;
            } catch (RuntimeException failure) {
                classifications = classify(failure);
            }
            if (classifications.size() != 1) {
                System.out.println("RENDER_REDIS_FAILURE: UNCLASSIFIED");
                return 2;
            }
            observed = classifications.iterator().next();
            if (observed != expected) {
                System.out.println("RENDER_REDIS_FAILURE: MISMATCH");
                return 2;
            }
            if (!rejectsProtectedRequestBeforeSql(context, fixture.probe)) {
                System.out.println("RENDER_REDIS_PROTECTED: FAILED");
                return 2;
            }
        } catch (Exception failure) {
            // A DB/startup/cleanup failure is not evidence of Redis TLS rejection.
            // Never chain or print the actual exception or connection metadata.
            System.out.println("RENDER_REDIS_FAILURE: FIXTURE_FAILURE");
            return 2;
        }
        System.out.println("RENDER_REDIS_FAILURE: " + observed.name());
        System.out.println("RENDER_REDIS_PROTECTED: REJECTED_NO_SQL");
        return 0;
    }

    private static boolean rejectsProtectedRequestBeforeSql(ConfigurableApplicationContext context,
        RenderRuntimeTestSupport.Probe probe) throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup((GenericWebApplicationContext) context)
            .apply(springSecurity()).defaultRequest(RenderRuntimeTestSupport.gateway(get("/"))).build();
        byte[] identifier = new byte[32];
        new SecureRandom().nextBytes(identifier);
        String sid = Base64.getUrlEncoder().withoutPadding().encodeToString(identifier);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        // This internal synthetic subject creates no User or session. Redis must
        // fail before the filter can look up any subject in the real repository.
        String access = context.getBean(JwtUtil.class).createDemoAccessToken(1L, sid, now, now.plusSeconds(300));
        var statistics = context.getBean(EntityManagerFactory.class).unwrap(SessionFactory.class).getStatistics();
        boolean previousStatistics = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            var response = mvc.perform(get("/api/users").contextPath("/api")
                .header("Authorization", "Bearer " + access)).andReturn().getResponse();
            return response.getStatus() == 503 && statistics.getPrepareStatementCount() == 0
                && statistics.getEntityLoadCount() == 0 && statistics.getEntityInsertCount() == 0
                && statistics.getEntityUpdateCount() == 0 && statistics.getEntityDeleteCount() == 0
                && probe.repositoryPollCalls.get() == 0 && probe.servicePollCalls.get() == 0
                && probe.csvCalls.get() == 0 && probe.webClientCalls.get() == 0 && probe.httpCalls.get() == 0;
        } finally {
            statistics.clear();
            statistics.setStatisticsEnabled(previousStatistics);
        }
    }

    private static EnumSet<Kind> classify(Throwable root) {
        EnumSet<Kind> found = EnumSet.noneOf(Kind.class);
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty() && seen.size() < 64) {
            Throwable failure = pending.removeFirst();
            if (!seen.add(failure)) continue;
            String message = failure.getMessage();
            String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
            if (failure instanceof CertPathBuilderException || failure instanceof CertPathValidatorException) {
                found.add(Kind.CA);
            }
            boolean certificateFailure = failure instanceof CertificateException
                || failure instanceof SSLHandshakeException || failure instanceof SSLPeerUnverifiedException;
            if (certificateFailure && (lower.contains("no subject alternative names matching")
                || lower.contains("no subject alternative dns name matching")
                || lower.contains("no name matching") || lower.contains("no subject alternative names present"))) {
                found.add(Kind.HOSTNAME);
            }
            if (failure instanceof RedisCommandExecutionException
                && (lower.startsWith("wrongpass ") || lower.startsWith("noauth "))) {
                found.add(Kind.CREDENTIAL);
            }
            if (failure.getCause() != null) pending.addLast(failure.getCause());
            for (Throwable suppressed : failure.getSuppressed()) pending.addLast(suppressed);
        }
        return found;
    }
}
