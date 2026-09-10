package com.potg.don.auth.demo;

import java.time.Instant;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@Profile("demo")
@RequestMapping("/auth/demo")
public class DemoAuthController {

	private final DemoAuthService auth;
	private final DemoRefreshCookie cookies;

	public DemoAuthController(DemoAuthService auth, DemoRefreshCookie cookies) {
		this.auth = auth;
		this.cookies = cookies;
	}

	// The demo interceptor alone validates/consumes the bounded empty-object body. No DTO binds input.
	@PostMapping("/login")
	public ResponseEntity<AccessTokenResponse> login(HttpServletRequest request) {
		DemoSessionService.IssuedTokens tokens = auth.login(refreshCookie(request, false));
		return tokenResponse(HttpStatus.CREATED, tokens);
	}

	@PostMapping("/reissue")
	public ResponseEntity<AccessTokenResponse> reissue(HttpServletRequest request) {
		DemoSessionService.IssuedTokens tokens = auth.reissue(refreshCookie(request, true));
		return tokenResponse(HttpStatus.OK, tokens);
	}

	@GetMapping("/session")
	public ResponseEntity<SessionResponse> session(Authentication authentication) {
		DemoSessionGuard.AuthorizedSession session = authorized(authentication);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(new SessionResponse(true, session.expiresAt()));
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(Authentication authentication) {
		auth.logout(authorized(authentication));
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
			.header(HttpHeaders.SET_COOKIE, cookies.delete().toString()).build();
	}

	private ResponseEntity<AccessTokenResponse> tokenResponse(HttpStatus status,
		DemoSessionService.IssuedTokens tokens) {
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
			.header(HttpHeaders.SET_COOKIE, cookies.create(tokens.refreshToken(), tokens.expiresAt()).toString())
			.body(new AccessTokenResponse(tokens.accessToken()));
	}

	private static DemoSessionGuard.AuthorizedSession authorized(Authentication authentication) {
		if (authentication == null || !authentication.isAuthenticated()
			|| !(authentication.getDetails() instanceof DemoSessionGuard.AuthorizedSession session)) {
			throw new DemoAuthException(DemoAuthException.Reason.DEMO_SESSION_INVALID);
		}
		return session;
	}

	private static String refreshCookie(HttpServletRequest request, boolean required) {
		String value = null;
		int count = 0;
		Cookie[] incoming = request.getCookies();
		if (incoming != null) {
			for (Cookie cookie : incoming) {
				if (DemoRefreshCookie.NAME.equals(cookie.getName())) {
					count++;
					value = cookie.getValue();
				}
			}
		}
		if (count > 1 || (required && count != 1) || (count == 1 && (value == null || value.isBlank()))) {
			throw new DemoAuthException(DemoAuthException.Reason.INVALID_DEMO_TOKEN);
		}
		return value;
	}

	public record AccessTokenResponse(String accessToken) {
		@Override public String toString() { return "AccessTokenResponse[redacted]"; }
	}

	public record SessionResponse(boolean demo, Instant expiresAt) { }
}
