package com.potg.don.auth.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.potg.don.auth.jwt.JwtUtil;

import io.jsonwebtoken.Claims;

/** Service failure contract with a strict Mockito store; actual Redis is tested separately. */
class DemoSessionServiceTest {

	private JwtUtil jwt;
	private DemoSessionStore store;
	private DemoSessionService service;
	private Instant now;
	private Clock clock;

	@BeforeEach
	void setUp() {
		jwt = DemoJwtContractTest.configuredJwt();
		store = mock(DemoSessionStore.class);
		now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		clock = Clock.fixed(now, ZoneOffset.UTC);
		service = new DemoSessionService(jwt, store, clock, new SequenceRandom());
	}

	@Test
	void creationStoresOnlyTheRefreshHashAndAOneHourAbsoluteBoundary() throws Exception {
		when(store.create(anyString(), eq(7L), anyString(), any())).thenReturn(true);
		DemoSessionService.IssuedTokens result = service.startForUser(7L);
		Claims at = jwt.parse(result.accessToken()).getPayload();
		Claims rt = jwt.parse(result.refreshToken()).getPayload();
		String sid = at.get("sid", String.class);
		assertThat(result.expiresAt()).isEqualTo(now.plusSeconds(3600));
		assertThat(at.getIssuedAt().toInstant()).isEqualTo(now);
		assertThat(at.getExpiration().toInstant()).isEqualTo(now.plusSeconds(300));
		assertThat(rt.getExpiration().toInstant()).isEqualTo(result.expiresAt());
		assertThat(rt.get("sid")).isEqualTo(sid);
		assertThat(sid).hasSize(43);
		assertThat(rt.getId()).hasSize(43).isNotEqualTo(sid);
		verify(store).create(sid, 7L, hash(result.refreshToken()), result.expiresAt());
		verifyNoMoreInteractions(store);
		assertThat(result.toString().contains(result.accessToken())).as("record text must redact AT").isFalse();
		assertThat(result.toString().contains(result.refreshToken())).as("record text must redact RT").isFalse();
	}

	@Test
	void createCollisionDoesNotOverwriteOrRetryAndReturnsNoTokens() {
		when(store.create(anyString(), anyLong(), anyString(), any())).thenReturn(false);
		failure(DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE, () -> service.startForUser(7L));
		verify(store).create(anyString(), eq(7L), anyString(), eq(now.plusSeconds(3600)));
		verifyNoMoreInteractions(store);
	}

	@Test
	void createStorageFailureReturnsNoTokensAndDoesNotRetry() {
		when(store.create(anyString(), anyLong(), anyString(), any()))
			.thenThrow(new DemoAuthException(DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE));
		failure(DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE, () -> service.startForUser(7L));
		verify(store).create(anyString(), eq(7L), anyString(), eq(now.plusSeconds(3600)));
		verifyNoMoreInteractions(store);
	}

	@Test
	void invalidServerUserIdNeverWritesToRedis() {
		assertAll(() -> failure(DemoAuthException.Reason.INVALID_DEMO_TOKEN, () -> service.startForUser(0L)),
			() -> failure(DemoAuthException.Reason.INVALID_DEMO_TOKEN, () -> service.startForUser(-1L)));
		verifyNoInteractions(store);
	}

	@Test
	void rotationPreservesAbsoluteExpiryAndIdentityWhileChangingTheRefreshHash() throws Exception {
		String old = refresh(now.minusSeconds(100), now.plusSeconds(3500), DemoJwtContractTest.JTI);
		when(store.rotate(anyString(), anyLong(), any(), anyString(), anyString()))
			.thenReturn(DemoSessionStore.RotationResult.ROTATED);
		DemoSessionService.IssuedTokens result = service.refresh(old);
		Claims rt = jwt.parse(result.refreshToken()).getPayload();
		Claims at = jwt.parse(result.accessToken()).getPayload();
		assertThat(result.expiresAt()).isEqualTo(now.plusSeconds(3500));
		assertThat(rt.getExpiration().toInstant()).isEqualTo(result.expiresAt());
		assertThat(rt.getSubject()).isEqualTo("7");
		assertThat(rt.get("sid")).isEqualTo(DemoJwtContractTest.SID);
		assertThat(rt.getId()).isNotEqualTo(DemoJwtContractTest.JTI);
		assertThat(rt.getIssuedAt().toInstant()).isEqualTo(now);
		assertThat(at.getExpiration().toInstant()).isEqualTo(now.plusSeconds(300));
		assertThat(old.equals(result.refreshToken())).as("rotation must replace RT").isFalse();
		verify(store).rotate(DemoJwtContractTest.SID, 7L, result.expiresAt(), hash(old), hash(result.refreshToken()));
		verifyNoMoreInteractions(store);
	}

