package com.potg.don.budget.controller;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.potg.don.auth.entity.CustomUserDetails;
import com.potg.don.budget.dto.request.BudgetUpdateRequest;
import com.potg.don.budget.dto.response.BudgetResponse;
import com.potg.don.budget.dto.response.BudgetUpdateResponse;
import com.potg.don.budget.dto.response.IsLeakedResponse;
import com.potg.don.budget.entity.Budget;
import com.potg.don.budget.service.BudgetService;
import com.potg.don.transaction.dto.response.TransactionResponse;
import com.potg.don.transaction.service.TransactionService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/budgets")
public class BudgetController {
	private final BudgetService budgetService;
	private final TransactionService transactionService;

	@GetMapping("")
	public ResponseEntity<List<IsLeakedResponse>> getRecent12MonthsLeaks(
		@AuthenticationPrincipal CustomUserDetails user) {
		Long userId = user.getUserId();
		YearMonth endYm = YearMonth.now();           // 예: 2025-09
		YearMonth startYm = endYm.minusMonths(11);   // 2024-10 ~ 2025-09

		List<IsLeakedResponse> result = new ArrayList<>(12);

		for (YearMonth ym = startYm; !ym.isAfter(endYm); ym = ym.plusMonths(1)) {
			int year = ym.getYear();
			int month = ym.getMonthValue();

			// 1) 월별 데이터 조회 (월 화면과 동일 소스)
			List<Budget> budgets = budgetService.getMonthlyBudgets(userId, year, month);
			List<TransactionResponse> transactions =
				transactionService.getMonthlyTransactions(userId, year, month);

			// 2) 화면에서 쓰는 것과 동일한 규칙(visible/HIDDEN + CATEGORY_ORDER)으로 12개 카테고리 응답 생성
			List<BudgetResponse> monthly = toBudgetResponses(budgets, transactions);

			// 3) 하나라도 지출이 예산을 넘으면 누수
			boolean leaked = monthly.stream().anyMatch(br -> {
				int budget = br.getBudget() == null ? 0 : br.getBudget();
				int spending = br.getSpending() == null ? 0 : br.getSpending();
				return spending > budget;
			});

			result.add(IsLeakedResponse.from(ym, leaked));
		}

		return ResponseEntity.ok(result);
	}

	@PatchMapping("")
	public ResponseEntity<BudgetUpdateResponse> updateBudget(@AuthenticationPrincipal CustomUserDetails user,
		@RequestBody
		BudgetUpdateRequest updateBudgetRequest) {
		Budget budget = budgetService.updateBudget(user.getUserId(), updateBudgetRequest);
		return ResponseEntity.ok(BudgetUpdateResponse.from(budget));
	}

	@GetMapping("/{year}/{month}")
	public ResponseEntity<List<BudgetResponse>> getMonthlyBudget(@AuthenticationPrincipal CustomUserDetails user,
		@PathVariable Integer year, @PathVariable Integer month) {

		List<Budget> budgets = budgetService.getMonthlyBudgets(user.getUserId(), year, month);
		List<TransactionResponse> transactionResponses = transactionService.getMonthlyTransactions(user.getUserId(),
			year, month);

		return ResponseEntity.ok(toBudgetResponses(budgets, transactionResponses));
	}

	// 0) 숨길 카테고리 집합
	private static final Set<String> HIDDEN_CATEGORIES = Set.of("보험 / 세금");

	private static boolean visible(String category) {
		return !HIDDEN_CATEGORIES.contains(category);
	}

	// 고정 순서 정의에서 '보험 / 세금' 제거 (이미 제거되어 있음)
	private static final List<String> CATEGORY_ORDER = List.of(
		"식비", "카페", "마트 / 편의점", "문화생활", "교통 / 차량", "패션 / 미용",
		"생활용품", "주거 / 통신", "건강 / 병원", "교육", "경조사 / 회비", "기타"
	);

	private static List<BudgetResponse> toBudgetResponses(
		List<Budget> budgets,
		List<TransactionResponse> transactionResponses
	) {
		// 1) 지출 합계 (숨김 제외)
		Map<String, Integer> spentByCategory = transactionResponses.stream()
			.filter(tr -> visible(tr.getCategory()))
			.collect(Collectors.groupingBy(TransactionResponse::getCategory,
				Collectors.summingInt(TransactionResponse::getAmount)));

		// 2) 예산 맵 (숨김 제외)
		Map<String, Budget> budgetByCategory = budgets.stream()
			.filter(b -> visible(b.getCategory()))
			.collect(Collectors.toMap(
				Budget::getCategory,
				b -> b,
				(a, b) -> a
			));

		// 3) "항상 12개 카테고리"를 고정 순서대로 생성
		return CATEGORY_ORDER.stream()
			.map(cat -> {
				int spent = spentByCategory.getOrDefault(cat, 0);
				Budget budget = budgetByCategory.get(cat);

				if (budget != null) {
					// 기존 예산이 있으면 그대로 사용
					return BudgetResponse.from(budget, spent);
				} else {
					// 예산이 없으면 0 예산으로 생성 (두 가지 방법 중 택1)
					// [방법 A] BudgetResponse에 팩토리 메서드 추가 가능할 때
					return BudgetResponse.of(cat, 0,0, spent);
				}
			})
			.collect(Collectors.toList());
	}
}
