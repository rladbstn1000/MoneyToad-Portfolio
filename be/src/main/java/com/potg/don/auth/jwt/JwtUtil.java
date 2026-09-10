package com.potg.don.auth.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Base64;
import java.util.Objects;

import com.potg.don.auth.demo.DemoAuthException;
import static com.potg.don.auth.demo.DemoAuthException.Reason.INVALID_DEMO_TOKEN;
import java.util.Map;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtUtil {

	private final SecretKey key; // ✅ SecretKey로 변경

	public JwtUtil(@Value("${app.jwt.secret}") String secret) {
		this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)); // ✅ UTF-8
	}

	@Value("${app.jwt.issuer}")
	private String issuer;

	@Value("${app.jwt.access-token-validity-seconds}")
	private long accessValiditySec;

	@Value("${app.jwt.refresh-token-validity-seconds}")
	private long refreshValiditySec;

	public String createAccessToken(Long userId, String email) {
		Instant now = Instant.now();
		return Jwts.builder()
			.issuer(issuer)
			.subject(String.valueOf(userId))
			.issuedAt(Date.from(now))
			.expiration(Date.from(now.plusSeconds(accessValiditySec)))
			.claims(Map.of("email", email, "typ", "ACCESS"))
			.signWith(key, Jwts.SIG.HS256) // ✅ 0.12.x 시그니처
			.compact();
	}

	public String createRefreshToken(Long userId) {
		Instant now = Instant.now();
		return Jwts.builder()
			.issuer(issuer)
			.subject(String.valueOf(userId))
			.issuedAt(Date.from(now))
			.expiration(Date.from(now.plusSeconds(refreshValiditySec)))
			.claims(Map.of("typ", "REFRESH"))
			.signWith(key, Jwts.SIG.HS256)
			.compact();
	}

	public Jws<Claims> parse(String token) {
		return Jwts.parser()                 // ✅ 0.12.x 파서
			.verifyWith(key)             // ✅ SecretKey 전달
			.build()
			.parseSignedClaims(token);
	}

	public Long getUserId(String token) {
		return Long.valueOf(parse(token).getPayload().getSubject());
	}

	public boolean isExpired(String token) {
		return parse(token).getPayload().getExpiration().before(new Date());
	}

	public long getRefreshTtlSeconds() {
		return refreshValiditySec;
	}

	/** Separate token kinds; original OAuth token methods above retain their contract. */
	public String createDemoAccessToken(long userId, String sid, Instant issuedAt, Instant expiresAt) {
		return createDemoToken(userId, sid, null, issuedAt, expiresAt, "DEMO_ACCESS", 300);
	}

	public String createDemoRefreshToken(long userId, String sid, String jti, Instant issuedAt, Instant expiresAt) {
		return createDemoToken(userId, sid, jti, issuedAt, expiresAt, "DEMO_REFRESH", 3600);
	}

	private String createDemoToken(long userId, String sid, String jti, Instant issuedAt, Instant expiresAt,
		String type, long maximumSeconds) {
		if (userId <= 0 || issuer == null || issuer.isBlank() || !isDemoIdentifier(sid)
			|| ("DEMO_REFRESH".equals(type) && !isDemoIdentifier(jti))
			|| !validDemoTimes(issuedAt, expiresAt, issuedAt, maximumSeconds)) {
			throw new DemoAuthException(INVALID_DEMO_TOKEN);
		}
		var builder = Jwts.builder().issuer(issuer).subject(Long.toString(userId))
			.issuedAt(Date.from(issuedAt)).expiration(Date.from(expiresAt)).claim("typ", type).claim("sid", sid);
		if (jti != null) builder.id(jti);
		return builder.signWith(key, Jwts.SIG.HS256).compact();
	}

	public DemoClaims validateDemoAccessToken(Jws<Claims> verified, Instant now) {
		return validateDemoToken(verified, now, "DEMO_ACCESS", 300);
	}

	public DemoClaims validateDemoRefreshToken(Jws<Claims> verified, Instant now) {
		return validateDemoToken(verified, now, "DEMO_REFRESH", 3600);
	}

	// Caller must supply the signed result of parse(), never untrusted decoded claims.
	private DemoClaims validateDemoToken(Jws<Claims> verified, Instant now, String type, long maximumSeconds) {
		try {
			Claims claims = verified.getPayload();
			String subject = claims.getSubject();
			long userId = Long.parseLong(subject);
			String sid = claims.get("sid", String.class);
			Instant issuedAt = claims.getIssuedAt().toInstant();
			Instant expiresAt = claims.getExpiration().toInstant();
			if (!"HS256".equals(verified.getHeader().getAlgorithm()) || issuer == null || issuer.isBlank()
				|| !Objects.equals(issuer, claims.getIssuer()) || !type.equals(claims.get("typ", String.class))
				|| userId <= 0 || !Long.toString(userId).equals(subject) || !isDemoIdentifier(sid)
				|| ("DEMO_REFRESH".equals(type) && !isDemoIdentifier(claims.getId()))
				|| ("DEMO_ACCESS".equals(type) && claims.containsKey("jti"))
				|| claims.containsKey("email") || claims.containsKey("name")
				|| !validDemoTimes(issuedAt, expiresAt, now, maximumSeconds)) {
				throw new DemoAuthException(INVALID_DEMO_TOKEN);
			}
			return new DemoClaims(userId, sid, issuedAt, expiresAt);
		} catch (io.jsonwebtoken.JwtException | IllegalArgumentException | NullPointerException failure) {
			throw new DemoAuthException(INVALID_DEMO_TOKEN);
		}
	}

	private static boolean validDemoTimes(Instant issuedAt, Instant expiresAt, Instant now, long maximumSeconds) {
		return issuedAt != null && expiresAt != null && now != null
			&& issuedAt.getEpochSecond() >= 0 && issuedAt.getNano() == 0 && expiresAt.getNano() == 0
			&& !issuedAt.isAfter(now) && expiresAt.isAfter(now) && expiresAt.isAfter(issuedAt)
			&& expiresAt.getEpochSecond() - issuedAt.getEpochSecond() <= maximumSeconds;
	}

	public static boolean isDemoIdentifier(String value) {
		if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) return false;
		try {
			byte[] decoded = Base64.getUrlDecoder().decode(value);
			return decoded.length == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value);
		} catch (IllegalArgumentException invalid) {
			return false;
		}
	}

	public record DemoClaims(long userId, String sid, Instant issuedAt, Instant expiresAt) { }
}
