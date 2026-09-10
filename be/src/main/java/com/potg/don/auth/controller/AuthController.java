package com.potg.don.auth.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.potg.don.auth.dto.AccessTokenResponse;
import com.potg.don.auth.dto.TokenResponse;
import com.potg.don.auth.entity.CustomUserDetails;
import com.potg.don.auth.jwt.JwtUtil;
import com.potg.don.auth.oauth.CookieUtil;
import com.potg.don.auth.service.AuthService;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {
	private final AuthService authService;
	private final JwtUtil jwtUtil;

	/**
	 * Refresh 토큰으로 Access 토큰을 재발급합니다.
	 */
	@PostMapping("/reissue")
	public ResponseEntity<AccessTokenResponse> reissue(@CookieValue("refreshToken") String refreshToken,
		HttpServletResponse response) {
		TokenResponse tokenResponse = authService.reissueTokens(refreshToken);
		Cookie refreshTokenCookie = CookieUtil.createCookie("refreshToken", tokenResponse.refreshToken(),
			(int)jwtUtil.getRefreshTtlSeconds() // 쿠키 만료 시간 설정
		);
		response.addCookie(refreshTokenCookie);
		return ResponseEntity.ok(new AccessTokenResponse(tokenResponse.accessToken()));
	}

	/**
	 * 로그아웃: Redis에 저장된 Refresh Token을 삭제합니다.
	 */
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@AuthenticationPrincipal CustomUserDetails userDetails) {
		authService.logout(userDetails.getUserId());
		return ResponseEntity.ok().build();
	}
}
