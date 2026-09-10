package com.potg.don.user.entity;

import com.potg.don.global.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(name = "users")
@Getter
public class User extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 255, unique = true)
	private String email;

	@Column(nullable = false, length = 100)
	private String name;

	// 최초 로그인 때는 빈 값(null)로 두고, 이후 사용자 입력으로 업데이트
	@Column(length = 20)
	private String gender;  // "MALE"/"FEMALE"/"OTHER" 등 자유 문자열

	@Column
	private Integer age;    // nullable

	@Column
	private String fileId;

	public static User createUser(String email, String name) {
		User user = new User();
		user.email = email;
		user.name = name;
		return user;
	}

	public void updateUser(String gender, int age) {
		this.gender = gender;
		this.age = age;
	}

	public void updateFileId(String fileId) {
		this.fileId = fileId;
	}
}
