package com.potg.don.auth.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

import com.potg.don.auth.DemoAuthHttpTestSupport;
import com.potg.don.auth.jwt.JwtUtil;
import com.potg.don.demo.seed.DemoSeedService;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;

/** Actual MySQL/JPA transactions and Redis; failures are explicitly named local boundaries. */
@Execution(ExecutionMode.SAME_THREAD)
class DemoAuthLoginTransactionTest {

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void realRedisConnectionRefusalOrTimeoutRollsBackTheInsertedUser(boolean timeout) throws Exception {
		try (var support = new DemoAuthHttpTestSupport(); var started = support.startDemo();
			var socket = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
			var context = context(started);
			var jdbc = jdbc(context);
			var before = DemoAuthHttpTestSupport.snapshot(jdbc);
			int port = socket.getLocalPort();
			if (!timeout) socket.close();
			var options = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(150))
				.shutdownTimeout(Duration.ZERO).clientOptions(io.lettuce.core.ClientOptions.builder()
					.socketOptions(io.lettuce.core.SocketOptions.builder().connectTimeout(Duration.ofMillis(150)).build())
					.timeoutOptions(io.lettuce.core.TimeoutOptions.enabled(Duration.ofMillis(150))).build()).build();
			var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port), options);
			factory.afterPropertiesSet();
			factory.start();
			var observed = new ObservedStore(new StringRedisTemplate(factory));
			try {
				var result = failedLogin(service(context, observed, manager(context), new SecureRandom()), DemoAuthException.class);
				assertThat(result.returnedTokens()).isZero();
				assertThat(result.failed()).isTrue();
				assertThat(observed.creates.get()).isEqualTo(1);
				assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(jdbc))).isTrue();
				check(support, started, timeout ? "tx_redis_timeout_rolls_back_user" : "tx_redis_refusal_rolls_back_user");
			} finally {
				factory.destroy();
				cleanup(context, observed);
			}
		}
	}

	@Test
	void failedDatabaseCommitAfterRealRedisCreateRevokesKnownSidAndReturnsNoTokens() throws Exception {
		try (var support = new DemoAuthHttpTestSupport(); var started = support.startDemo()) {
			var context = context(started);
			var before = DemoAuthHttpTestSupport.snapshot(jdbc(context));
			var observed = new ObservedStore(redis(context));
			try {
				var transaction = new FailingCommitManager(manager(context), false);
				var result = failedLogin(service(context, observed, transaction, new SecureRandom()), TransactionSystemException.class);
				assertThat(result.returnedTokens()).isZero();
				assertThat(result.failed()).isTrue();
				assertThat(transaction.commits.get()).isEqualTo(1);
				assertThat(observed.createdSuccessfully.get()).isEqualTo(1);
				assertThat(observed.revokes.get()).isEqualTo(1);
				assertThat(observed.sids.stream().allMatch(sid -> observed.findActive(sid).isEmpty())).isTrue();
				assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(jdbc(context)))).isTrue();
				check(support, started, "tx_commit_failure_revokes_known_session");
			} finally { cleanup(context, observed); }
		}
	}

	@Test
	void actualDatabaseCommitWithLostAcknowledgementDoesNotDeleteTheCommittedUser() throws Exception {
		try (var support = new DemoAuthHttpTestSupport(); var started = support.startDemo()) {
			var context = context(started);
			long before = users(context).count();
			var observed = new ObservedStore(redis(context));
			try {
				var transaction = new FailingCommitManager(manager(context), true);
				var result = failedLogin(service(context, observed, transaction, new SecureRandom()), TransactionSystemException.class);
				assertThat(result.returnedTokens()).isZero();
				assertThat(result.failed()).isTrue();
				assertThat(users(context).count()).isEqualTo(before + 1);
				assertSeedRows(context, 1);
				assertThat(observed.createdSuccessfully.get()).isEqualTo(1);
				assertThat(observed.revokes.get()).isEqualTo(1);
				assertThat(observed.sids.stream().allMatch(sid -> observed.findActive(sid).isEmpty())).isTrue();
				check(support, started, "tx_commit_ack_lost_no_destructive_user_compensation");
			} finally { cleanup(context, observed); }
		}
	}

	@Test
	void failedRevokeCompensationStillReturnsNoTokensAndLeavesOnlyABoundedSession() throws Exception {
		try (var support = new DemoAuthHttpTestSupport(); var started = support.startDemo()) {
			var context = context(started);
			var before = DemoAuthHttpTestSupport.snapshot(jdbc(context));
			var observed = new ObservedStore(redis(context));
			observed.failRevoke = true;
			try {
				var result = failedLogin(service(context, observed, new FailingCommitManager(manager(context), false), new SecureRandom()), TransactionSystemException.class);
				assertThat(result.returnedTokens()).isZero();
				assertThat(result.failed()).isTrue();
				assertThat(observed.createdSuccessfully.get()).isEqualTo(1);
				assertThat(observed.revokes.get()).isEqualTo(1);
				assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(jdbc(context)))).isTrue();
				assertFiniteOrphanWithoutDatabaseUser(context, observed);
				check(support, started, "tx_revoke_compensation_failure_returns_no_tokens");
			} finally { cleanup(context, observed); }
		}
	}

	@Test
	void actualRedisCreateThenLostResultRollsBackUserWithoutReturningCredentials() throws Exception {
		try (var support = new DemoAuthHttpTestSupport(); var started = support.startDemo()) {
			var context = context(started);
			var before = DemoAuthHttpTestSupport.snapshot(jdbc(context));
			var observed = new ObservedStore(redis(context));
			observed.loseCreateResult = true;
			try {
				var result = failedLogin(service(context, observed, manager(context), new SecureRandom()), DemoAuthException.class);
				assertThat(result.returnedTokens()).isZero();
				assertThat(result.failed()).isTrue();
				assertThat(observed.createdSuccessfully.get()).isEqualTo(1);
				assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(jdbc(context)))).isTrue();
				assertFiniteOrphanWithoutDatabaseUser(context, observed);
				check(support, started, "tx_applied_redis_create_lost_result_rolls_back_user");
			} finally { cleanup(context, observed); }
		}
	}


	@Test
	void realBudgetInsertFailureRollsBackUserCardAndAllTransactionsBeforeRedis() throws Exception {
		try (var support = new DemoAuthHttpTestSupport(); var started = support.startDemo()) {
			var context = context(started);
			var jdbc = jdbc(context);
			var before = DemoAuthHttpTestSupport.snapshot(jdbc);
			var observed = new ObservedStore(redis(context));
			// Owned disposable schema only. Fail after Card and Transaction inserts, at the Budget boundary.
			jdbc.execute("CREATE TRIGGER demo_seed_reject_budget BEFORE INSERT ON budgets FOR EACH ROW "
				+ "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic seed storage failure'");
			try {
				var result = failedLogin(service(context, observed, manager(context), new SecureRandom()), RuntimeException.class);
				assertThat(result.returnedTokens()).isZero();
				assertThat(result.failed()).isTrue();
				assertThat(observed.creates.get()).isZero();
				assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(jdbc))).isTrue();
				check(support, started, "tx_seed_sql_failure_rolls_back_all_children");
			} finally {
				jdbc.execute("DROP TRIGGER demo_seed_reject_budget");
				cleanup(context, observed);
			}
		}
	}

	@Test
	void realUniqueEmailConstraintPreservesExistingUserAndNeverCreatesRedisSession() throws Exception {
		try (var support = new DemoAuthHttpTestSupport(); var started = support.startDemo()) {
			var context = context(started);
			String email = "demo-" + "0".repeat(32) + "@moneytoad.invalid";
			users(context).saveAndFlush(User.createUser(email, "Existing Synthetic Visitor"));
			var before = DemoAuthHttpTestSupport.snapshot(jdbc(context));
			var observed = new ObservedStore(redis(context));
			try {
				var result = failedLogin(service(context, observed, manager(context), new ZeroRandom()), DataIntegrityViolationException.class);
				assertThat(result.returnedTokens()).isZero();
				assertThat(result.failed()).isTrue();
				assertThat(observed.creates.get()).isZero();
				assertThat(observed.revokes.get()).isZero();
				assertThat(before.equals(DemoAuthHttpTestSupport.snapshot(jdbc(context)))).isTrue();
				check(support, started, "tx_real_unique_collision_preserves_existing_user");
			} finally { cleanup(context, observed); }
		}
	}

	@Test
	void concurrentCookieLessLoginIsBusyAndExplicitLaterLoginRemainsDistinct() throws Exception {
		try (var support = new DemoAuthHttpTestSupport(); var started = support.startDemo()) {
			var context = context(started);
			long before = users(context).count();
			var observed = new ObservedStore(redis(context));
			observed.createReached = new CountDownLatch(1);
			observed.createRelease = new CountDownLatch(1);
			var service = service(context, observed, manager(context), new SecureRandom());
			var executor = Executors.newFixedThreadPool(2);
			try {
				var first = executor.submit(() -> service.login(null));
				assertThat(observed.createReached.await(10, TimeUnit.SECONDS)).isTrue();
                var busy = org.junit.jupiter.api.Assertions.assertThrows(
                    com.potg.don.demo.admission.DemoAdmissionException.class, () -> service.login(null));
                assertThat(busy.getMessage()).isEqualTo("DEMO_ADMISSION_BUSY");
                assertThat(observed.creates.get()).isEqualTo(1);
                observed.createRelease.countDown();
                var a = first.get(20, TimeUnit.SECONDS);
                observed.createReached = null;
                observed.createRelease = null;
                // A new explicit request after the first commit preserves visitor isolation.
                var b = service.login(null);
				assertThat(a != null && b != null).isTrue();
				assertThat(users(context).count()).isEqualTo(before + 2);
				assertSeedRows(context, 2);
				assertThat(observed.creates.get()).isEqualTo(2);
				assertThat(observed.createdSuccessfully.get()).isEqualTo(2);
				assertThat(observed.revokes.get()).isZero();
				assertThat(Set.copyOf(observed.sids).size()).isEqualTo(2);
				List<DemoSessionStore.SessionIdentity> identities = observed.sids.stream()
					.map(sid -> observed.findActive(sid).orElseThrow()).toList();
				assertThat(identities.stream().map(DemoSessionStore.SessionIdentity::userId).distinct().count()).isEqualTo(2);
				for (var identity : identities) {
					User user = users(context).findById(identity.userId()).orElseThrow();
					assertThat(user.getEmail().startsWith("demo-") && user.getEmail().endsWith("@moneytoad.invalid")).isTrue();
					assertThat(user.getGender()).isNull();
					assertThat(user.getAge()).isNull();
					assertThat(user.getFileId()).isNull();
				}
				check(support, started, "tx_concurrent_cookie_less_logins_are_distinct");
			} finally { if (observed.createRelease != null) observed.createRelease.countDown(); executor.shutdownNow(); cleanup(context, observed); }
		}
	}

	private static void assertSeedRows(ConfigurableApplicationContext context, int visitors) {
		var jdbc = jdbc(context);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cards", Integer.class)).isEqualTo(visitors);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class)).isEqualTo(visitors * 240);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budgets", Integer.class)).isEqualTo(visitors * 72);
		assertThat(jdbc(context).queryForObject("SELECT COUNT(*) FROM demo_visit", Integer.class)).isEqualTo(visitors);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM demo_capacity WHERE id=1 AND max_visitors=1000", Integer.class)).isEqualTo(1);
	}

	private static DemoAuthService service(ConfigurableApplicationContext context, DemoSessionStore store,
		PlatformTransactionManager manager, SecureRandom random) {
		if (store instanceof ObservedStore observed) {
			observed.beforeCreateCheck = userId -> {
				var jdbc = jdbc(context);
				assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cards WHERE user_id=?", Integer.class, userId)).isEqualTo(1);
				assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions t JOIN cards c ON t.card_id=c.id WHERE c.user_id=?", Integer.class, userId)).isEqualTo(240);
				assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budgets WHERE user_id=?", Integer.class, userId)).isEqualTo(72);
			};
		}
		var sessions = new DemoSessionService(context.getBean(JwtUtil.class), store);
		return new DemoAuthService(users(context), sessions, store, context.getBean(JwtUtil.class), context.getBean(DemoSeedService.class), context.getBean(com.potg.don.demo.admission.DemoAdmissionStore.class), manager, Clock.systemUTC(), random);
	}

	private record Failure(boolean failed, int returnedTokens) { }
	private static Failure failedLogin(DemoAuthService service, Class<? extends RuntimeException> expectedType) {
		int returned = 0;
		try {
			service.login(null);
			returned++;
			return new Failure(false, returned);
		} catch (RuntimeException expected) {
			assertThat(expectedType.isInstance(expected)).as("Expected failure category").isTrue();
			if (expected instanceof DemoAuthException demo) {
				assertThat(demo.reason()).isEqualTo(DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE);
			}
			return new Failure(true, returned);
		}
	}

	private static void assertFiniteOrphanWithoutDatabaseUser(ConfigurableApplicationContext context, ObservedStore observed) {
		assertThat(observed.sids.size()).isEqualTo(1);
		String sid = observed.sids.getFirst();
		var identity = observed.findActive(sid).orElseThrow();
		assertThat(users(context).existsById(identity.userId())).isFalse();
		long ttl = redis(context).getExpire("demo:session:" + sid, TimeUnit.MILLISECONDS);
		assertThat(ttl).isPositive().isLessThanOrEqualTo(3_600_000L);
	}

	private static void cleanup(ConfigurableApplicationContext context, ObservedStore observed) {
		if (!observed.sids.isEmpty()) redis(context).delete(observed.sids.stream().map(sid -> "demo:session:" + sid).toList());
	}

	private static ConfigurableApplicationContext context(DemoAuthHttpTestSupport.Started started) {
		assertThat(started.failure() == null).as("Owned product context starts").isTrue();
		return started.context();
	}
	private static UserRepository users(ConfigurableApplicationContext context) { return context.getBean(UserRepository.class); }
	private static StringRedisTemplate redis(ConfigurableApplicationContext context) { return context.getBean(StringRedisTemplate.class); }
	private static JdbcTemplate jdbc(ConfigurableApplicationContext context) { return new JdbcTemplate(context.getBean(DataSource.class)); }
	private static PlatformTransactionManager manager(ConfigurableApplicationContext context) { return context.getBean(PlatformTransactionManager.class); }
	private static void check(DemoAuthHttpTestSupport support, DemoAuthHttpTestSupport.Started started, String scenario) throws Exception {
		assertThat(support.noOutbound(started.probe())).isTrue();
		DemoAuthHttpTestSupport.emit(Map.of("scenario", scenario, "transactionContract", true, "outboundRequests", 0));
	}

	private static class ObservedStore extends DemoSessionStore {
		final List<String> sids = new CopyOnWriteArrayList<>();
		final AtomicInteger creates = new AtomicInteger();
		final AtomicInteger createdSuccessfully = new AtomicInteger();
		final AtomicInteger revokes = new AtomicInteger();
		boolean loseCreateResult;
		boolean failRevoke;
		CountDownLatch createReached;
        CountDownLatch createRelease;
		java.util.function.LongConsumer beforeCreateCheck = userId -> { };
		ObservedStore(StringRedisTemplate redis) { super(redis); }
		@Override public boolean create(String sid, long userId, String hash, Instant expiresAt) {
			beforeCreateCheck.accept(userId);
			sids.add(sid);
			creates.incrementAndGet();
			if (createReached != null) {
                createReached.countDown();
                try { if (!createRelease.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic hold timed out"); }
				catch (Exception failure) { throw new IllegalStateException("Synthetic concurrency boundary failed"); }
			}
			boolean result = super.create(sid, userId, hash, expiresAt);
			if (result) createdSuccessfully.incrementAndGet();
			if (loseCreateResult) throw new DemoAuthException(DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE);
			return result;
		}
		@Override public void revoke(String sid) {
			revokes.incrementAndGet();
			if (failRevoke) throw new DemoAuthException(DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE);
			super.revoke(sid);
		}
	}

	private static final class ZeroRandom extends SecureRandom {
		@Override public void nextBytes(byte[] bytes) { Arrays.fill(bytes, (byte) 0); }
	}

	/** The delegate really commits/rolls back; only acknowledgement/failure timing is injected. */
	private static final class FailingCommitManager implements PlatformTransactionManager {
		private final PlatformTransactionManager delegate;
		private final boolean commitThenLoseAcknowledgement;
		private final AtomicInteger commits = new AtomicInteger();
		FailingCommitManager(PlatformTransactionManager delegate, boolean commitThenLoseAcknowledgement) {
			this.delegate = delegate;
			this.commitThenLoseAcknowledgement = commitThenLoseAcknowledgement;
		}
		@Override public TransactionStatus getTransaction(TransactionDefinition definition) { return delegate.getTransaction(definition); }
		@Override public void commit(TransactionStatus status) {
			commits.incrementAndGet();
			if (commitThenLoseAcknowledgement) delegate.commit(status);
			else delegate.rollback(status);
			throw new TransactionSystemException("Synthetic database commit boundary failure");
		}
		@Override public void rollback(TransactionStatus status) { if (!status.isCompleted()) delegate.rollback(status); }
	}
}
