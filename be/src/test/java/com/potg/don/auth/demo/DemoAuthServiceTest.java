package com.potg.don.auth.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import com.potg.don.auth.jwt.JwtUtil;
import com.potg.don.demo.seed.DemoSeedService;
import com.potg.don.demo.admission.DemoAdmissionStore;
import com.potg.don.demo.admission.DemoAdmissionException;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;

/** SQL/Redis failure orchestration with explicit doubles; real transaction tests are separate. */
class DemoAuthServiceTest {

	private final List<String> events = new ArrayList<>();
	private UserRepository users;
	private DemoSessionService sessions;
	private DemoSessionStore store;
	private JwtUtil jwt;
	private DemoSeedService seed;
	private DemoAdmissionStore admission;
	private DemoAuthService service;
	private RecordingTransactionManager transaction;
	private Instant now;

	@BeforeEach
	void setUp() {
		users = mock(UserRepository.class);
		sessions = mock(DemoSessionService.class);
		store = mock(DemoSessionStore.class);
		seed = mock(DemoSeedService.class);
		admission = mock(DemoAdmissionStore.class);
		org.mockito.Mockito.doAnswer(call -> { events.add("seed"); return null; })
			.when(seed).install(any(User.class), any(LocalDate.class));
		jwt = DemoJwtContractTest.configuredJwt();
		now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		transaction = new RecordingTransactionManager(events);
		service = new DemoAuthService(users, sessions, store, jwt, seed, admission, transaction,
			Clock.fixed(now, ZoneOffset.UTC), new SequenceRandom());
	}

	@Test
	void createsOnlySyntheticIdentityAndReturnsTokensAfterItsOwnCommit() {
		AtomicReference<User> inserted = successfulInsert();
		DemoSessionService.IssuedTokens issued = successfulIssue();
        when(admission.claimSlot()).thenReturn(11L);
		DemoSessionService.IssuedTokens returned = service.login(null);
		events.add("returned");
		assertThat(events).containsExactly("begin", "insert", "seed", "session", "commit", "returned");
		assertThat(transaction.propagation).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(transaction.isolation).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var order = org.mockito.Mockito.inOrder(admission, users, seed, sessions);
        order.verify(admission).claimSlot();
        order.verify(users).saveAndFlush(any(User.class));
        order.verify(seed).install(any(User.class), any(LocalDate.class));
        order.verify(sessions).startForUser(anyLong());
        order.verify(admission).recordVisit(7L, issued.expiresAt());
        order.verify(admission).verifyIntegrity(11L);
		assertThat(returned == issued).as("only the committed result is returned").isTrue();
		User user = inserted.get();
		assertThat(user.getEmail()).matches("demo-[0-9a-f]{32}@moneytoad\\.invalid");
		assertThat(user.getName()).isEqualTo("데모 방문자 " + user.getEmail().substring(5, 13));
		assertThat(user.getGender()).isNull();
		assertThat(user.getAge()).isNull();
		assertThat(user.getFileId()).isNull();
		verify(users).saveAndFlush(any(User.class));
		verify(seed).install(user, LocalDate.ofInstant(now, ZoneId.of("Asia/Seoul")));
		verifyNoMoreInteractions(seed, users);
		verifyNoInteractions(store);
	}

	@Test
	void eachCookielessLoginCreatesAnIndependentServerIdentity() {
		List<String> emails = new ArrayList<>();
		when(users.saveAndFlush(any(User.class))).thenAnswer(call -> {
			User user = call.getArgument(0);
			emails.add(user.getEmail());
			ReflectionTestUtils.setField(user, "id", (long) emails.size());
			return user;
		});
		when(sessions.startForUser(anyLong())).thenAnswer(call -> tokens(call.getArgument(0)));
		service.login(null);
		service.login(null);
		assertThat(emails.size()).isEqualTo(2);
		assertThat(emails.get(0).equals(emails.get(1))).as("fresh server-generated identity per login").isFalse();
		verify(users, times(2)).saveAndFlush(any(User.class));
		verifyNoMoreInteractions(users);
		verify(sessions, times(2)).startForUser(anyLong());
		verifyNoMoreInteractions(sessions);
		verifyNoInteractions(store);
	}

