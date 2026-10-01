package com.potg.don.demo.seed;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.potg.don.budget.entity.Budget;
import com.potg.don.budget.repository.BudgetRepository;
import com.potg.don.card.entity.Card;
import com.potg.don.card.repository.CardRepository;
import com.potg.don.transaction.entity.Transaction;
import com.potg.don.transaction.repository.TransactionRepository;
import com.potg.don.user.entity.User;

import lombok.RequiredArgsConstructor;

/** Installs V1 inside the caller's login transaction. It does not create users or sessions. */
@Service
@Profile("demo")
@RequiredArgsConstructor
public class DemoSeedService {
	private final CardRepository cards;
	private final TransactionRepository transactions;
	private final BudgetRepository budgets;

	@Transactional(propagation = Propagation.MANDATORY)
	public void install(User user, LocalDate anchorDate) {
		if (user == null || user.getId() == null || anchorDate == null) throw incomplete();
		var existingCard = cards.findByUserId(user.getId());
		List<Budget> existingBudgets = budgets.findAllByUserId(user.getId());
		if (existingCard.isPresent()) {
			// A completed visit keeps its original anchor and all category/budget edits.
			validateComplete(existingCard.get(), existingBudgets);
			return;
		}
		if (!existingBudgets.isEmpty()) throw incomplete();

		DemoSeedScenario.Scenario scenario = DemoSeedScenario.generate(anchorDate);
		// The existing UNIQUE(user_id) constraint arbitrates concurrent first installs.
		Card card = cards.saveAndFlush(Card.createSyntheticCard(user));
		transactions.saveAll(scenario.transactions().stream().map(row -> Transaction.builder()
			.card(card).transactionDateTime(row.dateTime()).amount(row.amount())
			.merchantName(row.merchantName()).category(row.category()).build()).toList());
		budgets.saveAll(scenario.budgets().stream().map(row -> {
			Budget budget = new Budget();
			budget.setUser(user);
			budget.setBudgetDate(row.date());
			budget.setCategory(row.category());
			budget.setAmount(row.amount());
			// These are authored reference amounts, never prediction snapshots.
			return budget;
		}).toList());
		transactions.flush(); // Flushes every entity before Redis can be created by the caller.
		validateComplete(card, budgets.findAllByUserId(user.getId()));
	}

	@Transactional(readOnly = true)
	public YearMonth requireAnchorMonth(Long userId) {
		Card card = cards.findByUserId(userId).orElseThrow(DemoSeedService::incomplete);
		return YearMonth.from(validateComplete(card, budgets.findAllByUserId(userId)));
	}

	private LocalDate validateComplete(Card card, List<Budget> actualBudgets) {
        List<Transaction> actual = transactions.findAllByCard_IdOrderByTransactionDateTimeAsc(card.getId());
        return DemoDatasetValidator.validate(
            List.of(new DemoDatasetValidator.CardData(card.getCardNo(), card.getCvc())),
            actual.stream().map(row -> new DemoDatasetValidator.TransactionData(
                row.getTransactionDateTime(), row.getAmount(), row.getMerchantName())).toList(),
            actualBudgets.stream().map(row -> new DemoDatasetValidator.BudgetData(
                row.getBudgetDate(), row.getCategory(), row.getInitialAmount(), row.getInitialFileId(),
                row.getPredictedAt())).toList());
    }

    private static IllegalArgumentException incomplete() {
        return new IllegalArgumentException("DEMO_SEED_INCOMPLETE");
    }
}
