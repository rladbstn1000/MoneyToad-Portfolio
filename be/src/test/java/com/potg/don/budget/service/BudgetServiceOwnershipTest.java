package com.potg.don.budget.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.potg.don.budget.dto.request.BudgetUpdateRequest;
import com.potg.don.budget.entity.Budget;
import com.potg.don.budget.repository.BudgetRepository;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;

import jakarta.persistence.EntityNotFoundException;

/** Service ownership contract only; persistence is verified separately against MySQL. */
@ExtendWith(MockitoExtension.class)
class BudgetServiceOwnershipTest {

	private static final long USER_A_ID = 101L;
	private static final long USER_B_ID = 202L;
	private static final long BUDGET_A_ID = 1001L;
	private static final long BUDGET_B_ID = 2002L;
	private static final long MISSING_BUDGET_ID = 9009L;
	private static final String BUDGET_NOT_FOUND = "누수를 찾을 수 없습니다";

	@Mock
	private BudgetRepository budgetRepository;
	@Mock
	private UserRepository userRepository;

	private BudgetService service;
	private User userA;
	private Budget budgetA;
	private Budget budgetB;

	@BeforeEach
	void setUp() {
		service = new BudgetService(budgetRepository, userRepository);
		userA = user(USER_A_ID, "a1-unit-a@example.invalid");
		User userB = user(USER_B_ID, "a1-unit-b@example.invalid");
		budgetA = spy(budget(BUDGET_A_ID, userA, "식비", 100_000));
		budgetB = spy(budget(BUDGET_B_ID, userB, "카페", 50_000));
	}

	@Test
	void updatesOwnedBudgetAndPreservesPredictionAndOwnershipFields() {
		when(userRepository.findById(USER_A_ID)).thenReturn(Optional.of(userA));
		when(budgetRepository.findByIdAndUser_Id(BUDGET_A_ID, USER_A_ID))
			.thenReturn(Optional.of(budgetA));
		when(budgetRepository.save(budgetA)).thenReturn(budgetA);
		BudgetSnapshot before = snapshot(budgetA);
		BudgetSnapshot otherBefore = snapshot(budgetB);
		clearInvocations(budgetA, budgetB);
		LocalDateTime startedAt = LocalDateTime.now();

		Budget result = service.updateBudget(USER_A_ID, request(BUDGET_A_ID, 125_000));

		LocalDateTime finishedAt = LocalDateTime.now();
		assertThat(result).isSameAs(budgetA);
		assertThat(result.getAmount()).isEqualTo(125_000);
		assertThat(result.getIsOverridden()).isTrue();
		assertThat(result.getOverriddenAt()).isAfterOrEqualTo(startedAt).isBeforeOrEqualTo(finishedAt);
		assertThat(result.getOverriddenAt()).isNotEqualTo(before.overriddenAt());
		assertThat(result.getId()).isEqualTo(before.id());
		assertThat(result.getUser()).isSameAs(before.user());
		assertThat(result.getBudgetDate()).isEqualTo(before.budgetDate());
		assertThat(result.getCategory()).isEqualTo(before.category());
		assertThat(result.getInitialAmount()).isEqualTo(before.initialAmount());
		assertThat(result.getInitialFileId()).isEqualTo(before.initialFileId());
		assertThat(result.getPredictedAt()).isEqualTo(before.predictedAt());
		verify(budgetA).updateBudget(125_000);
		verifyNoInteractions(budgetB);
		assertThat(snapshot(budgetB)).isEqualTo(otherBefore);
		verify(userRepository).findById(USER_A_ID);
		verify(budgetRepository).findByIdAndUser_Id(BUDGET_A_ID, USER_A_ID);
		verify(budgetRepository).save(budgetA);
		verify(budgetRepository, never()).findById(anyLong());
		verifyNoMoreInteractions(userRepository, budgetRepository);
	}

