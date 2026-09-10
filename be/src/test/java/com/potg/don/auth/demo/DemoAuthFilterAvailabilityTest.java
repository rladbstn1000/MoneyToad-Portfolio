package com.potg.don.auth.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potg.don.auth.jwt.JwtAuthenticationFilter;
import com.potg.don.auth.jwt.JwtUtil;
import com.potg.don.user.repository.UserRepository;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** Actual JWT/guard/filter with faulting repository doubles, not a MySQL network outage test. */
class DemoAuthFilterAvailabilityTest {

	private static final String PRIVATE_DETAIL = "synthetic-database-detail-must-not-be-returned";
	private JwtUtil jwt;
	private DemoSessionStore store;
	private UserRepository users;
	private Instant now;

	@BeforeEach
	void setUp() {
		SecurityContextHolder.clearContext();
		jwt = DemoJwtContractTest.configuredJwt();
		store = mock(DemoSessionStore.class);
		users = mock(UserRepository.class);
		now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
	}

	@AfterEach
	void clearTestContext() { SecurityContextHolder.clearContext(); }

	@ParameterizedTest(name = "demo User lookup {0} is a fail-closed 503")
	@ValueSource(strings = {"connection", "transient-resource", "timeout", "transaction-connect", "transaction-timeout", "transaction-system", "transaction-rollback"})
	void demoRepositoryAvailabilityFailureStopsAuthenticationAndBusinessChain(String kind) throws Exception {
		activeSession();
		when(users.findById(7L)).thenThrow(databaseFailure(kind));
		Outcome outcome = request(filter(true), access(), "/api/auth/demo/session");
		assertStopped(outcome, 503, "Service Unavailable");
		assertThat(outcome.response().getContentAsString().contains(PRIVATE_DETAIL)).isFalse();
		assertThat(outcome.response().getHeader("Cache-Control")).isEqualTo("no-store");
		verify(store).findActive(anyString());
		verify(users).findById(7L);
		verifyNoMoreInteractions(store, users);
	}

	@ParameterizedTest(name = "ordinary mode preserves {0} repository exception propagation")
	@ValueSource(strings = {"connection", "transient-resource", "timeout", "transaction-connect", "transaction-timeout", "transaction-system", "transaction-rollback"})
	void ordinaryRepositoryFailurePreservesExistingBehavior(String kind) {
		RuntimeException failure = databaseFailure(kind);
		when(users.findById(7L)).thenThrow(failure);
		RuntimeException observed = assertThrows(RuntimeException.class,
			() -> request(filter(false), jwt.createAccessToken(7L, "synthetic@moneytoad.invalid"), "/api/users"));
		assertThat(observed == failure).as("general authentication exception behavior is unchanged").isTrue();
		verify(users).findById(7L);
		verifyNoMoreInteractions(users);
		verifyNoInteractions(store);
	}

	@Test
	void unexpectedDemoRepositoryProgrammingFailureRemainsSanitized500() throws Exception {
		activeSession();
		when(users.findById(7L)).thenThrow(new IllegalStateException(PRIVATE_DETAIL));
		Outcome outcome = request(filter(true), access(), "/api/auth/demo/session");
		assertStopped(outcome, 500, "Internal Server Error");
		assertThat(outcome.response().getContentAsString().contains(PRIVATE_DETAIL)).isFalse();
		verify(store).findActive(anyString());
		verify(users).findById(7L);
		verifyNoMoreInteractions(store, users);
	}

	@Test
	void missingDemoBearerDoesNotLogClientSuppliedPathContent() throws Exception {
		String pathCanary = "synthetic-path-value-must-not-be-logged";
		Logger logger = (Logger) LoggerFactory.getLogger(JwtAuthenticationFilter.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			Outcome outcome = request(filter(true), null, "/api/auth/demo/session/" + pathCanary);
			assertStopped(outcome, 401, "Unauthorized");
			assertThat(appender.list.isEmpty()).as("missing-token path emits only a fixed diagnostic").isFalse();
			assertThat(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains(pathCanary))).isTrue();
			verifyNoInteractions(users, store);
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	private RuntimeException databaseFailure(String kind) {
		return switch (kind) {
			case "connection" -> new DataAccessResourceFailureException(PRIVATE_DETAIL);
			case "transient-resource" -> new TransientDataAccessResourceException(PRIVATE_DETAIL);
			case "timeout" -> new QueryTimeoutException(PRIVATE_DETAIL);
			case "transaction-connect" -> new org.springframework.transaction.CannotCreateTransactionException(PRIVATE_DETAIL);
			case "transaction-timeout" -> new org.springframework.transaction.TransactionTimedOutException(PRIVATE_DETAIL);
			case "transaction-system" -> new org.springframework.transaction.TransactionSystemException(PRIVATE_DETAIL);
			case "transaction-rollback" -> new org.springframework.transaction.UnexpectedRollbackException(PRIVATE_DETAIL);
			default -> throw new IllegalArgumentException("Unknown synthetic failure kind");
		};
	}

	private void activeSession() {
		when(store.findActive(anyString()))
			.thenReturn(Optional.of(new DemoSessionStore.SessionIdentity(7L, now.plusSeconds(3600))));
	}

	private String access() {
		return jwt.createDemoAccessToken(7L, DemoJwtContractTest.SID, now, now.plusSeconds(300));
	}

	private JwtAuthenticationFilter filter(boolean demo) {
		MockEnvironment environment = new MockEnvironment();
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		if (demo) {
			environment.setActiveProfiles("demo");
			beans.addBean("demoSessionGuard", new DemoSessionGuard(jwt, store));
		}
		return new JwtAuthenticationFilter(jwt, users, environment, beans.getBeanProvider(DemoSessionGuard.class));
	}

	private Outcome request(JwtAuthenticationFilter filter, String token, String uri) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
		request.setContextPath("/api");
		if (token != null) request.addHeader("Authorization", "Bearer " + token);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(request, response, chain);
		return new Outcome(response, chain);
	}

	private static void assertStopped(Outcome outcome, int status, String error) throws Exception {
		assertThat(outcome.response().getStatus()).isEqualTo(status);
		assertThat(outcome.chain().getRequest()).isNull();
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		JsonNode body = new ObjectMapper().readTree(outcome.response().getContentAsString());
		assertThat(body.size()).isEqualTo(3);
		assertThat(body.get("status").asInt()).isEqualTo(status);
		assertThat(body.get("error").asText()).isEqualTo(error);
	}

	private record Outcome(MockHttpServletResponse response, MockFilterChain chain) { }
}
