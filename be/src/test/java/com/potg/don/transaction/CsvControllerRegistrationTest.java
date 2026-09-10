package com.potg.don.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.StandardEnvironment;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Limited component-candidate checks only: no application startup, database or deployed profile claim. */
class CsvControllerRegistrationTest {

	@ParameterizedTest(name = "CSV controller absent with {0} profile selection")
	@ValueSource(strings = {"default", "demo", "production"})
	void deletedControllerIsNotAComponentCandidateInSelectedProfiles(String profile) throws Exception {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		if (!profile.equals("default")) {
			environment.setActiveProfiles(profile);
		}
		ClassPathScanningCandidateComponentProvider scanner =
			new ClassPathScanningCandidateComponentProvider(false, environment);
		scanner.addIncludeFilter((metadata, factory) -> metadata.getClassMetadata().getClassName()
			.equals("com.potg.don.transaction.controller.CsvTestController"));
		var candidates = scanner.findCandidateComponents("com.potg.don.transaction.controller");
		System.out.println("A2_EVIDENCE " + new ObjectMapper().writeValueAsString(Map.of(
			"scenario", "profile_registration", "profile", profile,
			"candidateCount", candidates.size(), "applicationStarted", false)));
		assertThat(candidates).as("deleted CSV controller candidates under " + profile).isEmpty();
	}
}
