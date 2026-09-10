package com.potg.don.transaction.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class MonthlyPeerSpendingResponse {
	private String date;      // "yyyy-MM"
	private int totalAmount;  // 월 합계 (또래)
}
