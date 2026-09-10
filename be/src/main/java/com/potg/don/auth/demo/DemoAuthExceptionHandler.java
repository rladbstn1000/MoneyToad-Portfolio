package com.potg.don.auth.demo;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice(assignableTypes = DemoAuthController.class)
@Profile("demo")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DemoAuthExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(DemoAuthExceptionHandler.class);
	private final DemoRefreshCookie cookies;

	public DemoAuthExceptionHandler(DemoRefreshCookie cookies) { this.cookies = cookies; }

	@ExceptionHandler(DemoAuthException.class)
	public ResponseEntity<Map<String, Object>> auth(DemoAuthException failure, HttpServletRequest request) {
		HttpStatus status = failure.reason() == DemoAuthException.Reason.DEMO_SESSION_UNAVAILABLE
			? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED;
		return error(status, request);
	}

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Map<String, Object>> request(ResponseStatusException failure, HttpServletRequest request) {
		HttpStatus status = HttpStatus.resolve(failure.getStatusCode().value());
		if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
		return error(status, request);
	}

	@ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessResourceException.class,
		QueryTimeoutException.class, CannotCreateTransactionException.class, TransactionSystemException.class,
		TransactionTimedOutException.class, UnexpectedRollbackException.class})
	public ResponseEntity<Map<String, Object>> unavailable(Exception failure, HttpServletRequest request) {
		return error(HttpStatus.SERVICE_UNAVAILABLE, request);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> unexpected(Exception failure, HttpServletRequest request) {
		log.error("DEMO_AUTH_HTTP_INTERNAL_ERROR");
		return error(HttpStatus.INTERNAL_SERVER_ERROR, request);
	}

	private ResponseEntity<Map<String, Object>> error(HttpStatus status, HttpServletRequest request) {
		String message = switch (status) {
			case BAD_REQUEST -> "잘못된 데모 요청입니다.";
			case UNAUTHORIZED -> "데모 인증을 다시 시작해 주세요.";
			case FORBIDDEN -> "허용되지 않은 데모 요청입니다.";
			case CONFLICT -> "이미 활성화된 데모 세션이 있습니다.";
			case PAYLOAD_TOO_LARGE -> "데모 요청 크기가 너무 큽니다.";
			case UNSUPPORTED_MEDIA_TYPE -> "지원하지 않는 데모 요청 형식입니다.";
			case SERVICE_UNAVAILABLE -> "데모 인증 서비스를 사용할 수 없습니다.";
			default -> "서버 내부 오류가 발생했습니다.";
		};
		ResponseEntity.BodyBuilder response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
		String path = request.getRequestURI().substring(request.getContextPath().length());
		if (status == HttpStatus.UNAUTHORIZED && "POST".equals(request.getMethod())
			&& ("/auth/demo/login".equals(path) || "/auth/demo/reissue".equals(path))) {
			response.header(HttpHeaders.SET_COOKIE, cookies.delete().toString());
		}
		return response.body(Map.of("status", status.value(), "error", status.getReasonPhrase(), "message", message));
	}
}
