package com.potg.don.user.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.potg.don.user.dto.request.UpdateProfileRequest;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

	private final UserRepository userRepository;

	/**
	 * 인증된 사용자(me) 기준, 최신 상태로 재조회하여 반환
	 */
	public User getUser(Long userId) {
		return userRepository.findById(userId)
			.orElseThrow(() -> new EntityNotFoundException("ID: " + userId + "에 해당하는 사용자를 찾을 수 없습니다."));
	}

	/**
	 * 성별/나이 업데이트
	 */
	@Transactional
	public User updateUser(Long userId, UpdateProfileRequest req) {
		User userToUpdate = getUser(userId);
		userToUpdate.updateUser(req.gender(), req.age());
		return userRepository.save(userToUpdate);
	}

}
