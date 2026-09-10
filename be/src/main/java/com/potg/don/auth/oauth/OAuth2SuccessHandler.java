package com.potg.don.auth.oauth;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.potg.don.auth.jwt.JwtUtil;
import com.potg.don.auth.jwt.RefreshTokenStore;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

	private final UserRepository userRepository;
	private final JwtUtil jwtUtil;
	private final RefreshTokenStore refreshStore;

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
		Authentication authentication) throws IOException, ServletException {
		CustomOAuth2User o = (CustomOAuth2User)authentication.getPrincipal();

		// 1. 사용자 정보로 DB에서 유저 조회 또는 신규 생성
		User user = userRepository.findByEmail(o.getEmail())
			.orElseGet(() -> userRepository.save(User.createUser(o.getEmail(), o.getName())));

		// 2. Access Token, Refresh Token 생성
		String accessToken = jwtUtil.createAccessToken(user.getId(), user.getEmail());
		String refreshToken = jwtUtil.createRefreshToken(user.getId());

		// 3. Refresh Token을 Redis에 저장
		refreshStore.save(user.getId(), refreshToken, jwtUtil.getRefreshTtlSeconds());

		// 4. Refresh Token을 HttpOnly 쿠키에 담기
		Cookie refreshTokenCookie = CookieUtil.createCookie(
			"refreshToken",
			refreshToken,
			(int)jwtUtil.getRefreshTtlSeconds()
		);
		response.addCookie(refreshTokenCookie); // response에 쿠키 추가

		// 5. Access Token을 쿼리 파라미터에 담아 프론트엔드로 리디렉션
		String targetUrl = UriComponentsBuilder.fromUriString(
				"http://j13a409.p.ssafy.io:3002/auth/callback") // 리디렉션될 프론트엔드 URL
			.queryParam("accessToken", accessToken)
			.build().toUriString();

		// 6. 지정된 URL로 리디렉션 실행
		getRedirectStrategy().sendRedirect(request, response, targetUrl);
	}
}