	@Test
	void rejectsOtherUsersBudgetWithoutMutationOrSave() {
		when(userRepository.findById(USER_A_ID)).thenReturn(Optional.of(userA));
		when(budgetRepository.findByIdAndUser_Id(BUDGET_B_ID, USER_A_ID)).thenReturn(Optional.empty());
		assertDeniedWithoutMutation(BUDGET_B_ID);
	}

	@Test
	void rejectsMissingBudgetWithSameGenericNotFoundContract() {
		when(userRepository.findById(USER_A_ID)).thenReturn(Optional.of(userA));
		when(budgetRepository.findByIdAndUser_Id(MISSING_BUDGET_ID, USER_A_ID)).thenReturn(Optional.empty());
		assertDeniedWithoutMutation(MISSING_BUDGET_ID);
	}

	@Test
	void rejectsMissingUserBeforeQueryingBudgets() {
		when(userRepository.findById(USER_A_ID)).thenReturn(Optional.empty());

		EntityNotFoundException error = assertThrows(EntityNotFoundException.class,
			() -> service.updateBudget(USER_A_ID, request(BUDGET_A_ID, 125_000)));

		assertThat(error).hasMessage("해당 ID의 사용자를 찾을 수 없습니다: " + USER_A_ID);
		verify(userRepository).findById(USER_A_ID);
		verifyNoMoreInteractions(userRepository);
		verifyNoInteractions(budgetRepository, budgetA, budgetB);
	}

	private void assertDeniedWithoutMutation(long requestedBudgetId) {
		BudgetSnapshot beforeA = snapshot(budgetA);
		BudgetSnapshot beforeB = snapshot(budgetB);
		clearInvocations(budgetA, budgetB);

		EntityNotFoundException error = assertThrows(EntityNotFoundException.class,
			() -> service.updateBudget(USER_A_ID, request(requestedBudgetId, 125_000)));

		assertThat(error).hasMessage(BUDGET_NOT_FOUND);
		// Neither fixture entity is reached, including updateBudget or any setter.
		verifyNoInteractions(budgetA, budgetB);
		assertThat(snapshot(budgetA)).isEqualTo(beforeA);
		assertThat(snapshot(budgetB)).isEqualTo(beforeB);
		verify(userRepository).findById(USER_A_ID);
		verify(budgetRepository).findByIdAndUser_Id(requestedBudgetId, USER_A_ID);
		verify(budgetRepository, never()).findById(anyLong());
		verify(budgetRepository, never()).save(any(Budget.class));
		verifyNoMoreInteractions(userRepository, budgetRepository);
	}

	private static User user(long id, String email) {
		User user = User.createUser(email, "A1 Synthetic User");
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}

	private static Budget budget(long id, User user, String category, int amount) {
		Budget budget = new Budget();
		budget.setId(id);
		budget.setUser(user);
		budget.setBudgetDate(LocalDate.of(2026, 9, 1));
		budget.setCategory(category);
		budget.setAmount(amount);
		budget.setInitialAmount(amount - 5_000);
		budget.setInitialFileId("a1-unit-synthetic-file-" + id);
		budget.setPredictedAt(LocalDateTime.of(2026, 8, 30, 10, 0));
		budget.setIsOverridden(false);
		budget.setOverriddenAt(null);
		return budget;
	}

	private static BudgetUpdateRequest request(long budgetId, int amount) {
		BudgetUpdateRequest request = new BudgetUpdateRequest();
		ReflectionTestUtils.setField(request, "budgetId", budgetId);
		request.budget = amount;
		return request;
	}

	private static BudgetSnapshot snapshot(Budget budget) {
		return new BudgetSnapshot(budget.getId(), budget.getUser(), budget.getBudgetDate(), budget.getAmount(),
			budget.getCategory(), budget.getInitialAmount(), budget.getInitialFileId(), budget.getPredictedAt(),
			budget.getIsOverridden(), budget.getOverriddenAt());
	}

	private record BudgetSnapshot(Long id, User user, LocalDate budgetDate, Integer amount, String category,
		Integer initialAmount, String initialFileId, LocalDateTime predictedAt, Boolean overridden,
		LocalDateTime overriddenAt) {
	}
}
