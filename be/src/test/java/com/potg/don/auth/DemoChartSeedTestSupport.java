package com.potg.don.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.potg.don.auth.jwt.JwtUtil;
import com.potg.don.card.repository.CardRepository;
import com.potg.don.demo.seed.DemoSeedService;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;

/** Test-only counters preserve actual JPA operations and never retain SQL text or bind values. */
final class DemoChartSeedTestSupport implements AutoCloseable {
    final DemoAuthHttpTestSupport support = new DemoAuthHttpTestSupport();
    final DemoAuthHttpTestSupport.Started started;
    final MockMvc mvc;
    final JdbcTemplate jdbc;
    final StringRedisTemplate redis;
    final JwtUtil jwt;
    final DemoSeedService seed;
    final UserRepository users;
    final Observations observations;
    final TransactionTemplate transaction;
    final Set<String> initialKeys;

    DemoChartSeedTestSupport() throws Exception {
        started = support.start("demo", "", "true", "local-demo", false, Map.of(), ObservationConfiguration.class);
        assertThat(started.failure() == null).as("Owned product context starts").isTrue();
        var context = started.context();
        mvc = DemoAuthHttpIntegrationTest.mvc(started);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        redis = context.getBean(StringRedisTemplate.class);
        jwt = context.getBean(JwtUtil.class);
        seed = context.getBean(DemoSeedService.class);
        users = context.getBean(UserRepository.class);
        observations = context.getBean(Observations.class);
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        initialKeys = new HashSet<>(redis.keys("demo:session:*"));
    }

    Visitor login() throws Exception {
        long began = System.nanoTime();
        var result = mvc.perform(DemoAuthHttpIntegrationTest.postRequest("login", "{}")).andReturn();
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String access = DemoAuthHttpTestSupport.JSON.readTree(result.getResponse().getContentAsString()).path("accessToken").asText();
        var claims = jwt.validateDemoAccessToken(jwt.parse(access), Instant.now());
        return new Visitor(access, claims.userId(), elapsed);
    }

    Map<String, List<Map<String, Object>>> ownedSnapshot(long userId) {
        var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
        rows.put("users", jdbc.queryForList("SELECT * FROM users WHERE id=? ORDER BY id", userId));
        rows.put("cards", jdbc.queryForList("SELECT * FROM cards WHERE user_id=? ORDER BY id", userId));
        rows.put("transactions", jdbc.queryForList("SELECT t.* FROM transactions t JOIN cards c ON t.card_id=c.id WHERE c.user_id=? ORDER BY t.id", userId));
        rows.put("budgets", jdbc.queryForList("SELECT * FROM budgets WHERE user_id=? ORDER BY id", userId));
        rows.put("analysis_job", jdbc.queryForList("SELECT * FROM analysis_job WHERE user_id=? ORDER BY id", userId));
        return rows;
    }

