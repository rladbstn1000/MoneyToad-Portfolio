package com.potg.don.card.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.potg.don.analysisJob.service.AnalysisJobService;
import com.potg.don.auth.entity.CustomUserDetails;
import com.potg.don.card.dto.request.CardRequest;
import com.potg.don.card.dto.response.CardResponse;
import com.potg.don.card.entity.Card;
import com.potg.don.card.service.CardService;
import com.potg.don.dummy.service.DummyService;
import com.potg.don.transaction.dto.response.AnalysisTriggerResponse;
import com.potg.don.transaction.dto.response.CsvUploadResponse;
import com.potg.don.transaction.service.CsvService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/cards")
public class CardController {
	private final CardService cardService;
	private final DummyService dummyService;
	private final CsvService csvService;
	private final AnalysisJobService analysisJobService;

	@GetMapping("")
	public ResponseEntity<CardResponse> getCard(@AuthenticationPrincipal CustomUserDetails user) {
		Card card = cardService.getCard(user.getUserId());
		return ResponseEntity.ok(CardResponse.from(card));
	}

	@PostMapping("")
	public ResponseEntity<CardResponse> registerCard(@AuthenticationPrincipal CustomUserDetails user,
		@RequestBody CardRequest cardRequest) {
		Card card = cardService.registerCard(user.getUserId(), cardRequest);
		dummyService.populateFromPool(card.getId());
		CsvUploadResponse csvUploadResponse = csvService.uploadCsvAndSaveFileId(user.getUserId(), card.getId());
		String fileId = csvUploadResponse.getFileId();
		AnalysisTriggerResponse analysisTriggerResponse = csvService.triggerAnalysis(user.getUserId());
		csvService.saveBudgetsFromTriggerResponse(user.getUserId(), analysisTriggerResponse);
		analysisJobService.enqueue(user.getUserId(), fileId);
		return ResponseEntity.status(HttpStatus.CREATED).body(CardResponse.from(card));
	}

	@PatchMapping("")
	public ResponseEntity<CardResponse> updateCard(@AuthenticationPrincipal CustomUserDetails user,
		@RequestBody CardRequest cardRequest) {
		Card card = cardService.updateCard(user.getUserId(), cardRequest);
		dummyService.populateFromPool(card.getId());
		CsvUploadResponse csvUploadResponse = csvService.uploadCsvAndSaveFileId(user.getUserId(), card.getId());
		String fileId = csvUploadResponse.getFileId();
		AnalysisTriggerResponse analysisTriggerResponse = csvService.triggerAnalysis(user.getUserId());
		csvService.saveBudgetsFromTriggerResponse(user.getUserId(), analysisTriggerResponse);
		analysisJobService.enqueue(user.getUserId(), fileId);
		return ResponseEntity.ok(CardResponse.from(card));
	}

	@DeleteMapping("")
	public ResponseEntity<Void> deleteCard(@AuthenticationPrincipal CustomUserDetails user) {
		cardService.deleteCard(user.getUserId());
		return ResponseEntity.noContent().build();
	}
}
