package com.potg.don.budget.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.potg.don.user.entity.User;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "budgets")
@Getter
@Setter
public class Budget {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne
	@JoinColumn(name = "user_id")
	private User user;

	private LocalDate budgetDate;

	private Integer amount;

	private String category;

	private Integer initialAmount; // 최초 분석값(스냅샷, 보존)

	private String initialFileId;  // 최초값 생성한 file_id (옵션)

	private LocalDateTime predictedAt;   // 최초값 생성 시각 (옵션)

	private Boolean isOverridden = false; // 사용자가 수정했는지

	private LocalDateTime overriddenAt;

	public void updateBudget(Integer amount) {
		this.amount = amount;
		this.isOverridden = true;
		this.overriddenAt = LocalDateTime.now();
	}

	public void resetFromPrediction(int predictedAmount, String fileId, LocalDateTime predictedAt) {
		// 화면에 보여줄 현재값 = 초기값
		this.amount = predictedAmount;

		// 초기 스냅샷(항상 갱신)
		this.initialAmount = predictedAmount;
		this.initialFileId = fileId;
		this.predictedAt = predictedAt;

		// 사용자 수정 플래그/시각 초기화
		this.isOverridden = false;
		this.overriddenAt = null;
	}
}