	@Test
	void refreshedAccessCannotOutliveTheLastSecondsOfTheSession() {
		String old = refresh(now.minusSeconds(3597), now.plusSeconds(3), DemoJwtContractTest.JTI);
		when(store.rotate(anyString(), anyLong(), any(), anyString(), anyString()))
			.thenReturn(DemoSessionStore.RotationResult.ROTATED);
		DemoSessionService.IssuedTokens result = service.refresh(old);
		assertThat(jwt.parse(result.accessToken()).getPayload().getExpiration().toInstant()).isEqualTo(now.plusSeconds(3));
		assertThat(result.expiresAt()).isEqualTo(now.plusSeconds(3));
	}

	@Test
	void repeatedRotationsWithinOneSecondStillHaveDistinctRefreshTokens() {
		String old = refresh(now, now.plusSeconds(3600), DemoJwtContractTest.JTI);
		when(store.rotate(anyString(), anyLong(), any(), anyString(), anyString()))
			.thenReturn(DemoSessionStore.RotationResult.ROTATED);
		DemoSessionService.IssuedTokens first = service.refresh(old);
		DemoSessionService.IssuedTokens second = service.refresh(first.refreshToken());
		assertThat(first.refreshToken().equals(second.refreshToken())).as("fixed-clock RT rotation must remain unique").isFalse();
		assertThat(first.expiresAt()).isEqualTo(second.expiresAt());
		assertThat(jwt.parse(first.refreshToken()).getPayload().getIssuedAt())
			.isEqualTo(jwt.parse(second.refreshToken()).getPayload().getIssuedAt());
	}

	@Test
	void malformedOrWrongPurposeCredentialsNeverReachTheStore() {
		String ordinaryAccess = jwt.createAccessToken(7L, "synthetic@example.invalid");
		String ordinaryRefresh = jwt.createRefreshToken(7L);
		String demoAccess = jwt.createDemoAccessToken(7L, DemoJwtContractTest.SID, now, now.plusSeconds(300));
		JwtUtil other = new JwtUtil("other-synthetic-signing-key-that-is-definitely-not-the-same-0123456789");
		ReflectionTestUtils.setField(other, "issuer", DemoJwtContractTest.ISSUER);
		String wrongSignature = other.createDemoRefreshToken(7L, DemoJwtContractTest.SID, DemoJwtContractTest.JTI,
			now, now.plusSeconds(3600));
		assertAll(() -> failure(DemoAuthException.Reason.INVALID_DEMO_TOKEN, () -> service.refresh(null)),
			() -> failure(DemoAuthException.Reason.INVALID_DEMO_TOKEN, () -> service.refresh("")),
			() -> failure(DemoAuthException.Reason.INVALID_DEMO_TOKEN, () -> service.refresh("not.a.jwt")),
			() -> failure(DemoAuthException.Reason.INVALID_DEMO_TOKEN, () -> service.refresh(ordinaryAccess)),
			() -> failure(DemoAuthException.Reason.INVALID_DEMO_TOKEN, () -> service.refresh(ordinaryRefresh)),
			() -> failure(DemoAuthException.Reason.INVALID_DEMO_TOKEN, () -> service.refresh(demoAccess)),
			() -> failure(DemoAuthException.Reason.INVALID_DEMO_TOKEN, () -> service.refresh(wrongSignature)));
		verifyNoInteractions(store);
	}

