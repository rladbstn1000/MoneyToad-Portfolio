package com.potg.verification.browser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import com.potg.don.DonApplication;
import com.potg.don.auth.jwt.JwtAuthenticationFilter;
import com.potg.don.auth.demo.DemoSessionGuard;
import reactor.core.publisher.Mono;

/** E2E-only launcher outside the product scan: real embedded server and product beans. */
public final class DemoBrowserApplication {
    private static final AtomicInteger OUTBOUND = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        write("outbound", "0");
        SpringApplication app = new SpringApplication(DonApplication.class, Boundary.class);
        app.addListeners(event -> {
            if (event instanceof ApplicationReadyEvent ready) {
                var context = ready.getApplicationContext();
                var chains = context.getBeansOfType(SecurityFilterChain.class);
                boolean valid = chains.size() == 1 && chains.values().iterator().next().getFilters()
                    .stream().anyMatch(JwtAuthenticationFilter.class::isInstance)
                    && context.getBeansOfType(DemoSessionGuard.class).size() == 1
                    && context.getBeansOfType(ClientRegistrationRepository.class).isEmpty()
                    && context.getBeansOfType(com.potg.don.auth.oauth.CustomOAuth2UserService.class).isEmpty()
                    && context.getBeansOfType(com.potg.don.auth.oauth.OAuth2SuccessHandler.class).isEmpty()
                    && chains.values().iterator().next().getFilters().stream().noneMatch(filter ->
                        filter.getClass().getName().contains("OAuth2"));
                if (!valid) throw new IllegalStateException("E2E security invariant failed");
                try {
                    write("ready", Integer.toString(((ServletWebServerApplicationContext) context)
                        .getWebServer().getPort()));
                } catch (Exception error) { throw new IllegalStateException("E2E readiness failed"); }
            }
        });
        app.run(args);
    }

    private static void write(String name, String value) throws Exception {
        Files.writeString(Path.of(System.getenv("E2E_CONTROL_DIR"), name), value);
    }

    @Configuration(proxyBeanMethods = false)
    static class Boundary {
        @Bean WebClientCustomizer noOutboundHttp() {
            return builder -> builder.filter((request, next) -> {
                try { write("outbound", Integer.toString(OUTBOUND.incrementAndGet())); }
                catch (Exception error) { return Mono.error(new IllegalStateException("E2E counter failed")); }
                return Mono.error(new IllegalStateException("E2E outbound HTTP forbidden"));
            });
        }
    }
}
