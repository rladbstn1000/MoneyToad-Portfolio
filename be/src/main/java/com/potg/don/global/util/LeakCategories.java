package com.potg.don.global.util;

import java.util.List;

public final class LeakCategories {
	private LeakCategories() {
	}

	// 누수판단에 사용할 12개만 허용 (보험/세금 제외)
	public static final List<String> ALLOWED = List.of(
		"식비", "카페", "마트 / 편의점", "문화생활", "교통 / 차량", "패션 / 미용",
		"생활용품", "주거 / 통신", "건강 / 병원", "교육", "경조사 / 회비", "기타"
	);

	public static String mapToAllowedOrNull(String raw) {
		String c = (raw == null || raw.isBlank()) ? "기타" : raw;
		return ALLOWED.contains(c) ? c : null; // 허용 안 되는 건 제외
	}
}