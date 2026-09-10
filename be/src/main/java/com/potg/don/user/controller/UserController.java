package com.potg.don.user.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.potg.don.auth.entity.CustomUserDetails;
import com.potg.don.user.dto.request.UpdateProfileRequest;
import com.potg.don.user.dto.response.UserResponse;
import com.potg.don.user.entity.User;
import com.potg.don.user.service.UserService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/users")
public class UserController {

	private final UserService userService;

	@GetMapping("")
	public ResponseEntity<UserResponse> getUser(@AuthenticationPrincipal CustomUserDetails me) {
		User user = userService.getUser(me.getUserId());
		return ResponseEntity.ok(UserResponse.from(user));
	}

	@PatchMapping("")
	public ResponseEntity<UserResponse> updateUser(@AuthenticationPrincipal CustomUserDetails me,
		@RequestBody UpdateProfileRequest req) {
		User updatedUser = userService.updateUser(me.getUserId(), req);
		return ResponseEntity.ok(UserResponse.from(updatedUser));
	}

	@GetMapping("/file")
	public ResponseEntity<String> getFileId(@AuthenticationPrincipal CustomUserDetails me) {
		User user = userService.getUser(me.getUserId());
		return ResponseEntity.ok(user.getFileId());
	}
}
