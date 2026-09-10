package com.potg.don.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.ServletException;

/** Direct MVC exception-method resolution and invocation, not HTTP or database evidence. */
class GlobalExceptionHandlerContractTest {
	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
	private final ExceptionHandlerMethodResolver resolver = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

	@ParameterizedTest(name = "{0} has generic missing-resource response")
	@MethodSource("missingPathExceptions")
	void concreteMissingPathExceptionsHaveGeneric404(String scenario, Exception exception) throws Exception {
		ResponseEntity<?> response = dispatch(exception);
		emit(scenario, exception, response, 404);
		assertThat(response.getStatusCode().value()).isEqualTo(404);
		assertThat(response.getBody()).isEqualTo(Map.of("status", 404, "error", "Not Found",
			"message", "요청한 리소스를 찾을 수 없습니다."));
	}

	@ParameterizedTest(name = "{0} retains its existing domain error contract")
	@MethodSource("existingDomainExceptions")
	void existingDomainMessagesAndStatusesArePreserved(String scenario, Exception exception, int status,
		String error, String message) throws Exception {
		ResponseEntity<?> response = dispatch(exception);
		emit(scenario, exception, response, status);
		assertThat(response.getStatusCode().value()).isEqualTo(status);
		assertThat(response.getBody()).isEqualTo(Map.of("status", status, "error", error, "message", message));
	}

	@ParameterizedTest(name = "{0} remains a generic server error")
	@MethodSource("unexpectedExceptions")
	void unexpectedFailuresAreNotAbsorbedByNotFoundHandling(String scenario, Exception exception) throws Exception {
		ResponseEntity<?> response = dispatch(exception);
		emit(scenario, exception, response, 500);
		assertThat(response.getStatusCode().value()).isEqualTo(500);
		assertThat(response.getBody()).isEqualTo(Map.of("status", 500, "error", "Internal Server Error",
			"message", "서버 내부 오류가 발생했습니다."));
	}

	private ResponseEntity<?> dispatch(Exception exception) throws Exception {
		// Works before the new handler exists: RED resolves the current catch-all method.
		Method method = resolver.resolveMethod(exception);
		assertThat(method).isNotNull();
		Object[] arguments = method.getParameterCount() == 0 ? new Object[0] : new Object[] {exception};
		return (ResponseEntity<?>)method.invoke(handler, arguments);
	}

	private static Stream<Arguments> missingPathExceptions() {
		HttpHeaders headers = new HttpHeaders();
		headers.add("X-Synthetic-Internal", "synthetic-header-marker");
		return Stream.of(
			Arguments.of("mvc_no_resource", new NoResourceFoundException(HttpMethod.PATCH,
				"synthetic-private-path?probe=synthetic-query-marker")),
			Arguments.of("mvc_no_handler", new NoHandlerFoundException("GET",
				"/synthetic-private-path?probe=synthetic-query-marker", headers)));
	}

	private static Stream<Arguments> existingDomainExceptions() {
		return Stream.of(
			Arguments.of("entity_not_found", new EntityNotFoundException("카드를 찾을 수 없습니다"),
				404, "Not Found", "카드를 찾을 수 없습니다"),
			Arguments.of("security_exception", new SecurityException("synthetic security rejection"),
				401, "Unauthorized", "synthetic security rejection"),
			Arguments.of("illegal_state", new IllegalStateException("synthetic state conflict"),
				409, "Conflict", "synthetic state conflict"));
	}

	private static Stream<Arguments> unexpectedExceptions() {
		return Stream.of(
			Arguments.of("runtime_error", new RuntimeException("synthetic-internal-runtime-marker")),
			Arguments.of("servlet_error", new ServletException("synthetic-internal-servlet-marker")));
	}

	private void emit(String scenario, Exception exception, ResponseEntity<?> response, int expected) throws Exception {
		System.out.println("A2_1_EVIDENCE " + new ObjectMapper().writeValueAsString(Map.of(
			"scenario", scenario, "layer", "direct_resolver", "exception", exception.getClass().getSimpleName(),
			"httpStatus", response.getStatusCode().value(), "expectedStatus", expected)));
	}
}
