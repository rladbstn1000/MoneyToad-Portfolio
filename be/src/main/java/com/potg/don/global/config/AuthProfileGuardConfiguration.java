package com.potg.don.global.config;

import java.util.Set;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
public class AuthProfileGuardConfiguration {

	@Bean
	public static BeanFactoryPostProcessor authProfileGuard(Environment environment) {
		// Validate before ordinary singletons (including DB initialization) are created.
		return beanFactory -> {
			String enabled = environment.getProperty("app.demo.enabled", "false");
			String kind = environment.getProperty("app.deployment.kind", "standard");
			if (!Set.of("true", "false").contains(enabled)) {
				throw invalid("INVALID_ENABLED");
			}
			if (!Set.of("standard", "local-demo", "public-demo").contains(kind)) {
				throw invalid("INVALID_DEPLOYMENT_KIND");
			}
			boolean demo = environment.acceptsProfiles(Profiles.of("demo"));
			if (demo && environment.acceptsProfiles(Profiles.of("prod", "production"))) {
				throw invalid("PROFILE_CONFLICT");
			}
			if (demo && (!"true".equals(enabled) || "standard".equals(kind))) {
				throw invalid("DEMO_SETTINGS_REQUIRED");
			}
			if (!demo && (!"false".equals(enabled) || !"standard".equals(kind))) {
				throw invalid("STANDARD_SETTINGS_REQUIRED");
			}
			if (demo) {
				com.potg.don.auth.demo.DemoAuthHttpConfiguration.validatedSettings(environment);
				com.potg.don.auth.demo.DemoGatewaySettings.validatedSettings(environment);
			}
			if (environment.acceptsProfiles(Profiles.of("render"))) RenderRuntimeSettings.validate(environment);
		};
	}

	private static IllegalStateException invalid(String reason) {
		// Never include arbitrary configuration values or credentials in startup errors.
		return new IllegalStateException("DEMO_AUTH_PROFILE: " + reason);
	}
}
