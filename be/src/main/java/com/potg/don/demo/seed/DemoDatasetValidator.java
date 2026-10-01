package com.potg.don.demo.seed;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Side-effect-free V1 shape validation shared by installation and offline maintenance.
 * Identity, provenance, User source fields and Job references are verified by the caller.
 * Mutable category/budget/profile fields are deliberately absent from this contract. */
public final class DemoDatasetValidator {
    private DemoDatasetValidator() { }

    public record CardData(String cardNo, String cvc) { }
    public record TransactionData(LocalDateTime dateTime, Integer amount, String merchantName) { }
    public record BudgetData(LocalDate date, String category, Integer initialAmount,
        String initialFileId, LocalDateTime predictedAt) { }

    public static LocalDate validate(List<CardData> cards, List<TransactionData> transactions,
        List<BudgetData> budgets) {
        if (cards == null || transactions == null || budgets == null || cards.size() != 1
            || cards.getFirst() == null || cards.getFirst().cardNo() != null || cards.getFirst().cvc() != null
            || transactions.size() != DemoSeedScenario.TRANSACTION_COUNT
            || budgets.size() != DemoSeedScenario.BUDGET_COUNT) throw incomplete();
        for (TransactionData row : transactions) {
            if (row == null || row.dateTime() == null || row.amount() == null || row.merchantName() == null) {
                throw incomplete();
            }
        }
        LocalDate anchor = transactions.stream().map(TransactionData::dateTime)
            .max(Comparator.naturalOrder()).orElseThrow(DemoDatasetValidator::incomplete).toLocalDate();
        DemoSeedScenario.Scenario expected = DemoSeedScenario.generate(anchor);
        Comparator<TransactionData> order = Comparator.comparing(TransactionData::dateTime)
            .thenComparing(TransactionData::merchantName).thenComparingInt(TransactionData::amount);
        var expectedImmutable = expected.transactions().stream().map(row ->
            new TransactionData(row.dateTime(), row.amount(), row.merchantName())).sorted(order).toList();
        if (!transactions.stream().sorted(order).toList().equals(expectedImmutable)) throw incomplete();
        for (BudgetData row : budgets) {
            if (row == null || row.initialAmount() != null || row.initialFileId() != null || row.predictedAt() != null) {
                throw incomplete();
            }
        }
        Set<BudgetSlot> expectedSlots = expected.budgets().stream()
            .map(row -> new BudgetSlot(row.date(), row.category())).collect(Collectors.toSet());
        Set<BudgetSlot> actualSlots = budgets.stream().map(row -> new BudgetSlot(row.date(), row.category()))
            .collect(Collectors.toSet());
        if (!actualSlots.equals(expectedSlots) || actualSlots.size() != budgets.size()) throw incomplete();
        return anchor;
    }

    private static IllegalArgumentException incomplete() { return new IllegalArgumentException("DEMO_SEED_INCOMPLETE"); }
    private record BudgetSlot(LocalDate date, String category) { }
}
