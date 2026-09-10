package com.potg.don.auth.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potg.don.auth.jwt.JwtUtil;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/** Pure JWT tests: synthetic keys and values only; no application context or network. */
class DemoJwtContractTest {

	static final String SECRET = "demo-contract-test-only-64-byte-signing-key-0123456789-ABCDEFGHIJKLMNO";
	static final String ISSUER = "demo-contract-unit";
	static final String SID = identifier((byte) 1);
	static final String JTI = identifier((byte) 2);
	private final SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
	private JwtUtil jwt;
	private Instant now;

	@BeforeEach
	void prepareSyntheticSigner() {
		jwt = configuredJwt();
		now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
	}

	static JwtUtil configuredJwt() {
		JwtUtil util = new JwtUtil(SECRET);
		ReflectionTestUtils.setField(util, "issuer", ISSUER);
		ReflectionTestUtils.setField(util, "accessValiditySec", 900L);
		ReflectionTestUtils.setField(util, "refreshValiditySec", 7200L);
		return util;
	}

	static String identifier(byte value) {
		byte[] bytes = new byte[32];
		java.util.Arrays.fill(bytes, value);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	@Test
	void issuedDemoTokensHaveOnlyTheRequiredIdentityAndTimeClaims() {
		String access = jwt.createDemoAccessToken(7L, SID, now, now.plusSeconds(300));
		String refresh = jwt.createDemoRefreshToken(7L, SID, JTI, now, now.plusSeconds(3600));
		Jws<Claims> at = jwt.parse(access);
		Jws<Claims> rt = jwt.parse(refresh);
		assertThat(at.getHeader().getAlgorithm()).isEqualTo("HS256");
		assertThat(rt.getHeader().getAlgorithm()).isEqualTo("HS256");
		assertThat(at.getPayload().keySet()).containsExactlyInAnyOrder("iss", "sub", "iat", "exp", "typ", "sid");
		assertThat(rt.getPayload().keySet()).containsExactlyInAnyOrder("iss", "sub", "iat", "exp", "typ", "sid", "jti");
		assertThat(at.getPayload().get("typ")).isEqualTo("DEMO_ACCESS");
		assertThat(rt.getPayload().get("typ")).isEqualTo("DEMO_REFRESH");
		assertThat(jwt.validateDemoAccessToken(at, now)).isEqualTo(new JwtUtil.DemoClaims(7L, SID, now, now.plusSeconds(300)));
		assertThat(jwt.validateDemoRefreshToken(rt, now)).isEqualTo(new JwtUtil.DemoClaims(7L, SID, now, now.plusSeconds(3600)));
	}

	@Test
	void accessAndRefreshTypesCannotBeInterchanged() {
		Jws<Claims> at = jwt.parse(jwt.createDemoAccessToken(7L, SID, now, now.plusSeconds(300)));
		Jws<Claims> rt = jwt.parse(jwt.createDemoRefreshToken(7L, SID, JTI, now, now.plusSeconds(3600)));
		assertAll(() -> invalid(() -> jwt.validateDemoAccessToken(rt, now)),
			() -> invalid(() -> jwt.validateDemoRefreshToken(at, now)));
	}

	@Test
	void ordinaryAccessAndRefreshTokensAreNotDemoCredentials() {
		Jws<Claims> at = jwt.parse(jwt.createAccessToken(7L, "synthetic@example.invalid"));
		Jws<Claims> rt = jwt.parse(jwt.createRefreshToken(7L));
		assertAll(() -> invalid(() -> jwt.validateDemoAccessToken(at, now)),
			() -> invalid(() -> jwt.validateDemoRefreshToken(rt, now)),
			() -> invalid(() -> jwt.validateDemoAccessToken(rt, now)),
			() -> invalid(() -> jwt.validateDemoRefreshToken(at, now)));
	}

	@Test
	void malformedAccessClaimsAreRejectedAfterSignatureVerification() {
		Map<String, Consumer<Map<String, Object>>> cases = new LinkedHashMap<>();
		cases.put("missing issuer", m -> m.remove("iss"));
		cases.put("wrong issuer", m -> m.put("iss", "other-test-issuer"));
		cases.put("missing type", m -> m.remove("typ"));
		cases.put("missing sid", m -> m.remove("sid"));
		cases.put("non-string sid", m -> m.put("sid", 7));
		cases.put("short sid", m -> m.put("sid", "abc"));
		cases.put("padded sid", m -> m.put("sid", SID + "="));
		cases.put("noncanonical sid", m -> m.put("sid", "A".repeat(42) + "B"));
		cases.put("missing subject", m -> m.remove("sub"));
		cases.put("zero subject", m -> m.put("sub", "0"));
		cases.put("negative subject", m -> m.put("sub", "-1"));
		cases.put("noncanonical subject", m -> m.put("sub", "07"));
		cases.put("subject overflow", m -> m.put("sub", "9223372036854775808"));
		cases.put("missing issued-at", m -> m.remove("iat"));
		cases.put("future issued-at", m -> m.put("iat", now.plusSeconds(10).getEpochSecond()));
		cases.put("missing expiry", m -> m.remove("exp"));
		cases.put("expiry before issued-at", m -> {
			m.put("iat", now.plusSeconds(30).getEpochSecond());
			m.put("exp", now.plusSeconds(20).getEpochSecond());
		});
		cases.put("overlong access lifetime", m -> m.put("exp", now.plusSeconds(301).getEpochSecond()));
		cases.put("email leaked into demo claims", m -> m.put("email", "synthetic@example.invalid"));
		cases.put("name leaked into demo claims", m -> m.put("name", "Synthetic"));
		List<Executable> checks = new ArrayList<>();
		cases.forEach((name, mutation) -> checks.add(() -> {
			Map<String, Object> claims = claims(false);
			mutation.accept(claims);
			Jws<Claims> verified = jwt.parse(sign(claims));
			DemoAuthException failure = assertThrows(DemoAuthException.class,
				() -> jwt.validateDemoAccessToken(verified, now), name);
			assertThat(failure.reason()).as(name).isEqualTo(DemoAuthException.Reason.INVALID_DEMO_TOKEN);
		}));
		assertAll(checks);
	}

	@Test
	void refreshRequiresCanonicalRandomIdentifierAndBoundedLifetime() {
		List<Consumer<Map<String, Object>>> cases = List.of(m -> m.remove("jti"), m -> m.put("jti", 5),
			m -> m.put("jti", "abc"), m -> m.put("jti", JTI + "="),
			m -> m.put("jti", "A".repeat(42) + "B"),
			m -> m.put("exp", now.plusSeconds(3601).getEpochSecond()));
		List<Executable> checks = new ArrayList<>();
		for (Consumer<Map<String, Object>> mutation : cases) checks.add(() -> {
			Map<String, Object> claims = claims(true);
			mutation.accept(claims);
			if (claims.get("jti") instanceof Number) {
				// The normal JJWT builder rejects a non-string registered claim before
				// it reaches the product. Sign raw JSON to exercise the real parser.
				String malformed = Jwts.builder().content(new ObjectMapper().writeValueAsBytes(claims))
					.signWith(key, Jwts.SIG.HS256).compact();
				RuntimeException failure = assertThrows(RuntimeException.class,
					() -> jwt.validateDemoRefreshToken(jwt.parse(malformed), now));
				assertThat(failure).isInstanceOfAny(JwtException.class, DemoAuthException.class);
				if (failure instanceof DemoAuthException demoFailure) {
					assertThat(demoFailure.reason()).isEqualTo(DemoAuthException.Reason.INVALID_DEMO_TOKEN);
				}
			} else {
				Jws<Claims> verified = jwt.parse(sign(claims));
				invalid(() -> jwt.validateDemoRefreshToken(verified, now));
			}
		});
		assertAll(checks);
	}

	@Test
	void anotherHmacAlgorithmIsRejectedEvenWithAValidSignature() {
		String token = Jwts.builder().claims(claims(false)).signWith(key, Jwts.SIG.HS384).compact();
		Jws<Claims> verified = jwt.parse(token);
		assertThat(verified.getHeader().getAlgorithm()).isEqualTo("HS384");
		invalid(() -> jwt.validateDemoAccessToken(verified, now));
	}

	@Test
	void wrongSigningKeyIsRejectedByTheExistingParser() {
		SecretKey other = Keys.hmacShaKeyFor(("different-test-only-signing-key-" + "x".repeat(40)).getBytes(StandardCharsets.UTF_8));
		String token = Jwts.builder().claims(claims(false)).signWith(other, Jwts.SIG.HS256).compact();
		assertThrows(JwtException.class, () -> jwt.parse(token));
	}

	@Test
	void expirationIsExclusiveForBothTokenKinds() {
		Jws<Claims> at = jwt.parse(jwt.createDemoAccessToken(7L, SID, now, now.plusSeconds(300)));
		Jws<Claims> rt = jwt.parse(jwt.createDemoRefreshToken(7L, SID, JTI, now, now.plusSeconds(3600)));
		assertThat(jwt.validateDemoAccessToken(at, now.plusSeconds(299)).userId()).isEqualTo(7L);
		assertThat(jwt.validateDemoRefreshToken(rt, now.plusSeconds(3599)).userId()).isEqualTo(7L);
		assertAll(() -> invalid(() -> jwt.validateDemoAccessToken(at, now.plusSeconds(300))),
			() -> invalid(() -> jwt.validateDemoRefreshToken(rt, now.plusSeconds(3600))));
	}

	@Test
	void accessCanEndAtAnEarlierAbsoluteSessionBoundary() {
		Jws<Claims> at = jwt.parse(jwt.createDemoAccessToken(7L, SID, now, now.plusSeconds(4)));
		assertThat(jwt.validateDemoAccessToken(at, now).expiresAt()).isEqualTo(now.plusSeconds(4));
	}

	@Test
	void generationRejectsInvalidIdentityAndTimeInputs() {
		assertAll(
			() -> invalid(() -> jwt.createDemoAccessToken(0L, SID, now, now.plusSeconds(300))),
			() -> invalid(() -> jwt.createDemoAccessToken(7L, "not-a-sid", now, now.plusSeconds(300))),
			() -> invalid(() -> jwt.createDemoAccessToken(7L, SID, now.plusNanos(1), now.plusSeconds(300))),
			() -> invalid(() -> jwt.createDemoAccessToken(7L, SID, now, now.plusSeconds(300).plusNanos(1))),
			() -> invalid(() -> jwt.createDemoAccessToken(7L, SID, now, now)),
			() -> invalid(() -> jwt.createDemoAccessToken(7L, SID, now, now.plusSeconds(301))),
			() -> invalid(() -> jwt.createDemoRefreshToken(7L, SID, "bad-jti", now, now.plusSeconds(3600))),
			() -> invalid(() -> jwt.createDemoRefreshToken(7L, SID, JTI, now, now.plusSeconds(3601))));
	}

	@Test
	void differentRefreshIdentifiersProduceDistinctTokensAtTheSameInstant() {
		String first = jwt.createDemoRefreshToken(7L, SID, JTI, now, now.plusSeconds(3600));
		String second = jwt.createDemoRefreshToken(7L, SID, identifier((byte) 3), now, now.plusSeconds(3600));
		assertThat(first.equals(second)).as("same-second refresh tokens must differ").isFalse();
	}

	@Test
	void originalTokenIssuanceFieldsAndConfiguredLifetimesRemainUnchanged() {
		Claims at = jwt.parse(jwt.createAccessToken(7L, "synthetic@example.invalid")).getPayload();
		Claims rt = jwt.parse(jwt.createRefreshToken(7L)).getPayload();
		assertThat(at.get("typ")).isEqualTo("ACCESS");
		assertThat(at.get("email")).isEqualTo("synthetic@example.invalid");
		assertThat(rt.get("typ")).isEqualTo("REFRESH");
		assertThat(rt).doesNotContainKeys("sid", "jti");
		assertThat(at.getExpiration().getTime() - at.getIssuedAt().getTime()).isEqualTo(900_000L);
		assertThat(rt.getExpiration().getTime() - rt.getIssuedAt().getTime()).isEqualTo(7_200_000L);
		assertThat(jwt.getRefreshTtlSeconds()).isEqualTo(7200L);
	}

	private Map<String, Object> claims(boolean refresh) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("iss", ISSUER);
		result.put("sub", "7");
		result.put("iat", now.getEpochSecond());
		result.put("exp", now.plusSeconds(refresh ? 3600 : 300).getEpochSecond());
		result.put("typ", refresh ? "DEMO_REFRESH" : "DEMO_ACCESS");
		result.put("sid", SID);
		if (refresh) result.put("jti", JTI);
		return result;
	}

	private String sign(Map<String, Object> claims) {
		return Jwts.builder().claims(claims).signWith(key, Jwts.SIG.HS256).compact();
	}

	private static void invalid(Executable call) {
		assertThat(assertThrows(DemoAuthException.class, call).reason()).isEqualTo(DemoAuthException.Reason.INVALID_DEMO_TOKEN);
	}
}
