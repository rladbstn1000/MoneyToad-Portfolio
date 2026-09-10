package com.potg.don.dummy.service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.potg.don.card.entity.Card;
import com.potg.don.card.repository.CardRepository;
import com.potg.don.dummy.entity.Dummy;
import com.potg.don.dummy.repository.DummyRepository;
import com.potg.don.transaction.entity.Transaction;
import com.potg.don.transaction.repository.TransactionRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DummyService {

	private final DummyRepository poolRepo;
	private final TransactionRepository txRepo;
	private final CardRepository cardRepo;

	// ✅ 규칙: 카테고리별 월별 [최소~최대] 건수 + 금액범위(풀/규칙 중 교집합 사용)
	private record CatRule(int minTx, int maxTx, int minAmt, int maxAmt) {
	}

	private static final Map<String, CatRule> RULES;

	static {
		Map<String, CatRule> m = new LinkedHashMap<>();
		// 보통 프로필: 월별 최소/최대 건수 조정 (금액 범위는 유지)
		m.put("식비", new CatRule(8, 20, 5_000, 30_000));
		m.put("카페", new CatRule(5, 15, 2_000, 9_000));
		m.put("마트 / 편의점", new CatRule(4, 12, 2_000, 60_000));
		m.put("문화생활", new CatRule(1, 4, 7_000, 70_000));
		m.put("교통 / 차량", new CatRule(4, 12, 1_250, 50_000));
		m.put("패션 / 미용", new CatRule(1, 4, 8_000, 150_000));
		m.put("생활용품", new CatRule(1, 6, 3_000, 80_000));
		m.put("주거 / 통신", new CatRule(1, 2, 10_000, 200_000));
		m.put("건강 / 병원", new CatRule(1, 2, 10_000, 200_000));
		m.put("교육", new CatRule(1, 2, 10_000, 300_000));
		m.put("경조사 / 회비", new CatRule(1, 1, 10_000, 200_000));
		m.put("보험 / 세금", new CatRule(1, 1, 10_000, 300_000));
		m.put("기타", new CatRule(1, 4, 1_000, 100_000));
		RULES = java.util.Collections.unmodifiableMap(m);
	}

	// ✅ 월 전체 최소 건수(설정)
	private static final int MONTHLY_MIN_TX = 60; // 보통 프로필 권장: 55~60 중 60 선택

	private static final int MONTH_SPAN = 15;

	/**
	 * 카드에 대해 기존 내역을 삭제하고,
	 * 최근 MONTH_SPAN개월 × 카테고리별 [min~max] 규칙 + 월별 최소 건수 보정으로 더미풀에서 복사 생성합니다.
	 */
	@Transactional
	public void populateFromPool(Long cardId) {
		// 0) 기존 데이터 삭제
		txRepo.deleteByCardId(cardId);

		// 1) 카드 조회
		Card card = cardRepo.findById(cardId).orElseThrow();

		ThreadLocalRandom rng = ThreadLocalRandom.current();
		YearMonth nowYm = YearMonth.now();

		// 2) 최근 MONTH_SPAN개월 반복
		for (int m = 0; m < MONTH_SPAN; m++) {
			YearMonth ym = nowYm.minusMonths(m);
			int daysInMonth = ym.lengthOfMonth();

			// 2-1) 우선 카테고리마다 [min~max]로 개수 샘플링
			Map<String, Integer> plannedCount = new LinkedHashMap<>();
			int total = 0;
			int maxPossible = 0;

			for (var e : RULES.entrySet()) {
				var rule = e.getValue();
				int n = (rule.minTx() == rule.maxTx())
					? rule.minTx()
					: rng.nextInt(rule.minTx(), rule.maxTx() + 1);

				plannedCount.put(e.getKey(), n);
				total += n;
				maxPossible += rule.maxTx();
			}

			// 2-2) 월 전체 최소 건수 보정 (총합이 너무 작으면 maxTx 여유가 있는 카테고리에 +1씩)
			if (MONTHLY_MIN_TX > maxPossible) {
				throw new IllegalStateException("MONTHLY_MIN_TX가 카테고리 max 합보다 큽니다.");
			}
			while (total < MONTHLY_MIN_TX) {
				List<String> candidates = plannedCount.entrySet().stream()
					.filter(en -> en.getValue() < RULES.get(en.getKey()).maxTx())
					.map(Map.Entry::getKey)
					.toList();
				if (candidates.isEmpty())
					break;
				String pick = candidates.get(rng.nextInt(candidates.size()));
				plannedCount.put(pick, plannedCount.get(pick) + 1);
				total++;
			}

			// 2-3) 계획한 개수대로 실제 트랜잭션 생성
			List<Transaction> monthBulk = new ArrayList<>(total);
			for (var e : plannedCount.entrySet()) {
				String category = e.getKey();
				int n = e.getValue();
				var rule = RULES.get(category);

				for (int i = 0; i < n; i++) {
					// 더미풀에서 랜덤 템플릿 1건
					Dummy tpl = poolRepo.pickRandomOne(category)
						.orElseThrow(() -> new IllegalStateException("더미풀 누락: " + category));

					// 금액: 규칙 범위와 더미풀 범위의 교집합에서 랜덤
					int minAmt = Math.max(rule.minAmt(), tpl.getMinAmount());
					int maxAmt = Math.min(rule.maxAmt(), tpl.getMaxAmount());
					if (minAmt > maxAmt) { // 교집합 없으면 풀 기준으로라도 방어
						minAmt = tpl.getMinAmount();
						maxAmt = tpl.getMaxAmount();
					}
					int amount = rng.nextInt(minAmt, maxAmt + 1);

					// 날짜/시간: 단순 랜덤(09~20시)
					int day = rng.nextInt(1, daysInMonth + 1);
					int hour = rng.nextInt(9, 21);
					int minute = rng.nextInt(0, 60);
					LocalDateTime ts = LocalDateTime.of(ym.getYear(), ym.getMonthValue(), day, hour, minute);

					monthBulk.add(Transaction.builder()
						.card(card)
						.transactionDateTime(ts)
						.amount(amount)
						.merchantName(tpl.getMerchantName())
						.category(category)
						.build());
				}
			}

			txRepo.saveAll(monthBulk);
		}
	}
}
