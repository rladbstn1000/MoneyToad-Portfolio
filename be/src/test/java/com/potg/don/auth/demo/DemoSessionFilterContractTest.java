package com.potg.don.auth.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potg.don.auth.entity.CustomUserDetails;
import com.potg.don.auth.jwt.JwtAuthenticationFilter;
import com.potg.don.auth.jwt.JwtUtil;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;

/** Actual JWT, guard and filter; only repository/store boundaries are strict unit doubles. */
class DemoSessionFilterContractTest {

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
	void clearOnlyTestSecurityContext() { SecurityContextHolder.clearContext(); }

	@ParameterizedTest(name = "missing guard fails for {0} demo profile")
	@ValueSource(strings = {"active", "default"})
	void demoModeCannotStartWithoutItsSessionGuard(String profileSelection) {
		MockEnvironment environment = new MockEnvironment();
		if (profileSelection.equals("active")) environment.setActiveProfiles("demo");
		else environment.setDefaultProfiles("demo");
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		assertThrows(NoSuchBeanDefinitionException.class,
			() -> new JwtAuthenticationFilter(jwt, users, environment, beans.getBeanProvider(DemoSessionGuard.class)));
		verifyNoInteractions(users, store);
	}

	@Test
	void ordinaryModeStillAuthenticatesItsAccessTokenWithoutAGuard() throws Exception {
		User user = syntheticUser();
		when(users.findById(7L)).thenReturn(Optional.of(user));
		Outcome result = request(filter(false), jwt.createAccessToken(7L, user.getEmail()));
		assertThat(result.response().getStatus()).isEqualTo(200);
		assertThat(result.chain().getRequest()).isNotNull();
		assertPrincipalIsSyntheticUser();
		verify(users).findById(7L);
		verifyNoMoreInteractions(users);
		verifyNoInteractions(store);
	}

	@Test
	void activeDemoSessionReachesTheSamePrincipalConstruction() throws Exception {
		when(store.findActive(DemoJwtContractTest.SID))
			.thenReturn(Optional.of(new DemoSessionStore.SessionIdentity(7L, now.plusSeconds(3600))));
		when(users.findById(7L)).thenReturn(Optional.of(syntheticUser()));
		Outcome result = request(filter(true), access());
		assertThat(result.response().getStatus()).isEqualTo(200);
		assertThat(result.chain().getRequest()).isNotNull();
		assertPrincipalIsSyntheticUser();
		verify(store).findActive(DemoJwtContractTest.SID);
		verify(users).findById(7L);
		verifyNoMoreInteractions(users, store);
	}

	@Test
	void malformedDemoJwtHasFixedUnauthorizedResponseWithoutAnyStoreOrUserLookup() throws Exception {
		assertStopped(request(filter(true), "not.a.jwt"), 401, "Unauthorized", "유효하지 않은 인증 정보입니다.");
		verifyNoInteractions(users, store);
	}

	@Test
	void missingSessionRejectsBeforeUserLookup() throws Exception {
		when(store.findActive(DemoJwtContractTest.SID)).thenReturn(Optional.empty());
		assertStopped(request(filter(true), access()), 401, "Unauthorized", "유효하지 않은 인증 정보입니다.");
		verify(store).findActive(DemoJwtContractTest.SID);
		verifyNoMoreInteractions(store);
		verifyNoInteractions(users);
	}

	@Test
	void storeUnavailableBecomes503AndStopsBeforeUserLookup() throws Exception {
		when(store.findActive(DemoJwtContractTest.SID))
			.thenThrow(new DemoAuthException(DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE));
		assertStopped(request(filter(true), access()), 503, "Service Unavailable", "인증 서비스를 사용할 수 없습니다.");
		verify(store).findActive(DemoJwtContractTest.SID);
		verifyNoMoreInteractions(store);
		verifyNoInteractions(users);
	}

	@ParameterizedTest(name = "unexpected {0} remains a generic 500")
	@ValueSource(strings = {"NullPointerException", "IllegalArgumentException", "IllegalStateException"})
	void unexpectedGuardFailuresAreServerErrorsRatherThanInvalidCredentials(String kind) throws Exception {
		String canary = "synthetic internal details must not be exposed";
		RuntimeException defect = switch (kind) {
			case "NullPointerException" -> new NullPointerException(canary);
			case "IllegalArgumentException" -> new IllegalArgumentException(canary);
			default -> new IllegalStateException(canary);
		};
		when(store.findActive(DemoJwtContractTest.SID)).thenThrow(defect);
		Outcome result = request(filter(true), access());
		assertStopped(result, 500, "Internal Server Error", "서버 내부 오류가 발생했습니다.");
		assertThat(result.response().getContentAsString()).doesNotContain(canary);
		verify(store).findActive(DemoJwtContractTest.SID);
		verifyNoMoreInteractions(store);
		verifyNoInteractions(users);
	}

	@ParameterizedTest(name = "{0} cannot authenticate a demo business request")
	@ValueSource(strings = {"ordinaryAccess", "ordinaryRefresh", "demoRefresh"})
	void wrongTokenPurposeNeverReachesRedis(String kind) throws Exception {
		String token = switch (kind) {
			case "ordinaryAccess" -> jwt.createAccessToken(7L, "synthetic@example.invalid");
			case "ordinaryRefresh" -> jwt.createRefreshToken(7L);
			default -> jwt.createDemoRefreshToken(7L, DemoJwtContractTest.SID, DemoJwtContractTest.JTI,
				now, now.plusSeconds(3600));
		};
		assertStopped(request(filter(true), token), 401, "Unauthorized", "유효하지 않은 인증 정보입니다.");
		verifyNoInteractions(users, store);
	}

	@Test
	void noBearerTokenKeepsExisting401WithoutLookingUpAnySession() throws Exception {
		assertStopped(request(filter(true), null), 401, "Unauthorized", "인증 토큰이 필요합니다.");
		verifyNoInteractions(users, store);
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

	private String access() {
		return jwt.createDemoAccessToken(7L, DemoJwtContractTest.SID, now, now.plusSeconds(300));
	}

	private User syntheticUser() {
		User user = User.createUser("synthetic@example.invalid", "Synthetic Unit User");
		ReflectionTestUtils.setField(user, "id", 7L);
		return user;
	}

	private void assertPrincipalIsSyntheticUser() {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		assertThat(authentication).isNotNull();
		assertThat(authentication.isAuthenticated()).isTrue();
		assertThat(authentication.getPrincipal()).isInstanceOf(CustomUserDetails.class);
		CustomUserDetails principal = (CustomUserDetails) authentication.getPrincipal();
		assertThat(principal.getUserId()).isEqualTo(7L);
		assertThat(principal.getUsername()).isEqualTo("synthetic@example.invalid");
	}

	private Outcome request(JwtAuthenticationFilter filter, String token) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
		if (token != null) request.addHeader("Authorization", "Bearer " + token);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(request, response, chain);
		return new Outcome(response, chain);
	}

	private void assertStopped(Outcome result, int status, String error, String message) throws Exception {
		assertThat(result.response().getStatus()).isEqualTo(status);
		assertThat(result.chain().getRequest()).isNull();
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		JsonNode body = new ObjectMapper().readTree(result.response().getContentAsString());
		assertThat(body.size()).isEqualTo(3);
		assertThat(body.path("status").asInt()).isEqualTo(status);
		assertThat(body.path("error").asText()).isEqualTo(error);
		assertThat(body.path("message").asText()).isEqualTo(message);
	}

	private record Outcome(MockHttpServletResponse response, MockFilterChain chain) { }
}
