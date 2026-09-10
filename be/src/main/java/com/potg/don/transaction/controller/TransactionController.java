package com.potg.don.transaction.controller;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
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
import com.potg.don.budget.entity.Budget;
import com.potg.don.budget.service.BudgetService;
import com.potg.don.transaction.dto.request.UpdateCategoryRequest;
import com.potg.don.transaction.dto.response.MonthlyCategorySpendingResponse;
import com.potg.don.transaction.dto.response.MonthlyPeerSpendingResponse;
import com.potg.don.transaction.dto.response.MonthlySpendingResponse;
import com.potg.don.transaction.dto.response.TransactionResponse;
import com.potg.don.transaction.service.PeerTransactionStatsService;
import com.potg.don.transaction.service.TransactionService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/transactions")
public class TransactionController {
	private final TransactionService transactionService;
	private final PeerTransactionStatsService statsService;
	private final BudgetService budgetService;

	@GetMapping("")
	public ResponseEntity<List<MonthlySpendingResponse>> getYearlySpending(
		@AuthenticationPrincipal CustomUserDetails user) {
		Long userId = user.getUserId();
		YearMonth endYm = YearMonth.now();
		YearMonth startYm = endYm.minusMonths(11);

		Map<YearMonth, Map<String, Integer>> budgetByMonth =
			budgetService.getBudgetMapByMonthExcludingOthers(userId, startYm, endYm);

		Map<YearMonth, Map<String, Integer>> spentByMonth =
			transactionService.getSpentMapByMonthExcludingOthers(userId, startYm, endYm);

		List<MonthlySpendingResponse> result = new ArrayList<>(12);
		for (YearMonth cursor = startYm; !cursor.isAfter(endYm); cursor = cursor.plusMonths(1)) {
			Map<String, Integer> bm = budgetByMonth.getOrDefault(cursor, Map.of());
			Map<String, Integer> sm = spentByMonth.getOrDefault(cursor, Map.of());
			int totalSpent = sm.values().stream().mapToInt(Integer::intValue).sum();
			// 카테고리는 서비스에서 이미 12개로 정규화되어 옴 (기타 포함)
			Set<String> cats = new HashSet<>(bm.keySet());
			cats.addAll(sm.keySet()); // 예산 없는 카테고리 지출도 누수로 판단하려면 합집합 유지

			boolean leaked = false;
			for (String cat : cats) {
				int budget = bm.getOrDefault(cat, 0);
				int spent = sm.getOrDefault(cat, 0);
				if (spent > budget) {
					leaked = true;
					break;
				}
			}

			result.add(MonthlySpendingResponse.from(cursor.toString(), totalSpent, leaked));
		}
		return ResponseEntity.ok(result);
	}

	@GetMapping("/{year}/{month}")
	public ResponseEntity<List<TransactionResponse>> getMonthlyTransactions(
		@AuthenticationPrincipal CustomUserDetails user,
		@PathVariable Integer year,
		@PathVariable Integer month) {
		return ResponseEntity.ok(transactionService.getMonthlyTransactions(user.getUserId(), year, month));
	}

	@GetMapping("/{year}/{month}/categories")
	public ResponseEntity<List<MonthlyCategorySpendingResponse>> getMonthlyCategorySpending(
		@AuthenticationPrincipal CustomUserDetails user,
		@PathVariable Integer year,
		@PathVariable Integer month) {
		Long userId = user.getUserId();

		// 1) 월별 예산 조회 → 카테고리별 합계 맵
		List<Budget> budgets = budgetService.getMonthlyBudgets(userId, year, month);
		Map<String, Integer> budgetMap = budgets.stream()
			.collect(Collectors.toMap(
				Budget::getCategory,
				b -> b.getAmount() == null ? 0 : b.getAmount(),
				Integer::sum
			));

		// 2) 월별 지출 조회 (보험/세금 포함)
		List<MonthlyCategorySpendingResponse> spendings =
			transactionService.getMonthlyCategorySpending(userId, year, month);

		// 3) leakedAmount 계산
		final String INS_TAX = "보험 / 세금";

		List<MonthlyCategorySpendingResponse> result = spendings.stream()
			.map(r -> {
				String cat = r.getCategory();
				int spent = r.getTotalAmount();
				int leaked = INS_TAX.equals(cat) ? 0
					: Math.max(0, spent - budgetMap.getOrDefault(cat, 0));
				return new MonthlyCategorySpendingResponse(cat, spent, leaked);
			})
			.toList();

		return ResponseEntity.ok(result);
	}

	@PatchMapping("/{transactionId}/category")
	public ResponseEntity<TransactionResponse> updateTransactionCategory(
		@AuthenticationPrincipal CustomUserDetails user,
		@PathVariable Long transactionId,
		@RequestBody @Valid UpdateCategoryRequest request) {
		return ResponseEntity.ok(
			transactionService.updateTransactionCategory(user.getUserId(), transactionId, request));
	}

	@GetMapping("/peer")
	ResponseEntity<List<MonthlyPeerSpendingResponse>> getPeerTransactionStats(
		@AuthenticationPrincipal CustomUserDetails user) {
		return ResponseEntity.ok(statsService.getPeerMonthlySpendingForUser(user.getUserId()));
	}
}
