package com.potg.don.user.dto.response;

import com.potg.don.user.entity.User;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UserResponse {
	private String name;
	private String email;
	private String gender;
	private Integer age;

	public static UserResponse from(User user) {
		return UserResponse.builder()
			.name(user.getName())
			.email(user.getEmail())
			.gender(user.getGender())
			.age(user.getAge())
			.build();
	}
}
