package com.potg.don.transaction.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.potg.don.transaction.dto.response.MonthlyPeerSpendingResponse;
import com.potg.don.transaction.projection.MonthlyPeerSum;
import com.potg.don.transaction.repository.PeerTransactionStatsRepository;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PeerTransactionStatsService {
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final PeerTransactionStatsRepository statsRepository;
	private final UserRepository userRepository;

	public List<MonthlyPeerSpendingResponse> getPeerMonthlySpendingForUser(Long userId) {
		User user = userRepository.findById(userId)
			.orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다. id=" + userId));

		String gender = normalizeGenderToDb(user.getGender()); // e.g. "M"/"F"
		int ageGroup = calcAgeGroup(user.getAge());        // 핵심 변경

		return getPeerMonthlySpending(ageGroup, gender);
	}

	private List<MonthlyPeerSpendingResponse> getPeerMonthlySpending(int ageGroup, String genderRaw) {
		String gender = normalizeGenderToDb(genderRaw);

		YearMonth endYm = YearMonth.now(KST);      // 이번 달
		YearMonth startYm = endYm.minusMonths(11); // 11개월 전
		LocalDate startDate = startYm.atDay(1);    // 시작 월의 1일

		// DB 집계 조회
		List<MonthlyPeerSum> rows = statsRepository.findMonthlyPeerSum(ageGroup, gender, startDate);

		// (year, month) -> sum 맵으로 변환
		Map<YearMonth, Long> sumByYm = rows.stream().collect(Collectors.toMap(
			r -> YearMonth.of(r.getYear(), r.getMonth()),
			MonthlyPeerSum::getTotalAmount,
			Long::sum
		));

		// 12개월 모두 채워서 반환
		List<MonthlyPeerSpendingResponse> result = new ArrayList<>(12);
		YearMonth cursor = startYm;
		for (int i = 0; i < 12; i++) {
			long sum = sumByYm.getOrDefault(cursor, 0L);
			int totalAmount = (sum > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int)sum;
			result.add(new MonthlyPeerSpendingResponse(cursor.toString(), totalAmount)); // "yyyy-MM"
			cursor = cursor.plusMonths(1);
		}

		return result;
	}

	// ✅ "남성"/"여성"으로 매핑 (영/한/약어 모두 허용). 규격 밖 입력은 기본 "남성".
	private String normalizeGenderToDb(String gender) {
		if (gender == null)
			return "남성";
		String g = gender.trim().toLowerCase(Locale.ROOT);
		if (g.startsWith("남") || g.startsWith("m") || g.equals("male"))
			return "남성";
		if (g.startsWith("여") || g.startsWith("f") || g.equals("female"))
			return "여성";
		return "남성"; // 필요 시 "여성" 또는 예외로 변경 가능
	}

	private int calcAgeGroup(Integer age) {
		if (age == null || age < 0)
			return 0; // 미분류 처리 규칙은 프로젝트에 맞게 조정
		return (age / 10) * 10;
	}
}
