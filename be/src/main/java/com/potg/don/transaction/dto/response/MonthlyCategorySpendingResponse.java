package com.potg.don.transaction.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyCategorySpendingResponse {
	private String category;
	private int totalAmount;
	private int leakedAmount; // ✅ 추가: 초과분 (spent - budget, 음수면 0)

	// ✅ 기존 서비스 코드 호환용 2-인자 생성자 유지
	public MonthlyCategorySpendingResponse(String category, int totalAmount) {
		this.category = category;
		this.totalAmount = totalAmount;
		this.leakedAmount = 0;
	}
}
