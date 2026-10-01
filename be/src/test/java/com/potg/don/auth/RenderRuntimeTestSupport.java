package com.potg.don.auth;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.GenericWebApplicationContext;

import com.potg.don.DonApplication;
import com.potg.don.analysisJob.repository.AnalysisJobRepository;
import com.potg.don.analysisJob.service.AnalysisJobService;
import com.potg.don.transaction.client.CsvClient;
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Mono;

/**
 * Runner contract: supply RUNNER_RENDER_DB_URL/DB_USERNAME/DB_PASSWORD,
 * RUNNER_RENDER_REDIS_HOST/PORT/USERNAME/PASSWORD, RUNNER_RENDER_JWT_SECRET,
 * and RUNNER_RENDER_PORT. Only runner-owned loopback ports and the
 * moneytoad_render_* database namespace are accepted. The runner supplies a
 * temporary JVM javax.net.ssl.trustStore; OS trust and actual .env are never read.
 * The URL must use sslMode=VERIFY_IDENTITY and contain no credentials.
 */
final class RenderRuntimeTestSupport implements AutoCloseable {
    static final String ORIGIN = "https://render-demo.example.invalid";
    final Probe probe = new Probe();
    private final HttpServer stub;
    private ConfigurableApplicationContext context;

    RenderRuntimeTestSupport() throws IOException {
        stub = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
        stub.createContext("/", exchange -> {
            probe.httpCalls.incrementAndGet();
            try (exchange) { exchange.sendResponseHeaders(502, -1); }
        });
        stub.start();
    }

    ConfigurableApplicationContext start(boolean prepareSchema) {
        Map<String, Object> inputs = infrastructure();
        inputs.put("AI_BASE_URL", "http://127.0.0.1:" + stub.getAddress().getPort());
        inputs.put("APP_DEMO_ENABLED", "true");
        inputs.put("APP_DEPLOYMENT_KIND", "public-demo");
        inputs.put("DEMO_GATEWAY_SECRET", required("RUNNER_RENDER_DEMO_GATEWAY_SECRET"));
        inputs.put("APP_DEMO_BROWSER_ORIGIN", ORIGIN);
        if (prepareSchema) throw new IllegalArgumentException("USE_EXPLICIT_DDL_PREPARATION");
        StandardEnvironment environment = new StandardEnvironment() {
            @Override protected void customizePropertySources(MutablePropertySources sources) { }
        };
        environment.getPropertySources().addFirst(new MapPropertySource("owned-render-runner-inputs", inputs));
        SpringApplication app = new SpringApplication(DonApplication.class, ObservationConfiguration.class);
        app.setEnvironment(environment);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setApplicationContextFactory(type -> {
            GenericWebApplicationContext created = new GenericWebApplicationContext();
            created.setServletContext(new MockServletContext());
            return created;
        });
        app.setRegisterShutdownHook(false);
        app.setLogStartupInfo(false);
        app.setBannerMode(Banner.Mode.OFF);
        app.addInitializers(created -> created.getBeanFactory().registerSingleton("renderRuntimeProbe", probe));
        context = app.run("--spring.config.location=classpath:/application.yml",
            "--spring.profiles.active=" + (prepareSchema ? "demo" : "demo,render"));
        return context;
    }

