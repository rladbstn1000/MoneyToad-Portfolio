package com.potg.don.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import com.potg.don.auth.SyntheticGatewayTestSupport;
import com.potg.don.auth.demo.DemoGatewaySettings;

/** Loads production YAML through ConfigData; no database or external connection is constructed. */
class DemoGatewayProfileConfigurationTest {
    @Test void publicDemoRequiresValidSecretBeforeDatabaseOrNormalSingletons() {
        for (String supplied : List.of("<absent>", "", " ", "short", "a".repeat(42), "a".repeat(44),
            "*".repeat(43), SyntheticGatewayTestSupport.secret() + " ")) {
            AtomicBoolean db = new AtomicBoolean();
            AtomicBoolean normal = new AtomicBoolean();
            var values = base("demo", "public-demo");
            if (!supplied.equals("<absent>")) values.put("DEMO_GATEWAY_SECRET", supplied);
            runner(values, db, normal).run(context -> {
                assertThat(context).hasFailed();
                assertThat(db).isFalse(); assertThat(normal).isFalse();
                boolean safeFailure = false;
                for (Throwable error = context.getStartupFailure(); error != null; error = error.getCause()) {
                    safeFailure |= "DEMO_AUTH_PROFILE: INVALID_GATEWAY_SETTINGS".equals(error.getMessage());
                    if (error.getMessage() != null && supplied.length() > 10)
                        assertThat(error.getMessage().contains(supplied)).isFalse();
                }
                assertThat(safeFailure).isTrue();
            });
        }
    }

    @Test void validPublicSettingsLoadProductConfigAndNeverRenderSecretInSettings() {
        var values = base("demo", "public-demo");
        values.put("DEMO_GATEWAY_SECRET", SyntheticGatewayTestSupport.secret());
        var db = new AtomicBoolean(); var normal = new AtomicBoolean();
        runner(values, db, normal).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(db).isTrue(); assertThat(normal).isTrue();
            var names = new java.util.ArrayList<String>();
            context.getEnvironment().getPropertySources().forEach(source -> names.add(source.getName()));
            assertThat(names.stream().anyMatch(name -> name.contains("application.yml"))).isTrue();
            assertThat(names.stream().anyMatch(name -> name.contains("application-demo.yml"))).isTrue();
            var settings = DemoGatewaySettings.validatedSettings(context.getEnvironment());
            assertThat(settings.enabled()).isTrue();
            assertThat(settings.matches(SyntheticGatewayTestSupport.secret())).isTrue();
            assertThat(settings.toString().contains(SyntheticGatewayTestSupport.secret())).isFalse();
        });
    }

    @Test void localDemoAndOAuthIgnoreGatewaySecretWithoutAValidationFallback() {
        for (String profile : List.of("demo", "default", "prod", "production")) {
            var values = base(profile, profile.equals("demo") ? "local-demo" : "standard");
            // An unrelated unusable value must not make an OAuth/local profile require this feature.
            values.put("DEMO_GATEWAY_SECRET", "not-an-active-gateway-setting");
            var db = new AtomicBoolean(); var normal = new AtomicBoolean();
            runner(values, db, normal).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(DemoGatewaySettings.validatedSettings(context.getEnvironment()).enabled()).isFalse();
                assertThat(db).isTrue(); assertThat(normal).isTrue();
            });
        }
    }

    private static Map<String, Object> base(String profile, String kind) {
        var values = new LinkedHashMap<String, Object>();
        values.put("spring.config.location", "classpath:/application.yml");
        values.put("spring.profiles.active", profile);
        values.put("APP_DEMO_ENABLED", Boolean.toString(profile.equals("demo")));
        values.put("APP_DEPLOYMENT_KIND", kind);
        values.put("APP_DEMO_BROWSER_ORIGIN", kind.equals("public-demo") ? "https://demo.example.invalid" : "http://localhost:5173");
        return values;
    }
    private static ApplicationContextRunner runner(Map<String, Object> values, AtomicBoolean db, AtomicBoolean normal) {
        return new ApplicationContextRunner().withInitializer(context -> {
            var environment = context.getEnvironment();
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            environment.getPropertySources().addFirst(new MapPropertySource("synthetic-owned-gateway-input", values));
            new ConfigDataApplicationContextInitializer().initialize(context);
        }).withUserConfiguration(AuthProfileGuardConfiguration.class)
          .withBean("observedDataSource", DataSource.class, () -> { db.set(true); return org.mockito.Mockito.mock(DataSource.class); })
          .withBean("ordinarySentinel", Object.class, () -> { normal.set(true); return new Object(); });
    }
}
