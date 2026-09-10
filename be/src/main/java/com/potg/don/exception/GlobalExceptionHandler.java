package com.potg.don.exception;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	/**
	 * EntityNotFoundException 처리: 리소스를 찾지 못했을 때 (404 Not Found)
	 */
	@ExceptionHandler(EntityNotFoundException.class)
	public ResponseEntity<Map<String, Object>> handleEntityNotFoundException(EntityNotFoundException ex) {
		return createErrorResponse(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	/**
	 * IllegalStateException 처리: 비즈니스 규칙에 맞지 않을 때 (409 Conflict)
	 * 예: 이미 존재하는 카드를 또 등록하려고 할 때
	 */
	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<Map<String, Object>> handleIllegalStateException(IllegalStateException ex) {
		return createErrorResponse(HttpStatus.CONFLICT, ex.getMessage());
	}

	/**
	 * SecurityException 처리: 인증/보안 관련 오류 (401 Unauthorized)
	 * 예: 유효하지 않은 Refresh Token
	 */
	@ExceptionHandler(SecurityException.class)
	public ResponseEntity<Map<String, Object>> handleSecurityException(SecurityException ex) {
		return createErrorResponse(HttpStatus.UNAUTHORIZED, ex.getMessage());
	}

	/**
	 * 위에서 처리하지 못한 모든 예외 처리 (500 Internal Server Error)
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> handleAllUncaughtException(Exception ex) {
		// 실제 운영 환경에서는 로그를 남기는 것이 중요합니다.
		log.error("Unhandled exception occurred", ex);
		return createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");
	}

	/**
	 * 일관된 형식의 에러 응답을 생성하는 헬퍼 메서드
	 */
	private ResponseEntity<Map<String, Object>> createErrorResponse(HttpStatus status, String message) {
		Map<String, Object> errorDetails = new HashMap<>();
		errorDetails.put("status", status.value());
		errorDetails.put("error", status.getReasonPhrase());
		errorDetails.put("message", message);

		return new ResponseEntity<>(errorDetails, status);
	}
}