    private static Map<String, Object> infrastructure() {
        String url = required("RUNNER_RENDER_DB_URL");
        boolean allowed = false;
        try {
            URI uri = URI.create(url.substring("jdbc:".length()));
            allowed = url.startsWith("jdbc:mysql://") && loopback(uri.getHost())
                && uri.getPort() > 0 && uri.getPort() <= 65535 && uri.getPort() != 3306
                && uri.getUserInfo() == null && uri.getFragment() == null
                && uri.getPath().matches("/moneytoad_render_[a-z0-9_]+")
                && uri.getRawQuery() != null
                && java.util.Arrays.asList(uri.getRawQuery().split("&")).contains("sslMode=VERIFY_IDENTITY")
                && !uri.getRawQuery().toLowerCase(java.util.Locale.ROOT).matches(".*(?:user|password)=.*");
        } catch (RuntimeException ignored) { }
        String redisHost = required("RUNNER_RENDER_REDIS_HOST");
        int redisPort = port("RUNNER_RENDER_REDIS_PORT");
        int appPort = port("RUNNER_RENDER_PORT");
        if (!allowed || !loopback(redisHost) || redisPort == 6379 || appPort == 8080) {
            throw new IllegalStateException("Owned non-default loopback TLS infrastructure required");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("DB_URL", url);
        result.put("DB_USERNAME", required("RUNNER_RENDER_DB_USERNAME"));
        result.put("DB_PASSWORD", required("RUNNER_RENDER_DB_PASSWORD"));
        result.put("REDIS_HOST", redisHost);
        result.put("REDIS_PORT", redisPort);
        result.put("REDIS_USERNAME", required("RUNNER_RENDER_REDIS_USERNAME"));
        result.put("REDIS_PASSWORD", required("RUNNER_RENDER_REDIS_PASSWORD"));
        result.put("REDIS_SSL_ENABLED", "true");
        result.put("PORT", appPort);
        result.put("JWT_SECRET", required("RUNNER_RENDER_JWT_SECRET"));
        result.put("JWT_ACCESS_SECONDS", 300);
        result.put("JWT_REFRESH_SECONDS", 3600);
        result.put("JWT_ISSUER", "render-runtime-verification");
        return result;
    }

    static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder gateway(
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header("X-MoneyToad-Gateway", required("RUNNER_RENDER_DEMO_GATEWAY_SECRET"))
            .header("X-MoneyToad-Client-IP", "192.0.2.37");
    }

    private static boolean loopback(String host) { return "localhost".equals(host) || "127.0.0.1".equals(host); }
    private static int port(String name) {
        try {
            int value = Integer.parseInt(required(name));
            if (value > 0 && value <= 65535) return value;
        } catch (NumberFormatException ignored) { }
        throw new IllegalStateException("Owned test port is invalid: " + name);
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Owned test input is required: " + name);
        return value;
    }

    @Override public void close() {
        try { if (context != null) context.close(); }
        finally { stub.stop(0); }
    }

    static final class Probe {
        final AtomicInteger repositoryPollCalls = new AtomicInteger();
        final AtomicInteger servicePollCalls = new AtomicInteger();
        final AtomicInteger csvCalls = new AtomicInteger();
        final AtomicInteger webClientCalls = new AtomicInteger();
        final AtomicInteger httpCalls = new AtomicInteger();
        final CountDownLatch pollObserved = new CountDownLatch(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ObservationConfiguration {
        @Bean static BeanPostProcessor renderObservation(Probe probe) {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof AnalysisJobRepository) && !(bean instanceof AnalysisJobService)
                        && !(bean instanceof CsvClient)) return bean;
                    ProxyFactory proxy = new ProxyFactory(bean);
                    if (!(bean instanceof AnalysisJobRepository)) proxy.setProxyTargetClass(true);
                    proxy.addAdvice((MethodInterceptor) invocation -> {
                        String method = invocation.getMethod().getName();
                        if (bean instanceof AnalysisJobRepository && method.equals("findPollableJobs")) {
                            probe.repositoryPollCalls.incrementAndGet();
                            probe.pollObserved.countDown();
                        }
                        if (bean instanceof AnalysisJobService && method.equals("pollOnce")) {
                            probe.servicePollCalls.incrementAndGet();
                            probe.pollObserved.countDown();
                        }
                        if (bean instanceof CsvClient && !method.equals("toString") && !method.equals("hashCode")
                            && !method.equals("equals")) probe.csvCalls.incrementAndGet();
                        return invocation.proceed();
                    });
                    return proxy.getProxy();
                }
            };
        }
        @Bean WebClientCustomizer renderOutboundBlocker(Probe probe) {
            return builder -> builder.filter((request, next) -> {
                probe.webClientCalls.incrementAndGet();
                return Mono.error(new IllegalStateException("External application request blocked by owned verification"));
            });
        }
    }
}
