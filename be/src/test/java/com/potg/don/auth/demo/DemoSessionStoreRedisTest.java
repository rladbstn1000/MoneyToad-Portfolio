package com.potg.don.auth.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Real Redis only, supplied by the disposable-resource runner; no shared keyspace cleanup. */
@Execution(ExecutionMode.SAME_THREAD)
class DemoSessionStoreRedisTest {

	private static final String HASH_A = hash("synthetic-refresh-a");
	private static final String HASH_B = hash("synthetic-refresh-b");
	private static final String HASH_C = hash("synthetic-refresh-c");
	private static final Set<String> OWNED_KEYS = ConcurrentHashMap.newKeySet();
	private static LettuceConnectionFactory connection;
	private static StringRedisTemplate redis;
	private static DemoSessionStore store;

	@BeforeAll
	static void connectOnlyToRunnerRedis() {
		String configuredHost = System.getenv("A1_REDIS_HOST");
		assertThat(configuredHost == null || configuredHost.equals("127.0.0.1")).isTrue();
		String configuredPort = System.getenv("A1_REDIS_PORT");
		assertThat(configuredPort).as("Dedicated runner Redis port is required").isNotBlank();
		int port = Integer.parseInt(configuredPort);
		assertThat(port).isBetween(1024, 65535).isNotEqualTo(6379);
		connection = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port),
			LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(1)).shutdownTimeout(Duration.ZERO).build());
		connection.afterPropertiesSet();
		redis = new StringRedisTemplate(connection);
		store = new DemoSessionStore(redis);
		try (var client = connection.getConnection()) {
			assertThat(client.ping()).isEqualTo("PONG");
		}
	}

	@AfterEach
	void deleteOnlyKeysCreatedByThisTest() {
		if (!OWNED_KEYS.isEmpty()) redis.delete(new ArrayList<>(OWNED_KEYS));
		OWNED_KEYS.clear();
	}

	@AfterAll
	static void closeOnlyOwnedClient() {
		if (connection != null) connection.destroy();
	}

	@Test
	void createsExactlyThreeHashFieldsAndFiniteAbsoluteTtlWithoutRawRefreshToken() {
		String sid = sid();
		Instant expiresAt = deadline();
		assertThat(store.create(sid, 101L, HASH_A, expiresAt)).isTrue();
		assertThat(redis.opsForHash().entries(key(sid))).isEqualTo(fields(101L, HASH_A, expiresAt));
		assertThat(redis.opsForHash().values(key(sid))).doesNotContain("synthetic-refresh-a");
		assertThat(redis.getExpire(key(sid), TimeUnit.MILLISECONDS)).isBetween(1L, 3_600_000L);
		assertThat(expiry(key(sid))).isEqualTo(expiresAt.toEpochMilli());
		assertThat(store.findActive(sid)).contains(new DemoSessionStore.SessionIdentity(101L, expiresAt));
		evidence("redis_create_hash_absolute_ttl");
	}

	@Test
	void duplicateSidNeverOverwritesExistingFieldsOrExpiry() {
		String sid = create(101L);
		Map<Object, Object> before = redis.opsForHash().entries(key(sid));
		long expiry = expiry(key(sid));
		assertThat(store.create(sid, 202L, HASH_B, deadline().plusSeconds(10))).isFalse();
		assertThat(redis.opsForHash().entries(key(sid))).isEqualTo(before);
		assertThat(expiry(key(sid))).isEqualTo(expiry);
		evidence("redis_collision_preserved");
	}

	@Test
	void rejectsPastAndMoreThanOneHourDeadlinesBeforeWriting() {
		for (Instant bad : List.of(Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS),
			Instant.now().plusSeconds(3_610).truncatedTo(ChronoUnit.MILLIS))) {
			String sid = sid();
			assertThatThrownBy(() -> store.create(sid, 101L, HASH_A, bad)).isInstanceOf(DemoAuthException.class);
			assertThat(redis.hasKey(key(sid))).isFalse();
		}
		evidence("redis_deadline_rejected_before_write");
	}

	@Test
	void invalidInternalInputsCannotWriteOrRotate() {
		String sid = create(101L);
		Map<Object, Object> before = redis.opsForHash().entries(key(sid));
		assertThatThrownBy(() -> store.create(sid, 0L, HASH_A, deadline())).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> store.create(sid, 101L, "raw-refresh", deadline())).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> store.create("a".repeat(43), 101L, HASH_A, deadline())).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> store.rotate(sid, 101L, deadline(), HASH_A, HASH_A)).isInstanceOf(IllegalArgumentException.class);
		assertThat(redis.opsForHash().entries(key(sid))).isEqualTo(before);
		evidence("redis_invalid_input_no_mutation");
	}

	@Test
	void userIdsAboveDoublePrecisionRemainExact() {
		String sid = sid();
		Instant expiresAt = deadline();
		assertThat(store.create(sid, Long.MAX_VALUE, HASH_A, expiresAt)).isTrue();
		assertThat(store.findActive(sid)).contains(new DemoSessionStore.SessionIdentity(Long.MAX_VALUE, expiresAt));
		assertThat(store.rotate(sid, Long.MAX_VALUE - 1, expiresAt, HASH_C, HASH_B)).isEqualTo(DemoSessionStore.RotationResult.INVALID);
		assertThat(store.rotate(sid, Long.MAX_VALUE, expiresAt, HASH_A, HASH_B)).isEqualTo(DemoSessionStore.RotationResult.ROTATED);
		evidence("redis_long_identity_exact");
	}

	@Test
	void successfulRotationChangesOnlyHashAndPreservesAbsoluteExpiry() {
		String sid = create(101L);
		Instant expiresAt = store.findActive(sid).orElseThrow().expiresAt();
		long beforeTtl = redis.getExpire(key(sid), TimeUnit.MILLISECONDS);
		long beforeExpiry = expiry(key(sid));
		assertThat(store.rotate(sid, 101L, expiresAt, HASH_A, HASH_B)).isEqualTo(DemoSessionStore.RotationResult.ROTATED);
		assertThat(redis.opsForHash().entries(key(sid))).isEqualTo(fields(101L, HASH_B, expiresAt));
		assertThat(expiry(key(sid))).isEqualTo(beforeExpiry);
		assertThat(redis.getExpire(key(sid), TimeUnit.MILLISECONDS)).isBetween(1L, beforeTtl);
		evidence("redis_rotation_preserves_expiry");
	}

	@Test
	void oldRefreshHashReuseRevokesTheEntireSid() {
		String sid = create(101L);
		Instant expiresAt = store.findActive(sid).orElseThrow().expiresAt();
		assertThat(store.rotate(sid, 101L, expiresAt, HASH_A, HASH_B)).isEqualTo(DemoSessionStore.RotationResult.ROTATED);
		assertThat(store.rotate(sid, 101L, expiresAt, HASH_A, HASH_C)).isEqualTo(DemoSessionStore.RotationResult.REUSED_REVOKED);
		assertThat(store.findActive(sid)).isEmpty();
		assertThat(store.rotate(sid, 101L, expiresAt, HASH_B, HASH_C)).isEqualTo(DemoSessionStore.RotationResult.INVALID);
		assertThat(redis.hasKey(key(sid))).isFalse();
		evidence("redis_reuse_revokes_family");
	}

	@Test
	void identityAndDeadlineMismatchDoNotTriggerHashReuseRevocation() {
		String sid = create(101L);
		Instant expiresAt = store.findActive(sid).orElseThrow().expiresAt();
		Map<Object, Object> before = redis.opsForHash().entries(key(sid));
		long expiry = expiry(key(sid));
		assertThat(store.rotate(sid, 202L, expiresAt, HASH_C, HASH_B)).isEqualTo(DemoSessionStore.RotationResult.INVALID);
		assertThat(store.rotate(sid, 101L, expiresAt.plusSeconds(1), HASH_C, HASH_B)).isEqualTo(DemoSessionStore.RotationResult.INVALID);
		assertThat(redis.opsForHash().entries(key(sid))).isEqualTo(before);
		assertThat(expiry(key(sid))).isEqualTo(expiry);
		evidence("redis_identity_before_hash");
	}

	@Test
	void concurrentSameRefreshHasOneExchangeAndThenRevokesFinalSession() throws Exception {
		String sid = create(101L);
		Instant expiresAt = store.findActive(sid).orElseThrow().expiresAt();
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var first = pool.submit(() -> { ready.countDown(); start.await(); return store.rotate(sid, 101L, expiresAt, HASH_A, HASH_B); });
			var second = pool.submit(() -> { ready.countDown(); start.await(); return store.rotate(sid, 101L, expiresAt, HASH_A, HASH_C); });
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))).containsExactlyInAnyOrder(
				DemoSessionStore.RotationResult.ROTATED, DemoSessionStore.RotationResult.REUSED_REVOKED);
		}
		assertThat(store.findActive(sid)).isEmpty();
		assertThat(redis.hasKey(key(sid))).isFalse();
		evidence("redis_concurrent_reuse_one_success_final_absent");
	}

	@Test
	void revokeRotationRaceNeverResurrectsDeletedSessions() throws Exception {
		try (var pool = Executors.newFixedThreadPool(2)) {
			for (int attempt = 0; attempt < 10; attempt++) {
				String sid = create(101L);
				Instant expiresAt = store.findActive(sid).orElseThrow().expiresAt();
				CountDownLatch ready = new CountDownLatch(2);
				CountDownLatch start = new CountDownLatch(1);
				var rotate = pool.submit(() -> { ready.countDown(); start.await(); return store.rotate(sid, 101L, expiresAt, HASH_A, HASH_B); });
				var revoke = pool.submit(() -> { ready.countDown(); start.await(); store.revoke(sid); return true; });
				assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
				start.countDown();
				assertThat(rotate.get(5, TimeUnit.SECONDS)).isIn(DemoSessionStore.RotationResult.ROTATED, DemoSessionStore.RotationResult.INVALID);
				assertThat(revoke.get(5, TimeUnit.SECONDS)).isTrue();
				assertThat(redis.hasKey(key(sid))).isFalse();
			}
		}
		evidence("redis_revoke_race_no_resurrection");
	}

	@Test
	void revokeIsIdempotentAndMissingSessionsCannotRotate() {
		String sid = create(101L);
		Instant expiresAt = store.findActive(sid).orElseThrow().expiresAt();
		store.revoke(sid);
		store.revoke(sid);
		assertThat(store.findActive(sid)).isEmpty();
		assertThat(store.rotate(sid, 101L, expiresAt, HASH_A, HASH_B)).isEqualTo(DemoSessionStore.RotationResult.INVALID);
		evidence("redis_revoke_missing_no_resurrection");
	}

	@Test
	void actualRedisTtlExpiryRemovesSessionWithoutSlidingRenewal() throws Exception {
		String sid = create(101L);
		Instant expiresAt = store.findActive(sid).orElseThrow().expiresAt();
		assertThat(redis.expire(key(sid), Duration.ofMillis(80))).isTrue();
		long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (redis.hasKey(key(sid)) && System.nanoTime() < limit) Thread.sleep(10);
		assertThat(redis.hasKey(key(sid))).isFalse();
		assertThat(store.findActive(sid)).isEmpty();
		assertThat(store.rotate(sid, 101L, expiresAt, HASH_A, HASH_B)).isEqualTo(DemoSessionStore.RotationResult.INVALID);
		evidence("redis_actual_ttl_expired");
	}

	@Test
	void absoluteExpiryStillRejectsAKeyWhoseRedisTtlWasExtended() {
		String sid = sid();
		Instant expired = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
		redis.opsForHash().putAll(key(sid), fields(101L, HASH_A, expired));
		redis.expire(key(sid), Duration.ofSeconds(60));
		assertInvalidWithoutMutation(sid, expired);
		evidence("redis_absolute_expiry_authoritative");
	}

	@Test
	void missingExpiryAndOutOfBoundsTtlAreNotActiveSessions() {
		String noTtl = create(101L);
		Instant noTtlExpiry = store.findActive(noTtl).orElseThrow().expiresAt();
		redis.persist(key(noTtl));
		assertInvalidWithoutMutation(noTtl, noTtlExpiry);
		String oversizedTtl = create(101L);
		Instant oversizedExpiry = store.findActive(oversizedTtl).orElseThrow().expiresAt();
		redis.expire(key(oversizedTtl), Duration.ofHours(2));
		assertInvalidWithoutMutation(oversizedTtl, oversizedExpiry);
		evidence("redis_missing_or_excessive_ttl_rejected");
	}

	@Test
	void malformedFieldsAndWrongRedisTypesFailClosedWithoutDeletion() {
		Instant expiresAt = deadline();
		for (Map<String, String> malformed : List.of(
			Map.of("userId", "0", "refreshHash", HASH_A, "expiresAt", Long.toString(expiresAt.toEpochMilli())),
			Map.of("userId", "9223372036854775808", "refreshHash", HASH_A, "expiresAt", Long.toString(expiresAt.toEpochMilli())),
			Map.of("userId", "101", "refreshHash", "broken", "expiresAt", Long.toString(expiresAt.toEpochMilli())),
			Map.of("userId", "101", "refreshHash", HASH_A, "expiresAt", "not-a-time"),
			Map.of("userId", "101", "refreshHash", HASH_A),
			Map.of("userId", "101", "refreshHash", HASH_A, "expiresAt", Long.toString(expiresAt.toEpochMilli()), "extra", "field"))) {
			String sid = sid();
			redis.opsForHash().putAll(key(sid), malformed);
			redis.expire(key(sid), Duration.ofSeconds(60));
			assertInvalidWithoutMutation(sid, expiresAt);
		}
		String wrongType = sid();
		redis.opsForValue().set(key(wrongType), "owned-sentinel", Duration.ofSeconds(60));
		assertThat(store.findActive(wrongType)).isEmpty();
		assertThat(store.rotate(wrongType, 101L, expiresAt, HASH_C, HASH_B)).isEqualTo(DemoSessionStore.RotationResult.INVALID);
		assertThat(redis.opsForValue().get(key(wrongType))).isEqualTo("owned-sentinel");
		evidence("redis_corrupt_session_rejected_without_deletion");
	}

	@Test
	void allDemoOperationsPreserveOrdinaryRefreshTokenValueAndExpiry() {
		String ordinaryKey = "RT:" + Long.toUnsignedString(new SecureRandom().nextLong() & Long.MAX_VALUE);
		OWNED_KEYS.add(ordinaryKey);
		assertThat(redis.opsForValue().setIfAbsent(ordinaryKey, "owned-normal-refresh-sentinel", Duration.ofMinutes(2))).isTrue();
		long ordinaryExpiry = expiry(ordinaryKey);
		String sid = create(101L);
		Instant expiresAt = store.findActive(sid).orElseThrow().expiresAt();
		store.rotate(sid, 101L, expiresAt, HASH_A, HASH_B);
		store.rotate(sid, 101L, expiresAt, HASH_A, HASH_C);
		store.revoke(sid);
		assertThat(redis.opsForValue().get(ordinaryKey)).isEqualTo("owned-normal-refresh-sentinel");
		assertThat(expiry(ordinaryKey)).isEqualTo(ordinaryExpiry);
		evidence("redis_standard_refresh_unchanged");
	}

	private static void assertInvalidWithoutMutation(String sid, Instant expiresAt) {
		Map<Object, Object> before = redis.opsForHash().entries(key(sid));
		long expiry = expiry(key(sid));
		assertThat(store.findActive(sid)).isEmpty();
		assertThat(store.rotate(sid, 101L, expiresAt, HASH_C, HASH_B)).isEqualTo(DemoSessionStore.RotationResult.INVALID);
		assertThat(redis.opsForHash().entries(key(sid))).isEqualTo(before);
		assertThat(expiry(key(sid))).isEqualTo(expiry);
	}

	private static String create(long userId) {
		String sid = sid();
		assertThat(store.create(sid, userId, HASH_A, deadline())).isTrue();
		return sid;
	}

	private static String sid() {
		byte[] random = new byte[32];
		new SecureRandom().nextBytes(random);
		String sid = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
		OWNED_KEYS.add(key(sid));
		return sid;
	}

	private static String key(String sid) { return "demo:session:" + sid; }
	private static Instant deadline() { return Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MILLIS); }
	private static Map<String, String> fields(long userId, String refreshHash, Instant expiresAt) {
		return Map.of("userId", Long.toString(userId), "refreshHash", refreshHash, "expiresAt", Long.toString(expiresAt.toEpochMilli()));
	}
	private static long expiry(String key) {
		return redis.execute(new DefaultRedisScript<>("return redis.call('PEXPIRETIME', KEYS[1])", Long.class), List.of(key));
	}
	private static String hash(String raw) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception exception) {
			throw new AssertionError(exception);
		}
	}
	private static void evidence(String scenario) {
		System.out.println("DEMO_SESSION_EVIDENCE {\"scenario\":\"" + scenario + "\",\"realRedis\":true,\"passed\":true}");
	}
}
