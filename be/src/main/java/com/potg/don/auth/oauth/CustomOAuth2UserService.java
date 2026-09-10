package com.potg.don.auth.oauth;

import java.util.Map;

import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

	private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();

	@Override
	public OAuth2User loadUser(OAuth2UserRequest req) throws OAuth2AuthenticationException {
		OAuth2User user = delegate.loadUser(req);
		Map<String, Object> attrs = user.getAttributes();

		// SSAFY userInfo 예시: { userId, email, name, ... }
		String email = (String)attrs.getOrDefault("email", "");
		String name = (String)attrs.getOrDefault("name", "");

		if (email == null || email.isBlank()) {
			throw new OAuth2AuthenticationException(new OAuth2Error("invalid_userinfo"),
				"email is required from SSAFY userInfo");
		}
		return new CustomOAuth2User(email, name, attrs);
	}
}
