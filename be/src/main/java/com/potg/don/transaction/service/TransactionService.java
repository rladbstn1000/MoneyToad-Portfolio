package com.potg.don.transaction.service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.potg.don.card.repository.CardRepository;
import com.potg.don.global.util.LeakCategories;
import com.potg.don.transaction.dto.request.UpdateCategoryRequest;
import com.potg.don.transaction.dto.response.MonthlyCategorySpendingResponse;
import com.potg.don.transaction.dto.response.TransactionResponse;
import com.potg.don.transaction.entity.Transaction;
import com.potg.don.transaction.projection.CategoryTotalProjection;
import com.potg.don.transaction.projection.MonthlyCategoryTotalRow;
import com.potg.don.transaction.repository.TransactionRepository;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionService {

	private static final ZoneId ZONE_SEOUL = ZoneId.of("Asia/Seoul");
	private static final Set<String> EXCLUDED_FOR_LEAK = Set.of("보험 / 세금");

	private final TransactionRepository transactionRepository;
	private final CardRepository cardRepository;

	/**
	 * GET /transactions/{year}/{month}
	 * 해당 월의 전체 거래 목록
	 * Controller 반환 타입: List<TransactionResponse>
	 */
	public List<TransactionResponse> getMonthlyTransactions(Long userId, Integer year, Integer month) {
		YearMonth ym = YearMonth.of(year, month);
		LocalDateTime start = ym.atDay(1).atStartOfDay();
		LocalDateTime end = ym.plusMonths(1).atDay(1).atStartOfDay();

		List<Transaction> list = transactionRepository.findAllByUserAndBetween(userId, start, end);

		return list.stream()
			.sorted(Comparator.comparing(Transaction::getTransactionDateTime).reversed())
			.map(this::toTransactionResponse)
			.toList();
	}

	/**
	 * GET /transactions/{year}/{month}/categories
	 * 해당 월 카테고리별 합계
	 * Controller 메소드명에 맞춰 intentionally 'Spendig' 철자 유지
	 * 반환: List<MonthlyCategorySpendingResponse>
	 */
	public List<MonthlyCategorySpendingResponse> getMonthlyCategorySpending(Long userId, Integer year, Integer month) {
		YearMonth ym = YearMonth.of(year, month);
		LocalDateTime start = ym.atDay(1).atStartOfDay();
		LocalDateTime end = ym.plusMonths(1).atDay(1).atStartOfDay();

		List<CategoryTotalProjection> rows =
			transactionRepository.aggregateCategoryTotals(userId, start, end);

		return rows.stream()
			.map(r -> new MonthlyCategorySpendingResponse(
				Optional.ofNullable(r.getCategory()).orElse("미분류"),
				Math.toIntExact(Optional.ofNullable(r.getTotal()).orElse(0L))
			))
			.toList();
	}

	/**
	 * PATCH /transactions/{transactionId}/category
	 * 카테고리 수정
	 * Controller 반환 타입: TransactionResponse
	 */
	@Transactional
	public TransactionResponse updateTransactionCategory(Long userId, Long transactionId,
		UpdateCategoryRequest request) {
		Transaction tx = transactionRepository.findByIdAndCard_User_Id(transactionId, userId)
			.orElseThrow(() -> new NoSuchElementException("거래내역을 찾을 수 없거나 권한이 없습니다."));
		tx.update(request.getCategory());
		return TransactionResponse.from(transactionRepository.save(tx));
	}

	private TransactionResponse toTransactionResponse(Transaction t) {
		return TransactionResponse.builder()
			.id(t.getId())
			.transactionDateTime(t.getTransactionDateTime())
			.amount(t.getAmount())
			.merchantName(t.getMerchantName())
			.category(t.getCategory())
			.build();
	}

	private static boolean includeForLeak(String category) {
		return category != null && !EXCLUDED_FOR_LEAK.contains(category);
	}

	/**
	 * 최근 12개월 범위의 월별-카테고리별 지출 합계 맵
	 * - 키: YearMonth
	 * - 값: (카테고리 -> 합계)
	 * - 카테고리: LeakCategories.ALLOWED(12개)만 포함 (보험/세금 등 제외)
	 * - null/빈 카테고리는 "기타"로 정규화
	 */
	public Map<YearMonth, Map<String, Integer>> getSpentMapByMonthExcludingOthers(
		Long userId, YearMonth startYm, YearMonth endYm
	) {
		LocalDateTime start = startYm.atDay(1).atStartOfDay();
		LocalDateTime end = endYm.plusMonths(1).atDay(1).atStartOfDay(); // [start, end)

		Long cardId = cardRepository.findByUserId(userId)
			.orElseThrow(EntityNotFoundException::new)
			.getId();

		List<MonthlyCategoryTotalRow> rows =
			transactionRepository.aggregateCategoryTotalsByMonthForCardFiltered(
				cardId, start, end, LeakCategories.ALLOWED
			);

		Map<YearMonth, Map<String, Integer>> result = new HashMap<>();
		for (MonthlyCategoryTotalRow r : rows) {
			YearMonth ym = YearMonth.of(r.getY(), r.getM());
			String mapped = LeakCategories.mapToAllowedOrNull(r.getCategory()); // '기타' 포함 12개
			if (mapped == null)
				continue; // 방어적

			int total = Math.toIntExact(Objects.requireNonNullElse(r.getTotal(), 0L));
			result.computeIfAbsent(ym, k -> new HashMap<>())
				.merge(mapped, total, Integer::sum);
		}
		return result;
	}
}