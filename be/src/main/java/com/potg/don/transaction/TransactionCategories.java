package com.potg.don.transaction;

import java.util.Set;

import com.potg.don.transaction.exception.InvalidTransactionCategoryException;

/** Transaction editing accepts all Chart choices, including the non-leak insurance/tax category. */
public final class TransactionCategories {

	private static final Set<String> EDITABLE = Set.of(
		"식비", "카페", "마트 / 편의점", "문화생활", "교통 / 차량", "패션 / 미용",
		"생활용품", "주거 / 통신", "건강 / 병원", "교육", "경조사 / 회비", "보험 / 세금", "기타"
	);

	private TransactionCategories() {
	}

	public static String requireEditable(String category) {
		if (category == null || !EDITABLE.contains(category)) {
			throw new InvalidTransactionCategoryException();
		}
		return category;
	}

	/** Match the existing SQL COALESCE(NULLIF(category, ''), '기타'); do not broaden legacy policy. */
	public static String normalizeLegacy(String category) {
		return category == null || category.isEmpty() ? "기타" : category;
	}
}
