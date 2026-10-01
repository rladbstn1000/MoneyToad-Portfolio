package com.potg.don.auth.demo;

import java.net.URI;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoAuthHttpConfiguration implements WebMvcConfigurer {
    private final Settings settings;
    private final DemoGatewaySettings gateway;
    private final ObjectProvider<DemoAbuseLimiter> limiter;

    public DemoAuthHttpConfiguration(Environment environment, ObjectProvider<DemoAbuseLimiter> limiter) {
        this.settings = validatedSettings(environment);
        this.gateway = DemoGatewaySettings.validatedSettings(environment);
        this.limiter = limiter;
    }
    @Bean public DemoGatewaySettings demoGatewaySettings() { return gateway; }
    @Bean
    @ConditionalOnProperty(name = "app.deployment.kind", havingValue = "public-demo")
    public DemoAbuseLimiter demoAbuseLimiter() { return new DemoAbuseLimiter(); }
    @Bean public Settings demoHttpSettings() { return settings; }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new DemoAuthRequestInterceptor(settings)).addPathPatterns("/auth/demo/**").order(0);
        if (gateway.enabled()) registry.addInterceptor(new DemoAbuseRequestInterceptor(limiter.getObject()))
            .addPathPatterns("/auth/demo/**").order(1);
    }

    public static UrlBasedCorsConfigurationSource cors(Settings settings) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(settings.origin()));
        config.setAllowCredentials(true);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-MoneyToad-Demo", "Accept"));
        config.setExposedHeaders(settings.secure() ? List.of("Location", "Content-Disposition", "Retry-After")
            : List.of("Location", "Content-Disposition"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /** Called by the early profile guard before ordinary singleton/DB initialization. */
    public static Settings validatedSettings(Environment environment) {
        try {
            String origin = environment.getProperty("app.demo.browser-origin");
            if (origin == null || origin.isBlank()) throw invalid();
            URI uri = URI.create(origin);
            String kind = environment.getProperty("app.deployment.kind");
            boolean secure = "public-demo".equals(kind);
            if (!secure && !"local-demo".equals(kind)) throw invalid();
            int port = uri.getPort();
            if (uri.getHost() == null || uri.getRawUserInfo() != null || !uri.getRawPath().isEmpty()
                || uri.getRawQuery() != null || uri.getRawFragment() != null || port == 0 || port > 65535
                || !(secure ? "https" : "http").equals(uri.getScheme())
                || port == (secure ? 443 : 80)) throw invalid();
            String canonical = uri.getScheme() + "://" + uri.getHost().toLowerCase(java.util.Locale.ROOT)
                + (port == -1 ? "" : ":" + port);
            if (!origin.equals(canonical)) throw invalid();
            if (!secure && (!List.of("localhost", "127.0.0.1").contains(uri.getHost())
                || !"127.0.0.1".equals(environment.getProperty("server.address"))
                || !"false".equals(environment.getProperty("server.ssl.enabled", "false")))) throw invalid();
            return new Settings(origin, secure);
        } catch (RuntimeException failure) {
            // Never include supplied origins, arbitrary properties or credentials.
            throw invalid();
        }
    }

    private static IllegalStateException invalid() { return new IllegalStateException("DEMO_AUTH_PROFILE: INVALID_HTTP_SETTINGS"); }
    public record Settings(String origin, boolean secure) { }
}
