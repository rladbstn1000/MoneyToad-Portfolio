package com.potg.don.analysisJob.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.potg.don.analysisJob.entity.AnalysisJob;
import com.potg.don.analysisJob.repository.AnalysisJobRepository;
import com.potg.don.budget.entity.Budget;
import com.potg.don.budget.repository.BudgetRepository;
import com.potg.don.transaction.client.CsvClient;
import com.potg.don.transaction.dto.response.BaselineResponse;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalysisJobService {

	private final AnalysisJobRepository jobRepo;
	private final CsvClient csvClient;
	private final UserRepository userRepo;
	private final BudgetRepository budgetRepo;

	@Transactional
	public AnalysisJob enqueue(Long userId, String fileId) {

		User user = userRepo.findById(userId)
			.orElseThrow(() -> new IllegalArgumentException("사용자 없음: " + userId));
		if (user.getFileId() == null || user.getFileId().isBlank()) {
			throw new IllegalStateException("file_id가 없습니다.");
		}

		// 2) Job 생성
		AnalysisJob j = new AnalysisJob();
		j.setUserId(userId);
		j.setFileId(fileId);
		j.setStatus(AnalysisJob.Status.QUEUED);
		j.setRetryCount(0);
		j.setCreatedAt(Instant.now());
		j.setUpdatedAt(Instant.now());
		j.setNextPollAt(Instant.now().plusSeconds(2));
		return jobRepo.save(j);
	}

	/**
	 * 스케줄러가 호출: poll 대상 Job을 1건 처리
	 */
	@Transactional
	public void pollOnce(AnalysisJob job) {
		log.info("[Job] pollOnce start id={}, fileId={}", job.getId(), job.getFileId());
		// 간단 락: 15초 리스
		job.setLeasedUntil(Instant.now().plusSeconds(15));
		jobRepo.saveAndFlush(job);

		var status = csvClient.getCsvStatus(job.getFileId());
		log.info("[Job] status={} for fileId={}", status != null ? status.getStatus() : "null", job.getFileId());
		String s = status != null && status.getStatus() != null ? status.getStatus().toLowerCase() : "";

		if ("none".equals(s)) {
			// 완료 → 결과 조회 & 저장
			log.info("[Job] DONE -> fetching baseline for fileId={}", job.getFileId());
			saveBaselineToBudgets(job.getUserId(), job.getFileId());
			job.setStatus(AnalysisJob.Status.DONE);
			job.setLastMessage("DONE");
			job.setNextPollAt(null);
			log.info("[Job] baseline saved for userId={}", job.getUserId());
		} else {
			log.info("[Job] RUNNING retryCount={}, nextPollAt={}", job.getRetryCount(), job.getNextPollAt());
			// 진행중 → 백오프 증가
			int rc = (job.getRetryCount() == null ? 0 : job.getRetryCount()) + 1;
			job.setRetryCount(rc);
			job.setStatus(AnalysisJob.Status.RUNNING);
			job.setLastMessage(status != null ? status.getStatus() : "UNKNOWN");

			long delaySec = Math.min((long)Math.pow(2, rc - 1), 60); // 1,2,4,8,16,32,60...
			job.setNextPollAt(Instant.now().plusSeconds(delaySec));
		}

		job.setUpdatedAt(Instant.now());
		job.setLeasedUntil(null); // 락 해제
		jobRepo.save(job);
	}

	/**
	 * 베이스라인을 불러와 Budget upsert
	 */
	@Transactional
	public void saveBaselineToBudgets(Long userId, String fileId) {
		User user = userRepo.findById(userId)
			.orElseThrow(() -> new IllegalArgumentException("사용자 없음: " + userId));

		BaselineResponse baseline = csvClient.getBaseline(fileId);
		if (baseline == null || baseline.getBaselineMonths() == null) return;

		final LocalDateTime now = LocalDateTime.now();

		for (BaselineResponse.BaselineMonth bm : baseline.getBaselineMonths()) {
			if (bm.getCategoryPredictions() == null || bm.getCategoryPredictions().isEmpty()) continue;

			LocalDate budgetDate = LocalDate.of(bm.getYear(), bm.getMonth(), 1);

			bm.getCategoryPredictions().forEach((category, pred) -> {
				int predicted = toInt(pred.getPredictedAmount());

				Budget budget = budgetRepo
					.findByUser_IdAndBudgetDateAndCategory(userId, budgetDate, category)
					.orElseGet(() -> {
						Budget b = new Budget();
						b.setUser(user);
						b.setBudgetDate(budgetDate);
						b.setCategory(category);
						return b;
					});

				// ✅ 항상 초기화 후 새 예측으로 덮어쓰기
				budget.resetFromPrediction(predicted, fileId, now);
				budgetRepo.save(budget);
			});
		}
	}

	private int toInt(Double d) {
		if (d == null)
			return 0;
		long r = Math.round(d);
		if (r > Integer.MAX_VALUE)
			return Integer.MAX_VALUE;
		if (r < Integer.MIN_VALUE)
			return Integer.MIN_VALUE;
		return (int)r;
	}
}

