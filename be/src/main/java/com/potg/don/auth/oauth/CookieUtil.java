package com.potg.don.auth.oauth;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;

@Component
public class CookieUtil {

	public static Cookie createCookie(String key, String value, int maxAge) {
		Cookie cookie = new Cookie(key, value);
		cookie.setHttpOnly(true);
		cookie.setSecure(true); // HTTPS 환경에서만 전송됩니다. (운영 환경에서는 true로 설정)
		cookie.setPath("/");    // 쿠키가 전송될 경로를 설정합니다.
		cookie.setMaxAge(maxAge);
		return cookie;
	}
}
