package com.potg.don.auth.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.potg.don.analysisJob.repository.AnalysisJobRepository;
import com.potg.don.analysisJob.scheduler.AnalysisJobScheduler;
import com.potg.don.analysisJob.service.AnalysisJobService;
import com.potg.don.auth.jwt.JwtAuthenticationFilter;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** Bean registration and real JWT filter/guard logging, with strict repository doubles only. */
class RenderDemoBoundaryTest {
	@ParameterizedTest(name = "demo scheduler absent for {0}")
	@ValueSource(strings = {"demo", "demo,render"})
	void demoHasNoSchedulerBeanTaskOrPolling(String profiles) {
		AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
		AnalysisJobService service = mock(AnalysisJobService.class);
		new ApplicationContextRunner().withPropertyValues("spring.profiles.active=" + profiles)
			.withBean(AnalysisJobRepository.class, () -> jobs).withBean(AnalysisJobService.class, () -> service)
			.withUserConfiguration(SchedulingOnly.class).run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(AnalysisJobScheduler.class);
				assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).isEmpty();
				verifyNoInteractions(jobs, service);
			});
		verifyNoInteractions(jobs, service);
	}

	@ParameterizedTest(name = "original scheduler retained for {0}")
	@ValueSource(strings = {"default", "prod", "production"})
	void originalModeStillRegistersItsScheduledTask(String profile) {
		AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
		when(jobs.findPollableJobs(any(), any())).thenReturn(List.of());
		new ApplicationContextRunner().withPropertyValues("spring.profiles.active=" + profile)
			.withBean(AnalysisJobRepository.class, () -> jobs)
			.withBean(AnalysisJobService.class, () -> mock(AnalysisJobService.class))
			.withUserConfiguration(SchedulingOnly.class).run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(AnalysisJobScheduler.class);
				assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(1);
			});
	}

	@Test
	void demoSuccessAndRejectionLogOnlyNonIdentifyingEvents() throws Exception {
		String malformedAccess = java.util.UUID.randomUUID().toString();
		var jwt = DemoJwtContractTest.configuredJwt();
		var sessions = mock(DemoSessionStore.class);
		var users = mock(UserRepository.class);
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		User user = User.createUser("synthetic-log-canary@moneytoad.invalid", "synthetic-display-canary");
		ReflectionTestUtils.setField(user, "id", 71483925L);
		when(users.findById(user.getId())).thenReturn(Optional.of(user));
		when(sessions.findActive(DemoJwtContractTest.SID)).thenReturn(Optional.of(
			new DemoSessionStore.SessionIdentity(user.getId(), now.plusSeconds(3600))));
		String access = jwt.createDemoAccessToken(user.getId(), DemoJwtContractTest.SID, now, now.plusSeconds(300));
		String refresh = jwt.createDemoRefreshToken(user.getId(), DemoJwtContractTest.SID,
			DemoJwtContractTest.JTI, now, now.plusSeconds(3600));
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("demo", "render");
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		beans.addBean("guard", new DemoSessionGuard(jwt, sessions));
		var filter = new JwtAuthenticationFilter(jwt, users, env, beans.getBeanProvider(DemoSessionGuard.class));
		Logger logger = (Logger) LoggerFactory.getLogger(JwtAuthenticationFilter.class);
		Level previous = logger.getLevel();
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		logger.setLevel(Level.INFO);
		try {
			assertThat(request(filter, access, refresh)).isEqualTo(200);
			assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
			SecurityContextHolder.clearContext();
			assertThat(request(filter, malformedAccess, refresh)).isEqualTo(401);
			assertThat(request(filter, null, refresh)).isEqualTo(401);
			String messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
				.collect(java.util.stream.Collectors.joining("\n"));
			assertThat(messages.contains("DEMO_AUTHENTICATED")).isTrue();
			assertThat(messages.contains("DEMO_AUTH_TOKEN_REQUIRED")).isTrue();
			List<String> forbidden = List.of(user.getEmail(), user.getName(), Long.toString(user.getId()), access, refresh,
				DemoJwtContractTest.SID, DemoSessionService.hash(refresh), malformedAccess, "demoRefreshToken=", "Bearer ");
			assertThat(forbidden.stream().noneMatch(messages::contains)).as("logs contain no synthetic identity or auth canary").isTrue();
			assertThat(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null)).isTrue();
		} finally {
			SecurityContextHolder.clearContext();
			logger.setLevel(previous);
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	private static int request(JwtAuthenticationFilter filter, String bearer, String refresh) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
		request.setContextPath("/api");
		if (bearer != null) request.addHeader("Authorization", "Bearer " + bearer);
		request.addHeader("Cookie", "demoRefreshToken=" + refresh);
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());
		return response.getStatus();
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableScheduling
	@Import(AnalysisJobScheduler.class)
	static class SchedulingOnly { }
}
