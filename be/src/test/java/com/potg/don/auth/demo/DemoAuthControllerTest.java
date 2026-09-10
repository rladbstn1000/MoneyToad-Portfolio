package com.potg.don.auth.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import jakarta.servlet.http.Cookie;

/** Controller/advice units only; actual filters, interceptor and persistence use separate HTTP integration tests. */
class DemoAuthControllerTest {

	private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
	private DemoAuthService auth;
	private DemoAuthController controller;
	private DemoAuthExceptionHandler advice;
	private DemoSessionService.IssuedTokens issued;
	private JsonMapper json;

	@BeforeEach
	void setUp() {
		auth = mock(DemoAuthService.class);
		DemoRefreshCookie cookies = new DemoRefreshCookie(
			new DemoAuthHttpConfiguration.Settings("https://demo.example.invalid", true), Clock.fixed(NOW, ZoneOffset.UTC));
		controller = new DemoAuthController(auth, cookies);
		advice = new DemoAuthExceptionHandler(cookies);
		issued = new DemoSessionService.IssuedTokens("synthetic-access", "synthetic-refresh", NOW.plusSeconds(3600));
		json = JsonMapper.builder().addModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
	}

	@Test
	void successfulLoginSerializesOnlyAccessTokenAndUsesTheRefreshCookie() throws Exception {
		when(auth.login(null)).thenReturn(issued);
		ResponseEntity<DemoAuthController.AccessTokenResponse> response = controller.login(request("/login"));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertTokensSeparated(response);
		verify(auth).login(null);
		verifyNoMoreInteractions(auth);
	}

	@Test
	void reissueUsesOnlyTheDedicatedCookieAndReturnsTheSameRestrictedShape() throws Exception {
		MockHttpServletRequest request = request("/reissue");
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer ignored-synthetic-value");
		request.setCookies(new Cookie("refreshToken", "ignored-ordinary-cookie"),
			new Cookie(DemoRefreshCookie.NAME, "synthetic-old-refresh"));
		when(auth.reissue("synthetic-old-refresh")).thenReturn(issued);
		ResponseEntity<DemoAuthController.AccessTokenResponse> response = controller.reissue(request);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertTokensSeparated(response);
		verify(auth).reissue("synthetic-old-refresh");
		verifyNoMoreInteractions(auth);
	}

	@Test
	void duplicateDemoCookiesAreRejectedBeforeLoginOrReissue() {
		for (String suffix : List.of("/login", "/reissue")) {
			MockHttpServletRequest request = request(suffix);
			request.setCookies(new Cookie(DemoRefreshCookie.NAME, "first-synthetic"),
				new Cookie(DemoRefreshCookie.NAME, "second-synthetic"));
			assertThrows(DemoAuthException.class,
				() -> { if (suffix.equals("/login")) controller.login(request); else controller.reissue(request); });
		}
		verifyNoInteractions(auth);
	}

	@Test
	void missingReissueCookieAndBlankLoginCookieAreRejectedWithoutCallingService() {
		assertThrows(DemoAuthException.class, () -> controller.reissue(request("/reissue")));
		MockHttpServletRequest blank = request("/login");
		blank.setCookies(new Cookie(DemoRefreshCookie.NAME, ""));
		assertThrows(DemoAuthException.class, () -> controller.login(blank));
		verifyNoInteractions(auth);
	}

	@Test
	void sessionOnlyExposesDemoAndAbsoluteExpiryWithoutCallingRefresh() throws Exception {
		ResponseEntity<DemoAuthController.SessionResponse> response = controller.session(authentication());
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		JsonNode body = json.valueToTree(response.getBody());
		assertThat(fields(body)).containsExactlyInAnyOrder("demo", "expiresAt");
		assertThat(body.get("demo").asBoolean()).isTrue();
		assertThat(body.get("expiresAt").asText()).isEqualTo(NOW.plusSeconds(3600).toString());
		assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
		assertThat(response.getHeaders().containsKey(HttpHeaders.SET_COOKIE)).isFalse();
		verifyNoInteractions(auth);
	}

	@Test
	void logoutUsesAuthenticationDetailsOnlyAndDeletesTheCookie() {
		Authentication authentication = authentication();
		ResponseEntity<Void> response = controller.logout(authentication);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(response.getBody()).isNull();
		assertDeletion(response);
		verify(auth).logout((DemoSessionGuard.AuthorizedSession) authentication.getDetails());
		verifyNoMoreInteractions(auth);
	}

	@Test
	void missingUnauthenticatedOrForeignDetailsNeverReachLogoutOrSession() {
		List<Authentication> invalid = new ArrayList<>();
		invalid.add(null);
		invalid.add(UsernamePasswordAuthenticationToken.unauthenticated("synthetic-principal", null));
		invalid.add(UsernamePasswordAuthenticationToken.authenticated("synthetic-principal", null, List.of()));
		for (Authentication authentication : invalid) {
			assertThrows(DemoAuthException.class, () -> controller.session(authentication));
			assertThrows(DemoAuthException.class, () -> controller.logout(authentication));
		}
		verifyNoInteractions(auth);
	}

