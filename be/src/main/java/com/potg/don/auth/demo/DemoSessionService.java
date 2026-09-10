package com.potg.don.auth.demo;

import static com.potg.don.auth.demo.DemoAuthException.Reason.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.potg.don.auth.jwt.JwtUtil;

import io.jsonwebtoken.JwtException;

@Service
@Profile("demo")
public class DemoSessionService {

	private final JwtUtil jwt;
	private final DemoSessionStore sessions;
	private final Clock clock;
	private final SecureRandom random;

	@Autowired
	public DemoSessionService(JwtUtil jwt, DemoSessionStore sessions) {
		this(jwt, sessions, Clock.systemUTC(), new SecureRandom());
	}

	DemoSessionService(JwtUtil jwt, DemoSessionStore sessions, Clock clock, SecureRandom random) {
		this.jwt = jwt;
		this.sessions = sessions;
		this.clock = clock;
		this.random = random;
	}

	/** Internal only: a future login service supplies its newly persisted synthetic User ID. */
	public IssuedTokens startForUser(long userId) {
		if (userId <= 0) throw new DemoAuthException(INVALID_DEMO_TOKEN);
		Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
		Instant deadline = now.plusSeconds(3600);
		String sid = identifier();
		IssuedTokens tokens = issue(userId, sid, now, deadline);
		if (!sessions.create(sid, userId, hash(tokens.refreshToken()), deadline)) {
			throw new DemoAuthException(DEMO_SESSION_UNAVAILABLE);
		}
		return tokens;
	}

	public IssuedTokens refresh(String refreshToken) {
		Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
		JwtUtil.DemoClaims claims;
		try {
			claims = jwt.validateDemoRefreshToken(jwt.parse(refreshToken), now);
		} catch (JwtException | IllegalArgumentException | NullPointerException invalid) {
			throw new DemoAuthException(INVALID_DEMO_TOKEN);
		}
		IssuedTokens candidates = issue(claims.userId(), claims.sid(), now, claims.expiresAt());
		String previousHash = hash(refreshToken);
		String nextHash = hash(candidates.refreshToken());
		if (previousHash.equals(nextHash)) throw new DemoAuthException(DEMO_SESSION_UNAVAILABLE);
		return switch (sessions.rotate(claims.sid(), claims.userId(), claims.expiresAt(), previousHash, nextHash)) {
			case ROTATED -> candidates;
			case INVALID -> throw new DemoAuthException(DEMO_SESSION_INVALID);
			case REUSED_REVOKED -> throw new DemoAuthException(DEMO_REFRESH_REUSED);
		};
	}

	/** Trusted internal revocation; no HTTP endpoint accepts an arbitrary sid. */
	public void revoke(String sid) { sessions.revoke(sid); }

	private IssuedTokens issue(long userId, String sid, Instant now, Instant deadline) {
		Instant accessExpiry = now.plusSeconds(300).isBefore(deadline) ? now.plusSeconds(300) : deadline;
		String access = jwt.createDemoAccessToken(userId, sid, now, accessExpiry);
		String refresh = jwt.createDemoRefreshToken(userId, sid, identifier(), now, deadline);
		return new IssuedTokens(access, refresh, deadline);
	}

	private String identifier() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 unavailable");
		}
	}

	public record IssuedTokens(String accessToken, String refreshToken, Instant expiresAt) {
		@Override public String toString() { return "IssuedTokens[redacted]"; }
	}
}
