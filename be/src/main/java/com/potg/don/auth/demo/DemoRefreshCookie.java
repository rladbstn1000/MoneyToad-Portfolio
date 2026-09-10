package com.potg.don.auth.demo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
public class DemoRefreshCookie {

	public static final String NAME = "demoRefreshToken";
	private static final String PATH = "/api/auth/demo";
	private final boolean secure;
	private final Clock clock;

	@Autowired
	public DemoRefreshCookie(DemoAuthHttpConfiguration.Settings settings) {
		this(settings, Clock.systemUTC());
	}

	DemoRefreshCookie(DemoAuthHttpConfiguration.Settings settings, Clock clock) {
		this.secure = settings.secure();
		this.clock = clock;
	}

	public ResponseCookie create(String refreshToken, Instant absoluteExpiresAt) {
		long remaining = Duration.between(clock.instant(), absoluteExpiresAt).getSeconds();
		if (remaining < 1) throw new DemoAuthException(DemoAuthException.Reason.DEMO_SESSION_INVALID);
		return builder(refreshToken).maxAge(Math.min(remaining, 3600)).build();
	}

	public ResponseCookie delete() {
		return builder("").maxAge(0).build();
	}

	private ResponseCookie.ResponseCookieBuilder builder(String value) {
		return ResponseCookie.from(NAME, value).httpOnly(true).secure(secure).sameSite("Lax").path(PATH);
	}
}
