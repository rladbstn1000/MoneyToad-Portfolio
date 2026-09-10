package com.potg.don.auth.demo;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** The Redis key is the authority for a demo session; successful reads are never cached. */
@Component
@Profile("demo")
public class DemoSessionStore {

	private static final String PREFIX = "demo:session:";
	private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern SID = Pattern.compile("[A-Za-z0-9_-]{43}");
	private static final DefaultRedisScript<Long> CREATE = script("demo-session-create.lua", Long.class);
	private static final DefaultRedisScript<List> FIND = script("demo-session-find-active.lua", List.class);
	private static final DefaultRedisScript<Long> ROTATE = script("demo-session-rotate.lua", Long.class);
	private final StringRedisTemplate redis;

	public DemoSessionStore(StringRedisTemplate redis) {
		this.redis = redis;
	}

	public record SessionIdentity(long userId, Instant expiresAt) { }

	public enum RotationResult { ROTATED, INVALID, REUSED_REVOKED }

	/** False means an existing sid was left untouched. The deadline is never extended or corrected. */
	public boolean create(String sid, long userId, String refreshHash, Instant expiresAt) {
		String key = key(sid);
		validateUserId(userId);
		validateHash(refreshHash);
		String deadline = deadline(expiresAt);
		Long result = available(() -> redis.execute(CREATE, List.of(key), Long.toString(userId), refreshHash, deadline));
		if (result == 1L) return true;
		if (result == 0L) return false;
		if (result == -1L) throw unavailable();
		throw new IllegalStateException("Unexpected demo session create result");
	}

	public Optional<SessionIdentity> findActive(String sid) {
		String key = key(sid);
		List<?> result = available(() -> redis.execute(FIND, List.of(key)));
		if (result.isEmpty()) return Optional.empty();
		if (result.size() != 2) throw new IllegalStateException("Unexpected demo session lookup result");
		return Optional.of(new SessionIdentity(Long.parseLong((String) result.get(0)),
			Instant.ofEpochMilli(Long.parseLong((String) result.get(1)))));
	}

	public RotationResult rotate(String sid, long userId, Instant expiresAt, String expectedHash, String newHash) {
		String key = key(sid);
		validateUserId(userId);
		validateHash(expectedHash);
		validateHash(newHash);
		if (expectedHash.equals(newHash)) throw new IllegalArgumentException("A rotation needs a new token hash");
		String deadline = deadline(expiresAt);
		Long result = available(() -> redis.execute(ROTATE, List.of(key), Long.toString(userId), deadline,
			expectedHash, newHash));
		if (result == 1L) return RotationResult.ROTATED;
		if (result == 0L) return RotationResult.INVALID;
		if (result == -1L) return RotationResult.REUSED_REVOKED;
		throw new IllegalStateException("Unexpected demo session rotation result");
	}

	public void revoke(String sid) {
		String key = key(sid);
		available(() -> redis.delete(key));
	}

	private static String key(String sid) {
		if (sid == null || !SID.matcher(sid).matches()
			|| !Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(sid)).equals(sid)) {
			throw new IllegalArgumentException("Invalid internal demo session identifier");
		}
		return PREFIX + sid;
	}

	private static void validateUserId(long userId) {
		if (userId <= 0) throw new IllegalArgumentException("Invalid internal demo user identifier");
	}

	private static void validateHash(String hash) {
		if (hash == null || !HASH.matcher(hash).matches()) throw new IllegalArgumentException("Invalid refresh hash");
	}

	private static String deadline(Instant expiresAt) {
		if (expiresAt == null || expiresAt.getNano() % 1_000_000 != 0) {
			throw new IllegalArgumentException("Invalid internal demo deadline");
		}
		long millis = expiresAt.toEpochMilli();
		if (millis <= 0 || millis > 9_007_199_254_740_991L) throw new IllegalArgumentException("Invalid internal demo deadline");
		return Long.toString(millis);
	}

	private static <T> T available(Supplier<T> operation) {
		try {
			T result = operation.get();
			if (result == null) throw unavailable();
			return result;
		} catch (RedisConnectionFailureException | QueryTimeoutException exception) {
			// Do not expose connection details, and do not retry a possibly completed CAS.
			throw unavailable();
		}
	}

	private static DemoAuthException unavailable() {
		return new DemoAuthException(DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE);
	}

	private static <T> DefaultRedisScript<T> script(String file, Class<T> type) {
		DefaultRedisScript<T> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("redis/" + file));
		script.setResultType(type);
		return script;
	}
}
