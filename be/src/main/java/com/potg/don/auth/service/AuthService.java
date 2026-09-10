package com.potg.don.auth.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.potg.don.auth.dto.TokenResponse;
import com.potg.don.auth.jwt.JwtUtil;
import com.potg.don.auth.jwt.RefreshTokenStore;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

	private final JwtUtil jwtUtil;
	private final RefreshTokenStore refreshStore;
	private final UserRepository userRepository;

	@Transactional
	public TokenResponse reissueTokens(String refreshToken) {
		Long userId = jwtUtil.getUserId(refreshToken);
		// 1. Redis에 저장된 RT와 일치하는지, 만료되지는 않았는지 확인합니다.
		validateRefreshToken(userId, refreshToken);

		// 2. 새로운 토큰을 생성합니다.
		// DB를 다시 조회할 필요 없이, 인증된 사용자 정보(userId)로 토큰을 만듭니다.
		User user = userRepository.findById(userId)
			.orElseThrow(() -> new RuntimeException("User not found with id: " + userId));

		String newAccessToken = jwtUtil.createAccessToken(user.getId(), user.getEmail());
		String newRefreshToken = jwtUtil.createRefreshToken(user.getId());

		// 3. Redis에 새로운 Refresh Token을 저장합니다. (토큰 회전)
		refreshStore.save(userId, newRefreshToken, jwtUtil.getRefreshTtlSeconds());

		return new TokenResponse(newAccessToken, newRefreshToken);
	}

	private void validateRefreshToken(Long userId, String refreshToken) {
		String savedRefreshToken = refreshStore.get(userId);

		if (savedRefreshToken == null || !savedRefreshToken.equals(refreshToken) || jwtUtil.isExpired(refreshToken)) {
			throw new SecurityException("유효하지 않거나 만료된 Refresh Token입니다.");
		}
	}

	public void logout(Long userId) {
		// 단순히 Redis에서 토큰을 삭제합니다.
		refreshStore.delete(userId);
	}
}
