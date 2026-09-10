package com.potg.don.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.potg.don.user.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {
	Optional<User> findByEmail(String email);
}
