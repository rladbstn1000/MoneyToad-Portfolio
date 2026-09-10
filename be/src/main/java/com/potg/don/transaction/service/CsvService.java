package com.potg.don.transaction.service;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.potg.don.budget.entity.Budget;
import com.potg.don.budget.repository.BudgetRepository;
import com.potg.don.transaction.client.CsvClient;
import com.potg.don.transaction.dto.response.AnalysisTriggerResponse;
import com.potg.don.transaction.dto.response.CsvUploadResponse;
import com.potg.don.transaction.entity.Transaction;
import com.potg.don.transaction.repository.TransactionRepository;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CsvService {

	private final CsvClient csvClient;
	private final UserRepository userRepository;
	private final TransactionRepository transactionRepository;
	private final BudgetRepository budgetRepository;

	@Transactional
	public void saveBudgetsFromTriggerResponse(Long userId, AnalysisTriggerResponse resp) {
		if (resp == null)
			throw new IllegalArgumentException("AnalysisTriggerResponse가 null입니다.");
		if (resp.getDetails() == null || resp.getDetails().getCategoryPredictions() == null) {
			// 저장할 내용 없음
			return;
		}

		User user = userRepository.findById(userId)
			.orElseThrow(() -> new IllegalArgumentException("사용자 없음: " + userId));

		Integer year = resp.getYear();
		Integer month = resp.getMonth();
		if (year == null || month == null) {
			throw new IllegalStateException("응답에 year/month가 없습니다.");
		}

		LocalDate budgetDate = LocalDate.of(year, month, 1);
		final LocalDateTime now = LocalDateTime.now();
		resp.getDetails().getCategoryPredictions().forEach((category, pred) -> {
			int amount = toInt(pred.getPredictedAmount()); // Double → int(반올림)

			Budget budget = budgetRepository
				.findByUser_IdAndBudgetDateAndCategory(user.getId(), budgetDate, category)
				.orElseGet(() -> {
					Budget b = new Budget();
					b.setUser(user);
					b.setBudgetDate(budgetDate);
					b.setCategory(category);
					return b;
				});

			budget.resetFromPrediction(amount, resp.getFileId(), now);
			budgetRepository.save(budget);
		});
	}

	/**
	 * 사용자의 현재 file_id로 분석을 트리거
	 */
	@Transactional(readOnly = true)
	public AnalysisTriggerResponse triggerAnalysis(Long userId) {
		User user = userRepository.findById(userId)
			.orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + userId));

		String fileId = user.getFileId();
		if (fileId == null || fileId.isBlank()) {
			throw new IllegalStateException("분석을 시작할 file_id가 없습니다. 먼저 CSV를 업로드하세요.");
		}

		return csvClient.triggerAnalysis(fileId);
	}

	@Transactional
	public CsvUploadResponse uploadCsvAndSaveFileId(Long userId, Long cardId) {
		// 1) CSV 생성
		byte[] csv = buildTransactionsCsvForCard(cardId);

		// 2) 업로드 호출
		CsvUploadResponse response = csvClient.uploadCsv(csv, cardId);
		if (response == null || response.getFileId() == null || response.getFileId().isBlank()) {
			throw new IllegalStateException("AI 서버에서 file_id가 반환되지 않았습니다.");
		}

		// 3) users.file_id 업데이트
		User user = userRepository.findById(userId)
			.orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + userId));
		user.updateFileId(response.getFileId()); // User 엔티티에 setFileId(String) 존재해야 함

		// JPA @Transactional 이면 flush는 커밋 시점에 자동 반영
		return response;
	}

	@Transactional
	public CsvUploadResponse changeCsvAndSaveFileId(Long userId, Long newCardId) {
		User user = userRepository.findById(userId)
			.orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + userId));

		byte[] csv = buildTransactionsCsvForCard(newCardId);

		CsvUploadResponse response;
		if (user.getFileId() == null || user.getFileId().isBlank()) {
			// 기존 file_id 없으면 새로 업로드
			response = csvClient.uploadCsv(csv, newCardId);
		} else {
			// 기존 file_id 있으면 교체(put)
			response = csvClient.changeCsv(user.getFileId(), csv, newCardId);
		}

		if (response == null || response.getFileId() == null || response.getFileId().isBlank()) {
			throw new IllegalStateException("AI 서버에서 file_id가 반환되지 않았습니다.");
		}

		user.updateFileId(response.getFileId());
		return response;
	}

	private byte[] buildTransactionsCsvForCard(Long cardId) {
		List<Transaction> rows = transactionRepository
			.findAllByCard_IdOrderByTransactionDateTimeAsc(cardId);

		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		// UTF-8 BOM (엑셀 한글 대응)
		byte[] bom = new byte[] {(byte)0xEF, (byte)0xBB, (byte)0xBF};
		try {
			baos.write(bom);

			try (OutputStreamWriter writer = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
				 CSVPrinter csv = new CSVPrinter(writer, CSVFormat.DEFAULT
					 .withHeader("transaction_id", "transaction_date_time", "merchant_name", "category", "amount"))) {

				for (Transaction t : rows) {
					csv.printRecord(
						t.getId(),
						t.getTransactionDateTime(), // ISO-8601 문자열로 출력됨
						t.getMerchantName(),
						t.getCategory(),
						t.getAmount()
					);
				}
			}
			return baos.toByteArray();

		} catch (Exception e) {
			throw new IllegalStateException("CSV 생성 실패", e);
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
