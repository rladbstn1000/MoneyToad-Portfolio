package com.potg.don.auth.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
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
}
