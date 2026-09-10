package com.potg.don.budget.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.potg.don.budget.entity.Budget;

public interface BudgetRepository extends JpaRepository<Budget, Long> {
	// 특정 사용자 + 특정 월(그 달의 1일) 전체 예산
	List<Budget> findAllByUserIdAndBudgetDate(Long userId, LocalDate monthFirstDay);

	// 특정 사용자 + 기간 내(포함) 전체 예산, 월 오름차순
	List<Budget> findAllByUserIdAndBudgetDateBetweenOrderByBudgetDateAsc(
		Long userId, LocalDate start, LocalDate end
	);

	Optional<Budget> findByUser_IdAndBudgetDateAndCategory(Long userId, LocalDate budgetDate, String category);
}
