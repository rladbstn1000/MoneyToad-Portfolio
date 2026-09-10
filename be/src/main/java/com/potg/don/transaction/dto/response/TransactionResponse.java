package com.potg.don.transaction.dto.response;

import java.time.LocalDateTime;

import com.potg.don.transaction.entity.Transaction;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TransactionResponse {
	private Long id;
	private LocalDateTime transactionDateTime;
	private int amount;
	private String merchantName;
	private String category;

	public static TransactionResponse from(Transaction transaction) {
		return TransactionResponse.builder()
			.id(transaction.getId())
			.transactionDateTime(transaction.getTransactionDateTime())
			.amount(transaction.getAmount())
			.merchantName(transaction.getMerchantName())
			.category(transaction.getCategory())
			.build();
	}
}
