package com.potg.don.auth.jwt;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potg.don.auth.entity.CustomUserDetails;
import com.potg.don.auth.demo.DemoAuthException;
import com.potg.don.auth.demo.DemoSessionGuard;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final JwtUtil jwtUtil;
	private final UserRepository userRepository;
	private final DemoSessionGuard demoGuard;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final AntPathMatcher pathMatcher = new AntPathMatcher();

	public JwtAuthenticationFilter(JwtUtil jwtUtil, UserRepository userRepository,
		Environment environment, ObjectProvider<DemoSessionGuard> guards) {
		this.jwtUtil = jwtUtil;
		this.userRepository = userRepository;
		// Mode comes from the actual profile, never from optional bean availability.
		this.demoGuard = environment.acceptsProfiles(Profiles.of("demo")) ? guards.getObject() : null;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {

		// Readiness is read-only; public auth POSTs have their own browser/cookie boundary.
		if (demoGuard != null && request.getRequestURI().startsWith(request.getContextPath() + "/auth/demo/")) {
			response.setHeader("Cache-Control", "no-store");
		}
		if (isDemoPublicRequest(request) || isPublicUri(request.getRequestURI())) {
			filterChain.doFilter(request, response);
			return;
		}

		String token = resolveToken(request);

		if (token != null) {
			try {
				// ✅ 2. 이제 ExpiredJwtException에 대한 특별 처리가 필요 없다.
				//    보호된 경로에 대한 요청은 토큰이 무조건 유효해야 한다.
				Jws<Claims> verified;
				try {
					verified = jwtUtil.parse(token);
				} catch (JwtException | IllegalArgumentException | NullPointerException invalid) {
					if (demoGuard != null) throw new DemoAuthException(DemoAuthException.Reason.INVALID_DEMO_TOKEN);
					throw invalid;
				}
				Claims claims = verified.getPayload();
				if (demoGuard != null) {
					setAuthentication(claims, demoGuard.requireActive(verified));
				} else if ("ACCESS".equals(claims.get("typ", String.class))) {
					setAuthentication(claims, null);
				}
			} catch (DemoAuthException failure) {
				SecurityContextHolder.clearContext();
				boolean unavailable = failure.reason() == DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE;
				response.setStatus(unavailable ? 503 : 401);
				response.setContentType(MediaType.APPLICATION_JSON_VALUE);
				response.setCharacterEncoding("UTF-8");
				objectMapper.writeValue(response.getWriter(), Map.of("status", unavailable ? 503 : 401,
					"error", unavailable ? "Service Unavailable" : "Unauthorized",
					"message", unavailable ? "인증 서비스를 사용할 수 없습니다." : "유효하지 않은 인증 정보입니다."));
				return;
			} catch (JwtException | IllegalArgumentException | NullPointerException e) {
				if (demoGuard != null) {
					sendDemoServerError(response);
					return;
				}
				// 토큰 관련 모든 예외는 401 에러로 처리
				log.warn("Invalid JWT Token: {}. URI: {}", e.getMessage(), request.getRequestURI());
				sendErrorResponse(response, "유효하지 않은 토큰입니다.");
				return; // 필터 체인 중단
			} catch (DataAccessResourceFailureException | TransientDataAccessResourceException | QueryTimeoutException
				| org.springframework.transaction.CannotCreateTransactionException
				| org.springframework.transaction.TransactionTimedOutException
				| org.springframework.transaction.TransactionSystemException
				| org.springframework.transaction.UnexpectedRollbackException unavailable) {
				if (demoGuard == null) throw unavailable;
				SecurityContextHolder.clearContext();
				response.setStatus(503);
				response.setContentType(MediaType.APPLICATION_JSON_VALUE);
				response.setCharacterEncoding("UTF-8");
				objectMapper.writeValue(response.getWriter(), Map.of("status", 503, "error", "Service Unavailable",
					"message", "인증 서비스를 사용할 수 없습니다."));
				return;
			} catch (RuntimeException unexpected) {
				if (demoGuard == null) throw unexpected;
				sendDemoServerError(response);
				return;
			}
		} else {
			// ✅ 3. 보호된 경로에 토큰 없이 접근한 경우 에러 처리
			if (demoGuard == null) log.warn("No JWT Token found. URI: {}", request.getRequestURI());
			else log.warn("DEMO_AUTH_TOKEN_REQUIRED");
			sendErrorResponse(response, "인증 토큰이 필요합니다.");
			return;
		}

		filterChain.doFilter(request, response);
	}

	private boolean isDemoPublicRequest(HttpServletRequest request) {
		if (demoGuard == null) return false;
		String path = request.getRequestURI().substring(request.getContextPath().length());
		if ("GET".equals(request.getMethod()) && "/auth/demo/ready".equals(path)) return true;
		return "POST".equals(request.getMethod())
			&& ("/auth/demo/login".equals(path) || "/auth/demo/reissue".equals(path));
	}

	private boolean isPublicUri(String uri) {
		// SecurityConfig에 정의된 public 경로 목록과 동일하게 관리
		String[] publicUris = {
			"/api/login/**",
			"/api/oauth2/**",
			"/api/swagger-ui/**",     // swagger-ui 하위 모든 경로
			"/api/swagger-ui.html",   // ✅ swagger-ui.html 파일 자체를 추가
			"/api/v3/api-docs/**",    // swagger api docs
			"/api/test",
			"/api/auth/reissue"
		};

		for (String publicUri : publicUris) {
			if (pathMatcher.match(publicUri, uri)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Claims 정보를 바탕으로 SecurityContext에 인증 정보를 저장하는 메소드
	 */
	private void setAuthentication(Claims claims, DemoSessionGuard.AuthorizedSession authorized) {
		Long userId = Long.valueOf(claims.getSubject());
		User user = userRepository.findById(userId)
			.orElseThrow(() -> demoGuard != null
				? new DemoAuthException(DemoAuthException.Reason.DEMO_SESSION_INVALID)
				: new NullPointerException("User not found with id: " + userId));

		CustomUserDetails userDetails = new CustomUserDetails(
			user.getId(),
			user.getEmail(),
			"", // Password는 민감 정보이므로 비워둠
			Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
		);

		UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
			userDetails,
			null,
			userDetails.getAuthorities()
		);
		if (authorized != null) authentication.setDetails(authorized);
		SecurityContextHolder.getContext().setAuthentication(authentication);
		if (authorized != null) log.info("DEMO_AUTHENTICATED");
		else log.info("Successfully authenticated user: {}", userDetails.getUsername());
	}

	private String resolveToken(HttpServletRequest request) {
		String bearerToken = request.getHeader("Authorization");
		if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
			return bearerToken.substring(7).trim();
		}
		return null;
	}

	private void sendErrorResponse(HttpServletResponse response, String message) throws IOException {
		// 🚨 CORS 헤더 설정은 SecurityConfig의 corsConfigurationSource에서 중앙 관리하는 것이 더 좋습니다.
		//    다만, 현재 구조를 유지하기 위해 이 코드를 남겨둡니다.
		if (demoGuard == null) {
			response.setHeader("Access-Control-Allow-Origin", "*"); // Original mode behavior retained.
			response.setHeader("Access-Control-Allow-Credentials", "true");
		}

		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");

		Map<String, Object> body = Map.of(
			"status", 401,
			"error", "Unauthorized",
			"message", message
		);
		objectMapper.writeValue(response.getWriter(), body);
	}

	private void sendDemoServerError(HttpServletResponse response) throws IOException {
		SecurityContextHolder.clearContext();
		log.error("DEMO_AUTH_INTERNAL_ERROR");
		response.setStatus(500);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(response.getWriter(), Map.of("status", 500, "error", "Internal Server Error",
			"message", "서버 내부 오류가 발생했습니다."));
	}
}