	@Test
	void invalidSessionDoesNotReturnTokensOrPerformASecondMutation() {
		String old = refresh(now, now.plusSeconds(3600), DemoJwtContractTest.JTI);
		when(store.rotate(anyString(), anyLong(), any(), anyString(), anyString()))
			.thenReturn(DemoSessionStore.RotationResult.INVALID);
		failure(DemoAuthException.Reason.DEMO_SESSION_INVALID, () -> service.refresh(old));
		verify(store).rotate(eq(DemoJwtContractTest.SID), eq(7L), eq(now.plusSeconds(3600)), anyString(), anyString());
		verifyNoMoreInteractions(store);
	}

	@Test
	void reuseRevocationIsReportedWithoutReturningTheCandidateTokens() {
		String old = refresh(now, now.plusSeconds(3600), DemoJwtContractTest.JTI);
		when(store.rotate(anyString(), anyLong(), any(), anyString(), anyString()))
			.thenReturn(DemoSessionStore.RotationResult.REUSED_REVOKED);
		failure(DemoAuthException.Reason.DEMO_REFRESH_REUSED, () -> service.refresh(old));
		verify(store).rotate(eq(DemoJwtContractTest.SID), eq(7L), eq(now.plusSeconds(3600)), anyString(), anyString());
		verifyNoMoreInteractions(store);
	}

	@Test
	void unknownCasOutcomeDoesNotRetryRestoreHashOrReturnTokens() {
		String old = refresh(now, now.plusSeconds(3600), DemoJwtContractTest.JTI);
		when(store.rotate(anyString(), anyLong(), any(), anyString(), anyString()))
			.thenThrow(new DemoAuthException(DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE));
		failure(DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE, () -> service.refresh(old));
		verify(store).rotate(eq(DemoJwtContractTest.SID), eq(7L), eq(now.plusSeconds(3600)), anyString(), anyString());
		verifyNoMoreInteractions(store);
	}

	@Test
	void accidentalSameRefreshCandidateIsRejectedBeforeCasWithoutRetry() {
		String fixedJti = DemoJwtContractTest.identifier((byte) 9);
		String old = refresh(now, now.plusSeconds(3600), fixedJti);
		SecureRandom repeated = new SecureRandom() {
			@Override public void nextBytes(byte[] bytes) { Arrays.fill(bytes, (byte) 9); }
		};
		DemoSessionService repeatedRandom = new DemoSessionService(jwt, store, clock, repeated);
		failure(DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE, () -> repeatedRandom.refresh(old));
		verifyNoInteractions(store);
	}

	@Test
	void internalRevocationDelegatesOnceAndDoesNotHideStorageFailure() {
		service.revoke(DemoJwtContractTest.SID);
		verify(store).revoke(DemoJwtContractTest.SID);
		verifyNoMoreInteractions(store);
		DemoSessionStore unavailable = mock(DemoSessionStore.class);
		doThrow(new DemoAuthException(DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE))
			.when(unavailable).revoke(DemoJwtContractTest.SID);
		DemoSessionService failing = new DemoSessionService(jwt, unavailable, clock, new SecureRandom());
		failure(DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE, () -> failing.revoke(DemoJwtContractTest.SID));
		verify(unavailable).revoke(DemoJwtContractTest.SID);
		verifyNoMoreInteractions(unavailable);
	}

	@Test
	void unexpectedImplementationFailureRemainsAServerError() {
		IllegalStateException defect = new IllegalStateException("synthetic implementation failure");
		when(store.create(anyString(), anyLong(), anyString(), any())).thenThrow(defect);
		assertThat(assertThrows(IllegalStateException.class, () -> service.startForUser(7L))).isSameAs(defect);
	}

	private String refresh(Instant issuedAt, Instant expiresAt, String jti) {
		return jwt.createDemoRefreshToken(7L, DemoJwtContractTest.SID, jti, issuedAt, expiresAt);
	}

	private static String hash(String token) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
	}

	private static void failure(DemoAuthException.Reason expected, org.junit.jupiter.api.function.Executable action) {
		assertThat(assertThrows(DemoAuthException.class, action).reason()).isEqualTo(expected);
	}

	private static class SequenceRandom extends SecureRandom {
		private int next = 20;
		@Override public void nextBytes(byte[] bytes) { Arrays.fill(bytes, (byte) next++); }
	}
}
