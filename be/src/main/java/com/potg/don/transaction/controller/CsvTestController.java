package com.potg.don.transaction.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.potg.don.analysisJob.service.AnalysisJobService;
import com.potg.don.auth.entity.CustomUserDetails;
import com.potg.don.card.entity.Card;
import com.potg.don.card.service.CardService;
import com.potg.don.transaction.dto.response.AnalysisTriggerResponse;
import com.potg.don.transaction.dto.response.CsvUploadResponse;
import com.potg.don.transaction.service.CsvService;
import com.potg.don.user.entity.User;
import com.potg.don.user.service.UserService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/csv")
public class CsvTestController {
	private final CsvService csvService;
	private final CardService cardService;
	private final AnalysisJobService analysisJobService;
	private final UserService userService;

	@GetMapping("")
	ResponseEntity<CsvUploadResponse> uploadCsv(@AuthenticationPrincipal CustomUserDetails user) {
		Card card = cardService.getCard(user.getUserId());
		return ResponseEntity.ok(csvService.uploadCsvAndSaveFileId(card.getId(), user.getUserId()));
	}

	@PatchMapping("")
	ResponseEntity<CsvUploadResponse> changeCsv(@AuthenticationPrincipal CustomUserDetails user) {
		Card card = cardService.getCard(user.getUserId());
		return ResponseEntity.ok(csvService.changeCsvAndSaveFileId(card.getId(), user.getUserId()));
	}

	@GetMapping("/trigger")
	ResponseEntity<AnalysisTriggerResponse> triggerCsv(@AuthenticationPrincipal CustomUserDetails user) {
		Card card = cardService.getCard(user.getUserId());
		AnalysisTriggerResponse analysisTriggerResponse = csvService.triggerAnalysis(user.getUserId());
		csvService.saveBudgetsFromTriggerResponse(card.getId(), analysisTriggerResponse);
		return ResponseEntity.ok(analysisTriggerResponse);
	}

	@GetMapping("/analysis")
	ResponseEntity<Void> run(@AuthenticationPrincipal CustomUserDetails user) {
		User me = userService.getUser(user.getUserId());
		analysisJobService.enqueue(me.getId(), me.getFileId());
		return ResponseEntity.accepted().build(); // 202 }
	}
}