    void assertOwnedCounts(long userId) {
        var rows = ownedSnapshot(userId);
        assertThat(rows.get("users").size()).isEqualTo(1);
        assertThat(rows.get("cards").size()).isEqualTo(1);
        assertThat(rows.get("transactions").size()).isEqualTo(240);
        assertThat(rows.get("budgets").size()).isEqualTo(72);
        assertThat(rows.get("analysis_job")).isEmpty();
        assertThat(rows.get("cards").getFirst().get("card_no")).isNull();
        assertThat(rows.get("cards").getFirst().get("cvc")).isNull();
        assertThat(rows.get("users").getFirst().get("file_id")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budgets WHERE user_id=? AND (initial_amount IS NOT NULL OR initial_file_id IS NOT NULL OR predicted_at IS NOT NULL)", Integer.class, userId)).isZero();
    }

    void evidence(String scenario, Map<String, Object> values) throws Exception {
        assertThat(support.noOutbound(started.probe())).isTrue();
        assertThat(observations.dummyCalls.get()).isZero();
        assertThat(observations.csvCalls.get()).isZero();
        assertThat(observations.jobCalls.get()).isZero();
        var row = new LinkedHashMap<String, Object>();
        row.put("scenario", scenario); row.putAll(values);
        row.put("dummyCalls", observations.dummyCalls.get());
        row.put("csvCalls", observations.csvCalls.get());
        row.put("jobCalls", observations.jobCalls.get());
        row.put("outboundRequests", support.stubRequests.get() + started.probe().webClientBlocked.get());
        System.out.println("DEMO_CHART_SEED_EVIDENCE " + DemoAuthHttpTestSupport.JSON.writeValueAsString(row));
    }

    @Override public void close() {
        try {
            var keys = new HashSet<>(redis.keys("demo:session:*")); keys.removeAll(initialKeys);
            if (!keys.isEmpty()) redis.delete(keys);
            assertThat(support.noOutbound(started.probe())).isTrue();
            assertThat(observations.dummyCalls.get() + observations.csvCalls.get() + observations.jobCalls.get()).isZero();
        } finally { started.close(); support.close(); }
    }

    record Visitor(String access, long userId, long loginElapsedMillis) {
        @Override public String toString() { return "Visitor[redacted]"; }
    }

    static final class Observations implements StatementInspector {
        final AtomicInteger userInserts = new AtomicInteger();
        final AtomicInteger cardInserts = new AtomicInteger();
        final AtomicInteger transactionInserts = new AtomicInteger();
        final AtomicInteger budgetInserts = new AtomicInteger();
        final AtomicInteger multiRowInsertStatements = new AtomicInteger();
        final AtomicInteger nonParameterizedInserts = new AtomicInteger();
        final AtomicInteger dummyCalls = new AtomicInteger();
        final AtomicInteger csvCalls = new AtomicInteger();
        final AtomicInteger jobCalls = new AtomicInteger();
        final AtomicInteger seedCalls = new AtomicInteger();
        final AtomicLong seedNanos = new AtomicLong();
        volatile CyclicBarrier emptyCardReads;

        @Override public String inspect(String sql) {
            String statement = sql.toLowerCase(Locale.ROOT).replace("`", "").trim();
            if (!statement.startsWith("insert into ")) return sql;
            if (statement.startsWith("insert into users ")) userInserts.incrementAndGet();
            if (statement.startsWith("insert into cards ")) cardInserts.incrementAndGet();
            if (statement.startsWith("insert into transactions ")) transactionInserts.incrementAndGet();
            if (statement.startsWith("insert into budgets ")) budgetInserts.incrementAndGet();
            if (!statement.contains("?")) nonParameterizedInserts.incrementAndGet();
            if (statement.matches("(?s).*\\)\\s*,\\s*\\(.*")) multiRowInsertStatements.incrementAndGet();
            return sql;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ObservationConfiguration {
        @Bean Observations seedObservations() { return new Observations(); }
        @Bean HibernatePropertiesCustomizer seedStatementCounter(Observations observations) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", observations);
        }
        @Bean static BeanPostProcessor seedBoundaryObservers(Observations observations) {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    String type = AopUtils.getTargetClass(bean).getSimpleName();
                    boolean seed = type.equals("DemoSeedService");
                    boolean external = Set.of("DummyService", "CsvClient", "AnalysisJobService").contains(type);
                    boolean cards = bean instanceof CardRepository;
                    if (!seed && !external && !cards) return bean;
                    var proxy = new ProxyFactory(bean);
                    if (!cards) proxy.setProxyTargetClass(true);
                    proxy.addAdvice((MethodInterceptor) invocation -> {
                        String method = invocation.getMethod().getName();
                        if (external && invocation.getMethod().getDeclaringClass() != Object.class) {
                            if (type.equals("DummyService")) observations.dummyCalls.incrementAndGet();
                            if (type.equals("CsvClient")) observations.csvCalls.incrementAndGet();
                            if (type.equals("AnalysisJobService")) observations.jobCalls.incrementAndGet();
                            throw new AssertionError("SEED_TEST: unexpected external workflow");
                        }
                        if (seed && method.equals("install")) {
                            long began = System.nanoTime(); observations.seedCalls.incrementAndGet();
                            try { return invocation.proceed(); }
                            finally { observations.seedNanos.addAndGet(System.nanoTime() - began); }
                        }
                        Object result = invocation.proceed();
                        if (cards && method.equals("findByUserId") && result instanceof java.util.Optional<?> optional
                            && optional.isEmpty() && observations.emptyCardReads != null) {
                            observations.emptyCardReads.await(10, TimeUnit.SECONDS);
                        }
                        return result;
                    });
                    return proxy.getProxy();
                }
            };
        }
    }
}
