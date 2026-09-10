package com.potg.don.card.entity;

import com.potg.don.card.dto.request.CardRequest;
import com.potg.don.global.entity.BaseTimeEntity;
import com.potg.don.user.entity.User;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

@Entity
@Getter
@Table(name = "cards", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id"}))
public class Card extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	private String cardNo;

	private String cvc;

	public static Card createCard(CardRequest cardRequest, User user) {
		Card card = new Card();
		card.user = user;
		card.cardNo = cardRequest.getCardNo();
		card.cvc = cardRequest.getCvc();
		return card;
	}

	public Card updateCard(CardRequest cardRequest) {
		this.cardNo = cardRequest.getCardNo();
		this.cvc = cardRequest.getCvc();
		return this;
	}
}
