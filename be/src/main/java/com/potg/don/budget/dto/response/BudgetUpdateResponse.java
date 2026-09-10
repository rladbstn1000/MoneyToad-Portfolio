package com.potg.don.budget.dto.response;

import com.potg.don.budget.entity.Budget;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BudgetUpdateResponse {
	private Long budgetId;
	private Integer budget;

	public static BudgetUpdateResponse from(Budget budget) {
		return BudgetUpdateResponse.builder()
			.budgetId(budget.getId())
			.budget(budget.getAmount())
			.build();
	}
}
