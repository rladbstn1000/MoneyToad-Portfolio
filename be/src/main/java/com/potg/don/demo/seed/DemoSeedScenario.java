package com.potg.don.demo.seed;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Directly authored synthetic spending, independent of database IDs, clocks and random sources. */
public final class DemoSeedScenario {

	public static final String VERSION = "V1";
	public static final String PRACTICE_MERCHANT = "합성 장보기(분류 연습)";
	public static final int TRANSACTION_COUNT = 240;
	public static final int BUDGET_COUNT = 72;

	private DemoSeedScenario() {
	}

	public static Scenario generate(LocalDate anchorDate) {
		Objects.requireNonNull(anchorDate, "anchorDate");
		YearMonth anchorMonth = YearMonth.from(anchorDate);
		List<TransactionSeed> transactions = new ArrayList<>(TRANSACTION_COUNT);
		List<BudgetSeed> budgets = new ArrayList<>(BUDGET_COUNT);
		for (int offset = -11; offset <= 0; offset++) {
			YearMonth month = anchorMonth.plusMonths(offset);
			add(transactions, anchorDate, month, "주거 / 통신", "합성 주거", 500_000, 1);
			add(transactions, anchorDate, month, "교통 / 차량", "합성 교통", 15_000, 2, 9, 16, 23);
			int foodAmount = switch (offset) {
				case -2 -> 35_000;
				case -1 -> 40_000;
				case 0 -> 45_000;
				default -> 25_000;
			};
			add(transactions, anchorDate, month, "식비", "합성 식사", foodAmount, 3, 10, 17, 24);
			if (offset == 0) {
				add(transactions, anchorDate, month, "카페", "합성 카페", 4_000, 4, 7, 11, 14, 18, 21, 25);
				transactions.add(new TransactionSeed(date(anchorDate, month, 28), 30_000,
					PRACTICE_MERCHANT, "카페"));
			} else {
				add(transactions, anchorDate, month, "카페", "합성 카페", 4_000, 4, 7, 11, 14, 18, 21, 25, 28);
			}
			add(transactions, anchorDate, month, "마트 / 편의점", "합성 마트", 45_000, 5, 19);
			add(transactions, anchorDate, month, "문화생활", "합성 문화", offset == -5 ? 180_000 : 20_000, 26);
			budgets.add(new BudgetSeed(month.atDay(1), "주거 / 통신", 600_000));
			budgets.add(new BudgetSeed(month.atDay(1), "교통 / 차량", 80_000));
			budgets.add(new BudgetSeed(month.atDay(1), "식비", 200_000));
			budgets.add(new BudgetSeed(month.atDay(1), "카페", 40_000));
			budgets.add(new BudgetSeed(month.atDay(1), "마트 / 편의점", 150_000));
			budgets.add(new BudgetSeed(month.atDay(1), "문화생활", 60_000));
		}
		return new Scenario(anchorDate, transactions, budgets);
	}

	private static void add(List<TransactionSeed> rows, LocalDate anchor, YearMonth month,
		String category, String merchant, int amount, int... days) {
		for (int index = 0; index < days.length; index++) {
			rows.add(new TransactionSeed(date(anchor, month, days[index]), amount,
				merchant + " " + String.format(java.util.Locale.ROOT, "%02d", index + 1), category));
		}
	}

	private static LocalDateTime date(LocalDate anchor, YearMonth month, int plannedDay) {
		int day = month.equals(YearMonth.from(anchor)) ? Math.min(plannedDay, anchor.getDayOfMonth()) : plannedDay;
		return month.atDay(day).atStartOfDay();
	}

	public record TransactionSeed(LocalDateTime dateTime, int amount, String merchantName, String category) {
	}

	public record BudgetSeed(LocalDate date, String category, int amount) {
	}

	public record Scenario(LocalDate anchorDate, List<TransactionSeed> transactions, List<BudgetSeed> budgets) {
		public Scenario {
			transactions = List.copyOf(transactions);
			budgets = List.copyOf(budgets);
		}
	}
}
