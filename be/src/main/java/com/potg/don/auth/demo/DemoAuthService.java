package com.potg.don.auth.demo;

import static com.potg.don.auth.demo.DemoAuthException.Reason.DEMO_SESSION_INVALID;
import static com.potg.don.auth.demo.DemoAuthException.Reason.INVALID_DEMO_TOKEN;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.potg.don.auth.jwt.JwtUtil;
import com.potg.don.demo.seed.DemoSeedService;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;

import io.jsonwebtoken.JwtException;

/** Creates only server-generated identities; a Redis session remains the authentication authority. */
@Service
@Profile("demo")
public class DemoAuthService {

	private static final Logger log = LoggerFactory.getLogger(DemoAuthService.class);
	private final UserRepository users;
	private final DemoSessionService sessions;
	private final DemoSessionStore store;
	private final JwtUtil jwt;
	private final DemoSeedService seed;
	private final TransactionTemplate transaction;
	private final Clock clock;
	private final SecureRandom random;

	@Autowired
	public DemoAuthService(UserRepository users, DemoSessionService sessions, DemoSessionStore store,
		JwtUtil jwt, DemoSeedService seed, PlatformTransactionManager transactionManager) {
		this(users, sessions, store, jwt, seed, transactionManager, Clock.systemUTC(), new SecureRandom());
	}

	DemoAuthService(UserRepository users, DemoSessionService sessions, DemoSessionStore store,
		JwtUtil jwt, DemoSeedService seed, PlatformTransactionManager transactionManager, Clock clock, SecureRandom random) {
		this.users = users;
		this.sessions = sessions;
		this.store = store;
		this.jwt = jwt;
		this.seed = seed;
		this.clock = clock;
		this.random = random;
		this.transaction = new TransactionTemplate(transactionManager);
		// Returning tokens requires this transaction's commit, even if a caller has an outer transaction.
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public DemoSessionService.IssuedTokens login(String existingRefreshToken) {
		if (existingRefreshToken != null) rejectExistingCookie(existingRefreshToken);
		// One visit owns one Korean calendar anchor, including when installation crosses midnight.
		LocalDate anchor = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"));
		AtomicReference<String> createdSid = new AtomicReference<>();
		try {
			DemoSessionService.IssuedTokens issued = transaction.execute(status -> {
				byte[] identifier = new byte[16];
				random.nextBytes(identifier);
				String id = HexFormat.of().formatHex(identifier);
				User user = users.saveAndFlush(User.createUser("demo-" + id + "@moneytoad.invalid",
					"데모 방문자 " + id.substring(0, 8)));
				seed.install(user, anchor);
				DemoSessionService.IssuedTokens tokens = sessions.startForUser(user.getId());
				createdSid.set(verifiedRefresh(tokens.refreshToken()).sid());
				return tokens;
			});
			if (issued == null) throw new IllegalStateException("DEMO_LOGIN_RESULT_UNAVAILABLE");
			return issued;
		} catch (RuntimeException failure) {
			if (createdSid.get() != null) {
				try {
					sessions.revoke(createdSid.get());
				} catch (RuntimeException compensationFailure) {
					// An unconfirmed Redis result is never retried or reported as a successful login.
					log.error("DEMO_LOGIN_COMPENSATION_FAILED");
				}
			}
			throw failure;
		}
	}

	public DemoSessionService.IssuedTokens reissue(String refreshToken) {
		JwtUtil.DemoClaims claims = verifiedRefresh(refreshToken);
		if (!users.existsById(claims.userId())) throw new DemoAuthException(DEMO_SESSION_INVALID);
		return sessions.refresh(refreshToken);
	}

	public void logout(DemoSessionGuard.AuthorizedSession session) {
		if (session == null) throw new DemoAuthException(DEMO_SESSION_INVALID);
		sessions.revoke(session.sid());
	}

	private void rejectExistingCookie(String refreshToken) {
		JwtUtil.DemoClaims claims = verifiedRefresh(refreshToken);
		DemoSessionStore.SessionIdentity identity = store.findActive(claims.sid())
			.orElseThrow(() -> new DemoAuthException(DEMO_SESSION_INVALID));
		if (identity.userId() != claims.userId() || !identity.expiresAt().equals(claims.expiresAt())
			|| !users.existsById(claims.userId())) {
			throw new DemoAuthException(DEMO_SESSION_INVALID);
		}
		// This is only a duplicate-login refusal, never a refresh or an authentication success.
		throw new ResponseStatusException(HttpStatus.CONFLICT, "DEMO_SESSION_ALREADY_ACTIVE");
	}

	private JwtUtil.DemoClaims verifiedRefresh(String refreshToken) {
		try {
			return jwt.validateDemoRefreshToken(jwt.parse(refreshToken), clock.instant());
		} catch (JwtException | IllegalArgumentException | NullPointerException invalid) {
			throw new DemoAuthException(INVALID_DEMO_TOKEN);
		}
	}
}
