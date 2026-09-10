package com.potg.don.budget.dto.response;

import java.time.YearMonth;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class IsLeakedResponse {
	private YearMonth budgetDate;
	private boolean leaked;

	public static IsLeakedResponse from(YearMonth budgetDate, boolean isLeaked) {
		return IsLeakedResponse.builder()
			.budgetDate(budgetDate)
			.leaked(isLeaked)
			.build();
	}
}
