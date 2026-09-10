package com.potg.don.card.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.potg.don.card.dto.request.CardRequest;
import com.potg.don.card.entity.Card;
import com.potg.don.card.repository.CardRepository;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CardService {

	private final CardRepository cardRepository;
	private final UserRepository userRepository;

	public Card registerCard(Long userId, CardRequest request) {
		User user = userRepository.findById(userId)
			.orElseThrow(() -> new EntityNotFoundException("해당 ID의 사용자를 찾을 수 없습니다: " + userId));
		Card card = Card.createCard(request, user);
		return cardRepository.save(card);
	}

	public Card getCard(Long userId) {
		Card card = cardRepository.findByUserId(userId).orElseThrow(() -> new EntityNotFoundException("카드를 찾을 수 없습니다"));
		return card;
	}

	public Card updateCard(Long userId, CardRequest request) {
		Card cardToUpdate = cardRepository.findByUserId(userId)
			.orElseThrow(() -> new EntityNotFoundException("카드를 찾을 수 없습니다"));
		return cardRepository.save(cardToUpdate.updateCard(request));
	}

	@Transactional
	public void deleteCard(Long userId) {
		Card cardToDelete = cardRepository.findByUserId(userId)
			.orElseThrow(() -> new EntityNotFoundException("삭제할 카드를 찾을 수 없습니다."));
		cardRepository.delete(cardToDelete);
	}
}
