package com.potg.don.auth.jwt;

import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

	private final StringRedisTemplate redis;
	private static final String KEY = "RT:"; // RT:<userId>

	public void save(Long userId, String refreshToken, long ttlSeconds) {
		redis.opsForValue().set(KEY + userId, refreshToken, ttlSeconds, TimeUnit.SECONDS);
	}

	public String get(Long userId) {
		return redis.opsForValue().get(KEY + userId);
	}

	public void delete(Long userId) {
		redis.delete(KEY + userId);
	}
}

