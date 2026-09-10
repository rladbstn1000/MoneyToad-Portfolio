package com.potg.don.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConfig {
	@Bean
	public StringRedisTemplate stringRedisTemplate(org.springframework.data.redis.connection.RedisConnectionFactory f) {
		return new StringRedisTemplate(f);
	}
}