	@Test
	void controllerDoesNotReadOrBindTheRequestBodyAfterTheInterceptor() {
		for (var method : DemoAuthController.class.getDeclaredMethods()) {
			for (var parameter : method.getParameters()) {
				assertThat(parameter.isAnnotationPresent(RequestBody.class)).isFalse();
				assertThat(parameter.getType() == java.io.InputStream.class || parameter.getType() == java.io.Reader.class).isFalse();
			}
		}
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/demo/login") {
			@Override public jakarta.servlet.ServletInputStream getInputStream() { throw new AssertionError("Unexpected second body read"); }
			@Override public java.io.BufferedReader getReader() { throw new AssertionError("Unexpected second body read"); }
		};
		request.setContextPath("/api");
		when(auth.login(null)).thenReturn(issued);
		assertThat(controller.login(request).getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	@Test
	void authenticationErrorsDeleteCookiesOnlyForLoginAndReissue() {
		for (String suffix : List.of("/login", "/reissue")) {
			for (DemoAuthException.Reason reason : List.of(DemoAuthException.Reason.INVALID_DEMO_TOKEN,
				DemoAuthException.Reason.DEMO_SESSION_INVALID, DemoAuthException.Reason.DEMO_REFRESH_REUSED)) {
				ResponseEntity<Map<String, Object>> response = advice.auth(new DemoAuthException(reason), request(suffix));
				assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
				assertDeletion(response);
				assertErrorShape(response);
			}
		}
		for (String suffix : List.of("/session", "/logout")) {
			assertThat(advice.auth(new DemoAuthException(DemoAuthException.Reason.DEMO_SESSION_INVALID), request(suffix))
				.getHeaders().containsKey(HttpHeaders.SET_COOKIE)).isFalse();
		}
	}

	@Test
	void unavailableErrorsPreserveCookieAndNeverEchoTheExceptionDetails() {
		ResponseEntity<Map<String, Object>> redis = advice.auth(
			new DemoAuthException(DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE), request("/reissue"));
		assertUnavailable(redis);
		for (Exception failure : List.of(new DataAccessResourceFailureException("private-synthetic-detail"),
			new TransactionSystemException("private-synthetic-detail"))) {
			ResponseEntity<Map<String, Object>> db = advice.unavailable(failure, request("/login"));
			assertUnavailable(db);
			assertThat(db.getBody().toString().contains("private-synthetic-detail")).isFalse();
		}
	}

	@Test
	void requestErrorsUseFixedMessagesAndDoNotReplaceCookies() {
		for (HttpStatus status : List.of(HttpStatus.BAD_REQUEST, HttpStatus.FORBIDDEN, HttpStatus.CONFLICT,
			HttpStatus.PAYLOAD_TOO_LARGE, HttpStatus.UNSUPPORTED_MEDIA_TYPE)) {
			ResponseEntity<Map<String, Object>> response = advice.request(
				new ResponseStatusException(status, "private-synthetic-detail"), request("/login"));
			assertThat(response.getStatusCode()).isEqualTo(status);
			assertThat(response.getBody().toString().contains("private-synthetic-detail")).isFalse();
			assertThat(response.getHeaders().containsKey(HttpHeaders.SET_COOKIE)).isFalse();
			assertErrorShape(response);
		}
	}

	@Test
	void uniqueCollisionAndUnexpectedImplementationErrorsStaySanitized500() {
		for (Exception failure : List.of(new DataIntegrityViolationException("private-synthetic-detail"),
			new IllegalStateException("private-synthetic-detail"))) {
			ResponseEntity<Map<String, Object>> response = advice.unexpected(failure, request("/login"));
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
			assertThat(response.getBody().toString().contains("private-synthetic-detail")).isFalse();
			assertThat(response.getHeaders().containsKey(HttpHeaders.SET_COOKIE)).isFalse();
			assertErrorShape(response);
		}
	}

	private void assertTokensSeparated(ResponseEntity<DemoAuthController.AccessTokenResponse> response) throws Exception {
		JsonNode body = json.valueToTree(response.getBody());
		assertThat(fields(body)).containsExactly("accessToken");
		assertThat(issued.accessToken().equals(body.get("accessToken").asText())).isTrue();
		assertThat(json.writeValueAsString(body).contains(issued.refreshToken())).isFalse();
		assertThat(response.getBody().toString().contains(issued.accessToken())).isFalse();
		String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
		assertThat(cookie != null && cookie.startsWith("demoRefreshToken=" + issued.refreshToken() + ";")).isTrue();
		assertThat(cookie != null && cookie.contains("HttpOnly") && cookie.contains("Secure") && cookie.contains("SameSite=Lax")).isTrue();
		assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
	}

	private static void assertUnavailable(ResponseEntity<Map<String, Object>> response) {
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getHeaders().containsKey(HttpHeaders.SET_COOKIE)).isFalse();
		assertErrorShape(response);
	}

	private static void assertErrorShape(ResponseEntity<Map<String, Object>> response) {
		assertThat(response.getBody().keySet()).containsExactlyInAnyOrder("status", "error", "message");
		assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
	}

	private static void assertDeletion(ResponseEntity<?> response) {
		String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
		assertThat(cookie != null && cookie.startsWith("demoRefreshToken=;")).isTrue();
		assertThat(cookie != null && cookie.contains("Max-Age=0") && cookie.contains("Path=/api/auth/demo")
			&& cookie.contains("Secure") && cookie.contains("HttpOnly") && cookie.contains("SameSite=Lax")
			&& !cookie.contains("Domain=")).isTrue();
	}

	private static List<String> fields(JsonNode body) {
		List<String> fields = new ArrayList<>();
		body.fieldNames().forEachRemaining(fields::add);
		return fields;
	}

	private static Authentication authentication() {
		UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated("synthetic-principal", null, List.of());
		authentication.setDetails(new DemoSessionGuard.AuthorizedSession(7L, DemoJwtContractTest.SID, NOW.plusSeconds(3600)));
		return authentication;
	}

	private static MockHttpServletRequest request(String suffix) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/demo" + suffix);
		request.setContextPath("/api");
		return request;
	}
}
