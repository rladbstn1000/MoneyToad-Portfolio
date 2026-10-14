package com.potg.don.card.dto.response;

import com.potg.don.card.entity.Card;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CardResponse {
	private Long cardId;
	private String cardNo;

	public static CardResponse from(Card card) {
		return CardResponse.builder()
			.cardId(card.getId())
			.cardNo(card.getCardNo())
			.build();
	}
}