	@Test
	void insertCollisionRollsBackWithoutCallingRedisOrReusingAnExistingUser() {
		when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("synthetic collision"));
		assertThrows(DataIntegrityViolationException.class, () -> service.login(null));
		assertThat(events).containsExactly("begin", "rollback");
		verify(users).saveAndFlush(any(User.class));
		verifyNoMoreInteractions(users);
		verifyNoInteractions(sessions, store, seed);
	}


	@Test
	void seedDatabaseFailureRollsBackWithoutCallingRedis() {
		successfulInsert();
		doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic seed SQL failure"))
			.when(seed).install(any(User.class), any(LocalDate.class));
		assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> service.login(null));
		assertThat(events).containsExactly("begin", "insert", "rollback");
		verifyNoInteractions(sessions, store);
	}

	@Test
	void partialSeedContractFailureRollsBackAndDoesNotBecomeAuthenticationSuccess() {
		successfulInsert();
		doThrow(new IllegalStateException("DEMO_SEED_INCOMPLETE"))
			.when(seed).install(any(User.class), any(LocalDate.class));
		assertThrows(IllegalStateException.class, () -> service.login(null));
		assertThat(events).containsExactly("begin", "insert", "rollback");
		verifyNoInteractions(sessions, store);
	}

	@Test
	void loginCapturesOneKoreanDateBeforeInstallingSeedAcrossMonthBoundary() {
		var captured = successfulInsert();
		Clock boundaryClock = mock(Clock.class);
		when(boundaryClock.instant()).thenReturn(Instant.parse("2026-08-31T15:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"));
		var boundary = new DemoAuthService(users, sessions, store, jwt, seed, admission, transaction, boundaryClock, new SequenceRandom());
		doThrow(new IllegalArgumentException("stop after observing seed anchor"))
			.when(seed).install(any(User.class), any(LocalDate.class));
		assertThrows(IllegalArgumentException.class, () -> boundary.login(null));
		verify(seed).install(captured.get(), LocalDate.of(2026, 9, 1));
		verify(boundaryClock, times(1)).instant();
		verifyNoInteractions(sessions, store);
	}

	@Test
	void redisCreateFailureRollsBackWithoutReturningOrRetryingTokens() {
		successfulInsert();
		when(sessions.startForUser(anyLong())).thenThrow(unavailable());
		assertReason(DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE, () -> service.login(null));
		assertThat(events).containsExactly("begin", "insert", "seed", "rollback");
		verify(sessions).startForUser(anyLong());
		verifyNoMoreInteractions(sessions);
	}

	@Test
	void unacknowledgedCreationRollsBackAndDoesNotGuessASessionIdentifier() {
		successfulInsert();
		when(sessions.startForUser(anyLong())).thenAnswer(call -> {
			events.add("applied-without-acknowledgement");
			throw unavailable();
		});
		assertReason(DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE, () -> service.login(null));
		assertThat(events).containsExactly("begin", "insert", "seed", "applied-without-acknowledgement", "rollback");
		verify(sessions).startForUser(anyLong());
		verify(sessions, never()).revoke(anyString());
		verifyNoMoreInteractions(sessions);
	}

	@Test
	void failedCommitCompensatesOnlyTheAcknowledgedSessionAndDoesNotReturnTokens() {
		successfulInsert();
		DemoSessionService.IssuedTokens issued = successfulIssue();
		transaction.commitFailure = new TransactionSystemException("synthetic commit failure");
		AtomicReference<String> revoked = new AtomicReference<>();
		org.mockito.Mockito.doAnswer(call -> {
			revoked.set(call.getArgument(0));
			events.add("revoke");
			return null;
		}).when(sessions).revoke(anyString());
		assertThrows(TransactionSystemException.class, () -> service.login(null));
		assertThat(events).containsExactly("begin", "insert", "seed", "session", "commit", "revoke");
		String expected = jwt.parse(issued.refreshToken()).getPayload().get("sid", String.class);
		assertThat(expected.equals(revoked.get())).as("compensation is scoped to this creation").isTrue();
		verify(sessions).startForUser(anyLong());
		verify(sessions).revoke(anyString());
		verifyNoMoreInteractions(sessions);
	}

	@Test
	void failedCompensationPreservesTheOriginalFailureAndDoesNotRetry() {
		successfulInsert();
		successfulIssue();
		TransactionSystemException failure = new TransactionSystemException("synthetic unknown commit outcome");
		transaction.commitFailure = failure;
		doThrow(unavailable()).when(sessions).revoke(anyString());
		assertThat(assertThrows(TransactionSystemException.class, () -> service.login(null)) == failure).isTrue();
		verify(sessions).startForUser(anyLong());
		verify(sessions).revoke(anyString());
		verifyNoMoreInteractions(sessions);
	}

	@Test
	void unexpectedSessionImplementationFailureRollsBackAndRemainsAnError() {
		successfulInsert();
		when(sessions.startForUser(anyLong())).thenThrow(new IllegalStateException("synthetic defect"));
		assertThrows(IllegalStateException.class, () -> service.login(null));
		assertThat(events).containsExactly("begin", "insert", "seed", "rollback");
		verify(sessions).startForUser(anyLong());
		verifyNoMoreInteractions(sessions);
	}

	@Test
	void activeCookieRejectsLoginWithoutTransactionCreationOrRotation() {
		String refresh = tokens(7L).refreshToken();
		when(store.findActive(anyString())).thenReturn(Optional.of(new DemoSessionStore.SessionIdentity(7L, now.plusSeconds(3600))));
		when(users.existsById(7L)).thenReturn(true);
		ResponseStatusException failure = assertThrows(ResponseStatusException.class, () -> service.login(refresh));
		assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(events).isEmpty();
		verify(store).findActive(anyString());
		verify(users).existsById(7L);
		verifyNoMoreInteractions(store, users);
		verifyNoInteractions(sessions, seed);
	}

	@Test
	void malformedAndWrongPurposeCookiesNeverReachDatabaseOrSessionStore() {
		List<String> invalid = List.of("", "not.a.jwt", jwt.createAccessToken(7L, "synthetic@moneytoad.invalid"),
			jwt.createRefreshToken(7L), tokens(7L).accessToken());
		for (String value : invalid) {
			assertReason(DemoAuthException.Reason.INVALID_DEMO_TOKEN, () -> service.login(value));
			assertReason(DemoAuthException.Reason.INVALID_DEMO_TOKEN, () -> service.reissue(value));
		}
		assertReason(DemoAuthException.Reason.INVALID_DEMO_TOKEN, () -> service.reissue(null));
		assertThat(events).isEmpty();
		verifyNoInteractions(users, sessions, store, seed);
	}

	@Test
	void staleOrMismatchedCookieCannotCreateANewUserOrRevokeAnotherSession() {
		String refresh = tokens(7L).refreshToken();
		List<Optional<DemoSessionStore.SessionIdentity>> identities = List.of(Optional.empty(),
			Optional.of(new DemoSessionStore.SessionIdentity(8L, now.plusSeconds(3600))),
			Optional.of(new DemoSessionStore.SessionIdentity(7L, now.plusSeconds(3599))));
		for (Optional<DemoSessionStore.SessionIdentity> identity : identities) {
			when(store.findActive(anyString())).thenReturn(identity);
			assertReason(DemoAuthException.Reason.DEMO_SESSION_INVALID, () -> service.login(refresh));
		}
		assertThat(events).isEmpty();
		verifyNoInteractions(users, sessions);
	}

	@Test
	void cookieForADeletedUserIsInvalidAndNeverRecreatesThatUser() {
		when(store.findActive(anyString())).thenReturn(Optional.of(new DemoSessionStore.SessionIdentity(7L, now.plusSeconds(3600))));
		when(users.existsById(7L)).thenReturn(false);
		assertReason(DemoAuthException.Reason.DEMO_SESSION_INVALID, () -> service.login(tokens(7L).refreshToken()));
		verify(users).existsById(7L);
		verifyNoMoreInteractions(users);
		verifyNoInteractions(sessions, seed);
		assertThat(events).isEmpty();
	}

	@Test
	void existingCookieRedisFailureDoesNotStartASqlTransaction() {
		when(store.findActive(anyString())).thenThrow(unavailable());
		assertReason(DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE, () -> service.login(tokens(7L).refreshToken()));
		assertThat(events).isEmpty();
		verifyNoInteractions(users, sessions);
	}

	@Test
	void reissueUsesTheExistingAtomicSessionServiceOnlyAfterVerifiedUserExistence() {
		String previous = tokens(7L).refreshToken();
		DemoSessionService.IssuedTokens next = tokens(7L);
		when(users.existsById(7L)).thenAnswer(call -> { events.add("exists"); return true; });
		when(sessions.refresh(anyString())).thenAnswer(call -> {
			assertThat(previous.equals(call.getArgument(0))).as("only incoming cookie reaches rotation").isTrue();
			events.add("rotate");
			return next;
		});
		assertThat(service.reissue(previous) == next).isTrue();
		assertThat(events).containsExactly("exists", "rotate");
		verify(users).existsById(7L);
		verifyNoMoreInteractions(users);
		verify(sessions).refresh(anyString());
		verifyNoMoreInteractions(sessions);
		verifyNoInteractions(store);
	}

	@Test
	void deletedUserReissueDoesNotRotateOrRevoke() {
		when(users.existsById(7L)).thenReturn(false);
		assertReason(DemoAuthException.Reason.DEMO_SESSION_INVALID, () -> service.reissue(tokens(7L).refreshToken()));
		verifyNoInteractions(sessions, store, seed);
	}

	@Test
	void reuseAndUnknownCasOutcomeRemainDomainFailuresWithoutRetry() {
		when(users.existsById(7L)).thenReturn(true);
		for (DemoAuthException.Reason reason : List.of(DemoAuthException.Reason.DEMO_REFRESH_REUSED,
			DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE)) {
			doThrow(new DemoAuthException(reason)).when(sessions).refresh(anyString());
			assertReason(reason, () -> service.reissue(tokens(7L).refreshToken()));
		}
		verify(sessions, times(2)).refresh(anyString());
		verifyNoMoreInteractions(sessions);
		verifyNoInteractions(store);
	}

	@Test
	void logoutUsesOnlyTheAuthenticatedSessionAndPreservesStorageErrors() {
		DemoSessionGuard.AuthorizedSession authenticated = new DemoSessionGuard.AuthorizedSession(7L,
			DemoJwtContractTest.SID, now.plusSeconds(3600));
		service.logout(authenticated);
		verify(sessions).revoke(anyString());
		verifyNoMoreInteractions(sessions);
		verifyNoInteractions(users, store);
		doThrow(unavailable()).when(sessions).revoke(anyString());
		assertReason(DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE, () -> service.logout(authenticated));
	}

	@Test
	void missingAuthenticatedDetailsCannotRevokeAnySession() {
		assertReason(DemoAuthException.Reason.DEMO_SESSION_INVALID, () -> service.logout(null));
		verifyNoInteractions(users, sessions, store, seed);
	}

    @Test
    void everyAdmissionRefusalCreatesNeitherIdentityNorSession() {
        for (var code : DemoAdmissionException.Code.values()) {
            doThrow(new DemoAdmissionException(code)).when(admission).claimSlot();
            assertThat(assertThrows(DemoAdmissionException.class, () -> service.login(null)).code()).isEqualTo(code);
        }
        verifyNoInteractions(users, sessions, seed, store);
        verify(admission, never()).recordVisit(anyLong(), any(Instant.class));
        verify(admission, never()).verifyIntegrity(anyLong());
    }

    @Test
    void markerFailureRollsBackAndRevokesOnlyTheCreatedSession() {
        successfulInsert();
        successfulIssue();
        doThrow(new DemoAdmissionException(DemoAdmissionException.Code.DEMO_ADMISSION_UNAVAILABLE))
            .when(admission).recordVisit(anyLong(), any(Instant.class));
        assertThrows(DemoAdmissionException.class, () -> service.login(null));
        assertThat(events).containsExactly("begin", "insert", "seed", "session", "rollback");
        verify(sessions).revoke(anyString());
        verify(admission, never()).verifyIntegrity(anyLong());
    }

    @Test
    void finalIntegrityFailureRollsBackAndRevokesBeforeAnyTokensReturn() {
        successfulInsert();
        successfulIssue();
        doThrow(new DemoAdmissionException(DemoAdmissionException.Code.DEMO_ADMISSION_UNAVAILABLE))
            .when(admission).verifyIntegrity(anyLong());
        assertThrows(DemoAdmissionException.class, () -> service.login(null));
        assertThat(events).containsExactly("begin", "insert", "seed", "session", "rollback");
        verify(sessions).revoke(anyString());
    }

	private AtomicReference<User> successfulInsert() {
		AtomicReference<User> inserted = new AtomicReference<>();
		when(users.saveAndFlush(any(User.class))).thenAnswer(call -> {
			events.add("insert");
			User user = call.getArgument(0);
			ReflectionTestUtils.setField(user, "id", 7L);
			inserted.set(user);
			return user;
		});
		return inserted;
	}

	private DemoSessionService.IssuedTokens successfulIssue() {
		DemoSessionService.IssuedTokens issued = tokens(7L);
		when(sessions.startForUser(7L)).thenAnswer(call -> { events.add("session"); return issued; });
		return issued;
	}

	private DemoSessionService.IssuedTokens tokens(long userId) {
		return new DemoSessionService.IssuedTokens(
			jwt.createDemoAccessToken(userId, DemoJwtContractTest.SID, now, now.plusSeconds(300)),
			jwt.createDemoRefreshToken(userId, DemoJwtContractTest.SID, DemoJwtContractTest.JTI, now, now.plusSeconds(3600)),
			now.plusSeconds(3600));
	}

	private static DemoAuthException unavailable() {
		return new DemoAuthException(DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE);
	}

	private static void assertReason(DemoAuthException.Reason reason, org.junit.jupiter.api.function.Executable action) {
		assertThat(assertThrows(DemoAuthException.class, action).reason()).isEqualTo(reason);
	}

	private static class SequenceRandom extends SecureRandom {
		private int value = 40;
		@Override public void nextBytes(byte[] bytes) { Arrays.fill(bytes, (byte) value++); }
	}

	private static class RecordingTransactionManager implements PlatformTransactionManager {
		private final List<String> events;
		private RuntimeException commitFailure;
		private int propagation;
		private int isolation;
		RecordingTransactionManager(List<String> events) { this.events = events; }
		@Override public TransactionStatus getTransaction(TransactionDefinition definition) {
			propagation = definition.getPropagationBehavior();
			isolation = definition.getIsolationLevel();
			events.add("begin");
			return new SimpleTransactionStatus();
		}
		@Override public void commit(TransactionStatus status) {
			events.add("commit");
			if (commitFailure != null) throw commitFailure;
		}
		@Override public void rollback(TransactionStatus status) { events.add("rollback"); }
	}
}
