package com.potg.don.auth.demo;

import static com.potg.don.auth.demo.DemoAuthException.Reason.DEMO_SESSION_INVALID;

import java.time.Instant;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.potg.don.auth.jwt.JwtUtil;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import lombok.RequiredArgsConstructor;

/** Called by the existing JWT filter before any User lookup or authentication. */
@Component
@Profile("demo")
@RequiredArgsConstructor
public class DemoSessionGuard {

	private final JwtUtil jwt;
	private final DemoSessionStore sessions;

	public AuthorizedSession requireActive(Jws<Claims> verified) {
		var claims = jwt.validateDemoAccessToken(verified, Instant.now());
		var identity = sessions.findActive(claims.sid()).orElseThrow(() -> new DemoAuthException(DEMO_SESSION_INVALID));
		if (identity.userId() != claims.userId() || claims.expiresAt().isAfter(identity.expiresAt())) {
			throw new DemoAuthException(DEMO_SESSION_INVALID);
		}
		return new AuthorizedSession(claims.userId(), claims.sid(), identity.expiresAt());
	}

	public record AuthorizedSession(long userId, String sid, Instant expiresAt) {
		@Override public String toString() { return "AuthorizedSession[redacted]"; }
	}
}
