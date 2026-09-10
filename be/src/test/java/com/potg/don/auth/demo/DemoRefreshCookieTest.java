package com.potg.don.auth.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

class DemoRefreshCookieTest {

	private static final Instant NOW = Instant.parse("2030-01-01T00:00:00.250Z");

	@Test
	void publicCookieUsesTheExactSecureHttpOnlyHostOnlyScope() {
		ResponseCookie cookie = writer(true, NOW).create("synthetic-cookie-value", NOW.plusSeconds(3599));
		assertScope(cookie, true);
		assertThat(cookie.getMaxAge().getSeconds()).isEqualTo(3599);
	}

	@Test
	void explicitlyValidatedLocalSettingsAreTheOnlyInsecureCookieMode() {
		ResponseCookie cookie = writer(false, NOW).create("synthetic-cookie-value", NOW.plusSeconds(300));
		assertScope(cookie, false);
	}

	@Test
	void maxAgeFloorsTheRemainingAbsoluteLifetime() {
		ResponseCookie cookie = writer(true, NOW).create("synthetic-cookie-value", NOW.plusSeconds(15).plusMillis(999));
		assertThat(cookie.getMaxAge().getSeconds()).isEqualTo(15);
	}

	@Test
	void reissuedCookieDoesNotResetTheOriginalSessionLifetime() {
		Instant absolute = NOW.plusSeconds(3600);
		ResponseCookie first = writer(true, NOW).create("synthetic-first", absolute);
		ResponseCookie later = writer(true, NOW.plusSeconds(317)).create("synthetic-next", absolute);
		assertThat(first.getMaxAge().getSeconds()).isEqualTo(3600);
		assertThat(later.getMaxAge().getSeconds()).isEqualTo(3283);
		assertThat(later.getMaxAge()).isLessThan(first.getMaxAge());
	}

	@Test
	void defensiveCookieCeilingNeverExceedsOneHour() {
		ResponseCookie cookie = writer(true, NOW).create("synthetic-cookie-value", NOW.plusSeconds(4000));
		assertThat(cookie.getMaxAge().getSeconds()).isEqualTo(3600);
	}

	@Test
	void expirationAndSubsecondRemainderRefuseToIssueACookie() {
		for (Instant expiresAt : new Instant[] {NOW.minusSeconds(1), NOW, NOW.plusMillis(999)}) {
			DemoAuthException failure = assertThrows(DemoAuthException.class,
				() -> writer(true, NOW).create("synthetic-cookie-value", expiresAt));
			assertThat(failure.reason()).isEqualTo(DemoAuthException.Reason.DEMO_SESSION_INVALID);
		}
	}

	@Test
	void oneWholeSecondRemainingIsRepresentedWithoutExtension() {
		assertThat(writer(true, NOW).create("synthetic-cookie-value", NOW.plusSeconds(1)).getMaxAge().getSeconds())
			.isEqualTo(1);
	}

	@Test
	void deletionUsesTheSamePublicAndLocalScopesWithZeroLifetime() {
		for (boolean secure : new boolean[] {true, false}) {
			ResponseCookie deleted = writer(secure, NOW).delete();
			assertScope(deleted, secure);
			assertThat(deleted.getValue()).isEmpty();
			assertThat(deleted.getMaxAge().getSeconds()).isZero();
		}
	}

	private static DemoRefreshCookie writer(boolean secure, Instant now) {
		return new DemoRefreshCookie(new DemoAuthHttpConfiguration.Settings(
			secure ? "https://demo.example.invalid" : "http://127.0.0.1:4173", secure), Clock.fixed(now, ZoneOffset.UTC));
	}

	private static void assertScope(ResponseCookie cookie, boolean secure) {
		assertThat(cookie.getName()).isEqualTo("demoRefreshToken");
		assertThat(cookie.isHttpOnly()).isTrue();
		assertThat(cookie.isSecure()).isEqualTo(secure);
		assertThat(cookie.getSameSite()).isEqualTo("Lax");
		assertThat(cookie.getPath()).isEqualTo("/api/auth/demo");
		assertThat(cookie.getDomain()).isNull();
		assertThat(cookie.toString().contains("Domain=")).isFalse();
	}
}
