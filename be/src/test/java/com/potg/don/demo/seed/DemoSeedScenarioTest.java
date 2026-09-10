package com.potg.don.demo.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DemoSeedScenarioTest {
	private static final LocalDate ANCHOR = LocalDate.of(2026, 9, 10);

	@Test
	void v1HasExactlyTwelveMonthsTwentyTransactionsAndSixBudgetSlotsEach() {
		var scenario = DemoSeedScenario.generate(ANCHOR);
		assertThat(DemoSeedScenario.VERSION).isEqualTo("V1");
		assertThat(scenario.transactions()).hasSize(240);
		assertThat(scenario.budgets()).hasSize(72);
		assertThat(scenario.transactions().stream().collect(Collectors.groupingBy(
			row -> YearMonth.from(row.dateTime()), Collectors.counting())))
			.hasSize(12).allSatisfy((month, count) -> assertThat(count).isEqualTo(20L));
		assertThat(scenario.budgets().stream().collect(Collectors.groupingBy(
			row -> YearMonth.from(row.date()), Collectors.counting())))
			.hasSize(12).allSatisfy((month, count) -> assertThat(count).isEqualTo(6L));
		assertThat(scenario.budgets().stream().map(row -> row.date() + ":" + row.category()).distinct()).hasSize(72);
		assertThat(scenario.transactions()).allSatisfy(row -> {
			assertThat(row.amount()).isPositive();
			assertThat(row.merchantName()).startsWith("합성 ");
		});
	}

	@Test
	void sameAnchorReproducesEveryFinancialFieldAndCannotMutateTheFixtureLists() {
		var first = DemoSeedScenario.generate(ANCHOR);
		assertThat(DemoSeedScenario.generate(ANCHOR)).isEqualTo(first);
		assertThatThrownBy(() -> first.transactions().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> first.budgets().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void authoredAmountsProduceTheApprovedMonthlyAndAnnualTotals() {
		var scenario = DemoSeedScenario.generate(ANCHOR);
		var month = YearMonth.from(ANCHOR);
		Map<YearMonth, Integer> totals = scenario.transactions().stream().collect(Collectors.groupingBy(
			row -> YearMonth.from(row.dateTime()), Collectors.summingInt(DemoSeedScenario.TransactionSeed::amount)));
		assertThat(totals.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(9_990_000);
		assertThat(totals.get(month.minusMonths(5))).isEqualTo(962_000);
		assertThat(totals.get(month.minusMonths(2))).isEqualTo(842_000);
		assertThat(totals.get(month.minusMonths(1))).isEqualTo(862_000);
		assertThat(totals.get(month)).isEqualTo(908_000);
		Set<YearMonth> special = Set.of(month.minusMonths(5), month.minusMonths(2), month.minusMonths(1), month);
		totals.forEach((key, total) -> {
			if (!special.contains(key)) {
				assertThat(total).isEqualTo(802_000);
			}
		});
		assertThat(leak(scenario.transactions(), scenario, month.minusMonths(5))).isEqualTo(120_000);
		assertThat(leak(scenario.transactions(), scenario, month)).isEqualTo(18_000);
	}

	@Test
	void correctingThePracticeCategoryPreservesSpendingAndRemovesTheCurrentLeak() {
		var scenario = DemoSeedScenario.generate(ANCHOR);
		var month = YearMonth.from(ANCHOR);
		assertThat(scenario.transactions().stream()
			.filter(row -> row.merchantName().equals(DemoSeedScenario.PRACTICE_MERCHANT))).hasSize(1);
		var corrected = scenario.transactions().stream().map(row ->
			row.merchantName().equals(DemoSeedScenario.PRACTICE_MERCHANT)
				? new DemoSeedScenario.TransactionSeed(row.dateTime(), row.amount(), row.merchantName(), "마트 / 편의점")
				: row).toList();
		assertThat(corrected.stream().filter(row -> YearMonth.from(row.dateTime()).equals(month))
			.mapToInt(DemoSeedScenario.TransactionSeed::amount).sum()).isEqualTo(908_000);
		assertThat(leak(corrected, scenario, month)).isZero();
		assertThat(leak(corrected, scenario, month.minusMonths(5))).isEqualTo(120_000);
	}

	@ParameterizedTest
	@ValueSource(strings = {"2024-02-01", "2024-02-29", "2025-01-01", "2025-12-31", "2026-03-01", "2026-09-10"})
	void monthStartsLeapDayAndYearChangesNeverProduceFutureOrInvalidDates(String date) {
		var anchor = LocalDate.parse(date);
		var rows = DemoSeedScenario.generate(anchor).transactions();
		assertThat(rows).hasSize(240).allSatisfy(row -> {
			assertThat(row.dateTime()).isBeforeOrEqualTo(anchor.atStartOfDay());
			assertThat(row.dateTime().getDayOfMonth()).isBetween(1, 28);
			assertThat(row.dateTime().toLocalTime()).isEqualTo(java.time.LocalTime.MIDNIGHT);
		});
		var months = rows.stream().map(row -> YearMonth.from(row.dateTime())).distinct().sorted().toList();
		assertThat(months).hasSize(12);
		assertThat(months.getFirst()).isEqualTo(YearMonth.from(anchor).minusMonths(11));
		assertThat(months.getLast()).isEqualTo(YearMonth.from(anchor));
		assertThat(rows.stream().map(DemoSeedScenario.TransactionSeed::dateTime).max(java.util.Comparator.naturalOrder()))
			.contains(YearMonth.from(anchor).atDay(Math.min(anchor.getDayOfMonth(), 28)).atStartOfDay());
	}

	@Test
	void advancingTheAnchorChangesOnlyDatesAndKeepsTheRelativeScenario() {
		var lastDay = DemoSeedScenario.generate(LocalDate.of(2025, 12, 31));
		var firstDay = DemoSeedScenario.generate(LocalDate.of(2026, 1, 1));
		assertThat(lastDay.transactions().stream().map(row -> List.of(row.amount(), row.category(), row.merchantName())).toList())
			.isEqualTo(firstDay.transactions().stream().map(row -> List.of(row.amount(), row.category(), row.merchantName())).toList());
	}

	private static int leak(List<DemoSeedScenario.TransactionSeed> transactions,
		DemoSeedScenario.Scenario scenario, YearMonth month) {
		Map<String, Integer> spent = transactions.stream().filter(row -> YearMonth.from(row.dateTime()).equals(month))
			.collect(Collectors.groupingBy(DemoSeedScenario.TransactionSeed::category,
				Collectors.summingInt(DemoSeedScenario.TransactionSeed::amount)));
		return scenario.budgets().stream().filter(row -> YearMonth.from(row.date()).equals(month))
			.mapToInt(row -> Math.max(0, spent.getOrDefault(row.category(), 0) - row.amount())).sum();
	}
}
