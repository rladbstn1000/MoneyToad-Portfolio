package com.potg.don.auth.entity;

import java.util.Collection;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import lombok.Getter;

@Getter
public class CustomUserDetails implements UserDetails {

	private final Long userId;
	private final String email; // getUsername()이 반환할 필드
	private final String password;
	private final Collection<? extends GrantedAuthority> authorities;

	// User 엔티티를 받아 UserDetails 객체를 생성하는 생성자
	public CustomUserDetails(Long userId, String email, String password,
		Collection<? extends GrantedAuthority> authorities) {
		this.userId = userId;
		this.email = email;
		this.password = password;
		this.authorities = authorities;
	}

	// === UserDetails 인터페이스의 메소드들을 오버라이드 ===

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		// 사용자의 권한 목록을 반환 (예: ROLE_USER, ROLE_ADMIN)
		// 여기서는 간단하게 단일 권한을 반환하도록 설정했으나, User 엔티티에 따라 동적으로 설정 가능
		return authorities;
	}

	@Override
	public String getPassword() {
		// 사용자의 비밀번호를 반환
		return this.password;
	}

	@Override
	public String getUsername() {
		// Spring Security에서 사용자를 식별하는 고유한 값 (여기서는 이메일 사용)
		return this.email;
	}

	// -- 아래 4개 메소드는 계정의 상태를 나타냄 --

	@Override
	public boolean isAccountNonExpired() {
		// 계정이 만료되지 않았는지 (true: 만료 안됨)
		return true;
	}

	@Override
	public boolean isAccountNonLocked() {
		// 계정이 잠기지 않았는지 (true: 잠기지 않음)
		return true;
	}

	@Override
	public boolean isCredentialsNonExpired() {
		// 비밀번호가 만료되지 않았는지 (true: 만료 안됨)
		return true;
	}

	@Override
	public boolean isEnabled() {
		// 계정이 활성화되어 있는지 (true: 활성화됨)
		return true;
	}
}
