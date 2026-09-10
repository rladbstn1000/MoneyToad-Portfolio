package com.potg.don.budget.dto.response;

import com.potg.don.budget.entity.Budget;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BudgetResponse {
	private Long id;
	private Integer budget;
	private Integer initialBudget;
	private Integer spending;
	private String category;

	public static BudgetResponse from(Budget budget, Integer spending) {
		return BudgetResponse.builder()
			.id(budget.getId())
			.budget(nz(budget.getAmount()))
			.initialBudget(nz(budget.getInitialAmount()))
			.spending(spending)
			.category(budget.getCategory())
			.build();
	}

	public static BudgetResponse of(String category, Integer budget, Integer initialBudget, Integer spending) {
		return BudgetResponse.builder()
			.category(category)
			.budget(nz(budget))
			.initialBudget(nz(initialBudget))
			.spending(spending)
			.build();
	}

	private static int nz(Integer v) { return v == null ? 0 : v; }
}
