package com.potg.don.auth.jwt;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

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
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final JwtUtil jwtUtil;
	private final UserRepository userRepository;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final AntPathMatcher pathMatcher = new AntPathMatcher();

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {

		// ✅ 1. 공개 경로(permitAll)는 필터를 거치지 않고 통과시킨다.
		if (isPublicUri(request.getRequestURI())) {
			filterChain.doFilter(request, response);
			return;
		}

		String token = resolveToken(request);

		if (token != null) {
			try {
				// ✅ 2. 이제 ExpiredJwtException에 대한 특별 처리가 필요 없다.
				//    보호된 경로에 대한 요청은 토큰이 무조건 유효해야 한다.
				Claims claims = jwtUtil.parse(token).getPayload();
				if ("ACCESS".equals(claims.get("typ", String.class))) {
					setAuthentication(claims);
				}
			} catch (JwtException | IllegalArgumentException | NullPointerException e) {
				// 토큰 관련 모든 예외는 401 에러로 처리
				log.warn("Invalid JWT Token: {}. URI: {}", e.getMessage(), request.getRequestURI());
				sendErrorResponse(response, "유효하지 않은 토큰입니다.");
				return; // 필터 체인 중단
			}
		} else {
			// ✅ 3. 보호된 경로에 토큰 없이 접근한 경우 에러 처리
			log.warn("No JWT Token found. URI: {}", request.getRequestURI());
			sendErrorResponse(response, "인증 토큰이 필요합니다.");
			return;
		}

		filterChain.doFilter(request, response);
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
	private void setAuthentication(Claims claims) {
		Long userId = Long.valueOf(claims.getSubject());
		User user = userRepository.findById(userId)
			.orElseThrow(() -> new NullPointerException("User not found with id: " + userId));

		CustomUserDetails userDetails = new CustomUserDetails(
			user.getId(),
			user.getEmail(),
			"", // Password는 민감 정보이므로 비워둠
			Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
		);

		Authentication authentication = new UsernamePasswordAuthenticationToken(
			userDetails,
			null,
			userDetails.getAuthorities()
		);
		SecurityContextHolder.getContext().setAuthentication(authentication);
		log.info("Successfully authenticated user: {}", userDetails.getUsername());
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
		response.setHeader("Access-Control-Allow-Origin", "*"); // 실제 운영에서는 특정 Origin만 허용해야 합니다.
		response.setHeader("Access-Control-Allow-Credentials", "true");

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
}
