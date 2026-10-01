package com.potg.don.demo.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DemoDatasetValidatorTest {
    private final DemoSeedScenario.Scenario scenario = DemoSeedScenario.generate(LocalDate.of(2026, 9, 10));
    private List<DemoDatasetValidator.CardData> cards() {
        return List.of(new DemoDatasetValidator.CardData(null, null));
    }
    private List<DemoDatasetValidator.TransactionData> transactions() {
        return scenario.transactions().stream().map(t ->
            new DemoDatasetValidator.TransactionData(t.dateTime(), t.amount(), t.merchantName())).toList();
    }
    private List<DemoDatasetValidator.BudgetData> budgets() {
        return scenario.budgets().stream().map(b ->
            new DemoDatasetValidator.BudgetData(b.date(), b.category(), null, null, null)).toList();
    }
    @Test void unchangedAuthoredShapeHasNoMutableCategoryOrBudgetAmountDependency() {
        assertThat(DemoDatasetValidator.validate(cards(), transactions(), budgets())).isEqualTo(scenario.anchorDate());
    }
    @Test void cardCountAndFinancialFieldsAreMandatory() {
        for (var invalid : List.of(List.<DemoDatasetValidator.CardData>of(),
            List.of(new DemoDatasetValidator.CardData("synthetic-invalid", null)),
            List.of(new DemoDatasetValidator.CardData(null, "synthetic-invalid")),
            List.of(cards().getFirst(), cards().getFirst()))) {
            assertThatThrownBy(() -> DemoDatasetValidator.validate(invalid, transactions(), budgets()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("DEMO_SEED_INCOMPLETE");
        }
    }
    @Test void missingAndCorruptedImmutableTransactionsAreRejected() {
        var missing = new ArrayList<>(transactions());
        missing.removeFirst();
        assertThatThrownBy(() -> DemoDatasetValidator.validate(cards(), missing, budgets())).isInstanceOf(IllegalArgumentException.class);
        var changed = new ArrayList<>(transactions());
        var first = changed.getFirst();
        changed.set(0, new DemoDatasetValidator.TransactionData(first.dateTime(), first.amount() + 1, first.merchantName()));
        assertThatThrownBy(() -> DemoDatasetValidator.validate(cards(), changed, budgets())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void duplicateBudgetSlotsAndPredictionFieldsCannotLookLikeCompleteSeed() {
        var changed = new ArrayList<>(budgets());
        changed.set(0, changed.get(1));
        assertThatThrownBy(() -> DemoDatasetValidator.validate(cards(), transactions(), changed)).isInstanceOf(IllegalArgumentException.class);
        var source = new ArrayList<>(budgets());
        var first = source.getFirst();
        source.set(0, new DemoDatasetValidator.BudgetData(first.date(), first.category(), 1, null, null));
        assertThatThrownBy(() -> DemoDatasetValidator.validate(cards(), transactions(), source)).isInstanceOf(IllegalArgumentException.class);
    }
}
