package com.potg.don.card.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.potg.don.card.entity.Card;

public interface CardRepository extends JpaRepository<Card, Long> {
	Optional<Card> findByUserId(Long userId);

	void deleteByUserId(Long userId);
}