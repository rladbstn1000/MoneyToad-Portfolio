package com.potg.don.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.SpringVersion;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potg.don.auth.jwt.JwtAuthenticationFilter;
import com.potg.don.auth.jwt.JwtUtil;
import com.potg.don.transaction.client.CsvClient;
import com.potg.don.transaction.service.CsvService;

/** Real servlet/filter/advice behavior with committed MySQL rows; only CsvClient is replaced. */
@SpringBootTest(properties = "spring.config.location=classpath:application-a1.yml")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(NotFoundContractIntegrationTest.RuntimeFailureProbeConfiguration.class)
class NotFoundContractIntegrationTest {

	private static final long USER_A = 101L;
	private static final long USER_B = 202L;
	private static final long CARD_A = 202L;
	private static final long CARD_B = 101L;
	private static final String CSV_CONTROLLER = "com.potg.don.transaction.controller.CsvTestController";
	private static final String INTERNAL_MARKER = "a2_1_synthetic_internal_failure";
	private static final String FAILURE_PATH = "/__a2_1_probe/runtime-failure";
	private static final String PROBE_BEAN = "notFoundRuntimeFailureProbe";
	private static final Set<String> DELETED_MAPPINGS = Set.of("GET /csv", "PATCH /csv", "GET /csv/trigger", "GET /csv/analysis");
	private static final List<String> TABLES = List.of("users", "cards", "transactions", "budgets", "analysis_job");
	private static final Map<String, Object> NOT_FOUND = Map.of(
		"status", 404, "error", "Not Found", "message", "요청한 리소스를 찾을 수 없습니다.");
	private static final Map<String, Object> UNAUTHORIZED = Map.of(
		"status", 401, "error", "Unauthorized", "message", "인증 토큰이 필요합니다.");
	private static final Map<String, Object> SERVER_ERROR = Map.of(
		"status", 500, "error", "Internal Server Error", "message", "서버 내부 오류가 발생했습니다.");

	@Autowired private MockMvc mvc;
	@Autowired private JwtUtil jwtUtil;
	@Autowired private JwtAuthenticationFilter jwtFilter;
	@Autowired private SecurityFilterChain securityFilterChain;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private DataSource dataSource;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private ConfigurableApplicationContext applicationContext;
	@Autowired private CsvService csvService;
	@Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping mappings;
	@MockBean private CsvClient csvClient;

	@BeforeEach
	void commitSyntheticFixturesAndCheckUnchangedBoundaries() throws Exception {
		StrictCsvClientSupport.resetToRejectEveryExternalMethod(csvClient);
		assertThat(mockingDetails(csvClient).isMock()).isTrue();
		assertThat(applicationContext.getBeansOfType(CsvClient.class)).hasSize(1).containsValue(csvClient);
		assertThat(mockingDetails(csvService).isMock()).isFalse();
		assertThat(securityFilterChain.getFilters()).contains(jwtFilter);
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		try (Connection connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
			assertThat(connection.getMetaData().getURL()).startsWith("jdbc:mysql://127.0.0.1:");
		}
		assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).startsWith("moneytoad_a1");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_job", Long.class)).isZero();
		assertThat(csvBusinessHandlerCount()).isZero();
		assertThat(csvControllerBeanCount()).isZero();
		// This factory method exists only in the explicitly imported nested @TestConfiguration.
		assertThat(applicationContext.getBeanFactory().getBeanDefinition(PROBE_BEAN).getFactoryMethodName())
			.isEqualTo(PROBE_BEAN);
		assertThat(RuntimeFailureProbe.class.isAnnotationPresent(TestComponent.class)).isTrue();
		assertThat(mappings.getHandlerMethods().values().stream()
			.filter(handler -> handler.getBeanType().equals(RuntimeFailureProbe.class)).count()).isEqualTo(1);

		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			jdbc.update("DELETE FROM transactions WHERE card_id IN (?, ?)", CARD_A, CARD_B);
			jdbc.update("DELETE FROM budgets WHERE user_id IN (?, ?)", USER_A, USER_B);
			jdbc.update("DELETE FROM cards WHERE id IN (?, ?)", CARD_A, CARD_B);
			jdbc.update("DELETE FROM users WHERE id IN (?, ?)", USER_A, USER_B);
			insertUser(USER_A, "a2-1-a@example.invalid");
			insertUser(USER_B, "a2-1-b@example.invalid");
			insertCard(CARD_A, USER_A);
			insertCard(CARD_B, USER_B);
			insertTransaction(3001, CARD_A);
			insertTransaction(3002, CARD_A);
			insertTransaction(4001, CARD_B);
			insertTransaction(4002, CARD_B);
			insertBudget(5001, USER_A);
			insertBudget(5002, USER_B);
		});
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
	}

	@ParameterizedTest(name = "authenticated {0} returns generic 404")
	@MethodSource("missingPaths")
	void authenticatedAbsentResourceReturnsGeneric404(String pathCase, HttpMethod method, String path) throws Exception {
		DatabaseSnapshot before = snapshot();
		MvcResult result = mvc.perform(request(method, "/api" + path).contextPath("/api")
			.contentType(MediaType.APPLICATION_JSON).queryParam("detail", "a2_1_synthetic_query")
			.header("X-A2-1-Context", "a2_1_synthetic_header")
			.header("Authorization", bearer())).andReturn();
		DatabaseSnapshot after = snapshot();
		JsonNode body = responseJson(result);
		boolean exactContract = body.equals(json(NOT_FOUND));
		recordEvidence("authenticated_not_found", pathCase, method, result, before, after, exactContract);
		assertAll(
			() -> assertThat(result.getHandler()).isInstanceOf(ResourceHttpRequestHandler.class),
			() -> assertThat(result.getResolvedException()).isInstanceOf(NoResourceFoundException.class),
			() -> assertThat(result.getResponse().getStatus()).isEqualTo(404),
			() -> assertTrue(exactContract, "Expected the exact generic 404 JSON without reflecting request data"),
			() -> assertUnchanged(before, after));
	}

	@ParameterizedTest(name = "missing Bearer for {0} remains 401")
	@MethodSource("missingPaths")
	void missingBearerRemains401BeforeRouting(String pathCase, HttpMethod method, String path) throws Exception {
		DatabaseSnapshot before = snapshot();
		MvcResult result = mvc.perform(request(method, "/api" + path).contextPath("/api")
			.contentType(MediaType.APPLICATION_JSON)).andReturn();
		DatabaseSnapshot after = snapshot();
		boolean exactContract = responseJson(result).equals(json(UNAUTHORIZED));
		recordEvidence("unauthenticated_not_found", pathCase, method, result, before, after, exactContract);
		assertAll(
			() -> assertThat(result.getResponse().getStatus()).isEqualTo(401),
			() -> assertThat(result.getHandler()).isNull(),
			() -> assertTrue(exactContract, "Existing missing-Bearer JSON contract changed"),
			() -> assertUnchanged(before, after));
	}

	@Test
	void unexpectedRuntimeExceptionRemainsGeneric500() throws Exception {
		DatabaseSnapshot before = snapshot();
		MvcResult result = mvc.perform(get("/api" + FAILURE_PATH).contextPath("/api")
			.header("Authorization", bearer())).andReturn();
		DatabaseSnapshot after = snapshot();
		JsonNode body = responseJson(result);
		boolean exactContract = body.equals(json(SERVER_ERROR));
		recordEvidence("unexpected_server_error", "test_only_runtime_failure", HttpMethod.GET,
			result, before, after, exactContract);
		assertAll(
			() -> {
				assertThat(result.getHandler()).isInstanceOf(HandlerMethod.class);
				assertThat(((HandlerMethod)result.getHandler()).getBeanType()).isEqualTo(RuntimeFailureProbe.class);
			},
			() -> assertThat(result.getResolvedException()).isExactlyInstanceOf(RuntimeException.class),
			() -> assertThat(result.getResponse().getStatus()).isEqualTo(500),
			() -> assertTrue(exactContract && !body.toString().contains(INTERNAL_MARKER),
				"Unexpected server failures must retain the generic 500 without internal details"),
			() -> assertUnchanged(before, after));
	}

	@Test
	void localOpenApiJsonStillSucceedsWithoutExternalNavigation() throws Exception {
		DatabaseSnapshot before = snapshot();
		MvcResult result = mvc.perform(get("/api/v3/api-docs").contextPath("/api")).andReturn();
		DatabaseSnapshot after = snapshot();
		JsonNode body = responseJson(result);
		boolean successContract = result.getResponse().getStatus() == 200
			&& body.path("openapi").asText().startsWith("3.")
			&& body.path("paths").isObject() && !body.path("paths").isEmpty();
		recordEvidence("openapi_success", "local_openapi_json", HttpMethod.GET,
			result, before, after, successContract);
		assertAll(
			() -> assertThat(result.getResponse().getStatus()).isEqualTo(200),
			() -> assertThat(result.getHandler()).isInstanceOf(HandlerMethod.class),
			() -> assertThat(result.getResolvedException()).isNull(),
			() -> assertTrue(successContract, "Local OpenAPI metadata should remain available"),
			() -> assertUnchanged(before, after));
	}

	private static Stream<Arguments> missingPaths() {
		return Stream.of(
			Arguments.of("deleted_csv_get", HttpMethod.GET, "/csv"),
			Arguments.of("deleted_csv_patch", HttpMethod.PATCH, "/csv"),
			Arguments.of("deleted_csv_trigger_get", HttpMethod.GET, "/csv/trigger"),
			Arguments.of("deleted_csv_analysis_get", HttpMethod.GET, "/csv/analysis"),
			Arguments.of("unique_missing_get", HttpMethod.GET, "/__a2_1_missing_8471/resource"),
			Arguments.of("unique_missing_patch", HttpMethod.PATCH, "/__a2_1_missing_8471/resource"),
			Arguments.of("missing_static_get", HttpMethod.GET, "/assets/__a2_1_missing_8471.css"));
	}

	private String bearer() {
		return "Bearer " + jwtUtil.createAccessToken(USER_A, "a2-1-a@example.invalid");
	}

	private JsonNode responseJson(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsByteArray());
	}

	private JsonNode json(Map<String, Object> value) throws Exception {
		return objectMapper.readTree(objectMapper.writeValueAsBytes(value));
	}

	private long csvBusinessHandlerCount() {
		return mappings.getHandlerMethods().entrySet().stream().filter(entry ->
			entry.getValue().getBeanType().getName().equals(CSV_CONTROLLER)
				|| entry.getKey().getPatternValues().stream().anyMatch(path ->
					entry.getKey().getMethodsCondition().getMethods().stream()
						.anyMatch(method -> DELETED_MAPPINGS.contains(method.name() + " " + path)))).count();
	}

	private long csvControllerBeanCount() {
		return Stream.of(applicationContext.getBeanDefinitionNames()).map(applicationContext::getType)
			.filter(type -> type != null && type.getName().equals(CSV_CONTROLLER)).count();
	}

	private DatabaseSnapshot snapshot() {
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
		for (String table : TABLES) tables.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
		return new DatabaseSnapshot(tables);
	}

	private void assertUnchanged(DatabaseSnapshot before, DatabaseSnapshot after) {
		assertAll(() -> assertTrue(before.equals(after), "HTTP request changed committed database rows"),
			() -> assertThat(StrictCsvClientSupport.calls(csvClient)).isZero());
	}

	private void recordEvidence(String scenario, String pathCase, HttpMethod method, MvcResult result,
		DatabaseSnapshot before, DatabaseSnapshot after, boolean exactContract) throws Exception {
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("layer", "http");
		evidence.put("scenario", scenario);
		evidence.put("pathCase", pathCase);
		evidence.put("method", method.name());
		evidence.put("authenticated", scenario.equals("authenticated_not_found") || scenario.equals("unexpected_server_error"));
		evidence.put("expectedStatus", switch (scenario) {
			case "authenticated_not_found" -> 404;
			case "unauthenticated_not_found" -> 401;
			case "unexpected_server_error" -> 500;
			case "openapi_success" -> 200;
			default -> throw new IllegalArgumentException("Unknown test evidence scenario");
		});
		evidence.put("httpStatus", result.getResponse().getStatus());
		evidence.put("handler", result.getHandler() == null ? "NONE" : result.getHandler().getClass().getSimpleName());
		evidence.put("exception", result.getResolvedException() == null ? "NONE" : result.getResolvedException().getClass().getSimpleName());
		evidence.put("clientCalls", StrictCsvClientSupport.calls(csvClient));
		evidence.put("dbUnchanged", before.equals(after));
		evidence.put("exactContract", exactContract);
		evidence.put("genericServerErrorContract", objectMapper.readTree(result.getResponse().getContentAsByteArray())
			.equals(objectMapper.valueToTree(SERVER_ERROR)));
		evidence.put("csvBusinessHandlers", csvBusinessHandlerCount());
		evidence.put("csvControllerBeans", csvControllerBeanCount());
		evidence.put("springBootVersion", String.valueOf(SpringBootVersion.getVersion()));
		evidence.put("springFrameworkVersion", String.valueOf(SpringVersion.getVersion()));
		System.out.println("A2_1_EVIDENCE " + objectMapper.writeValueAsString(evidence));
	}

	private void insertUser(long id, String email) {
		jdbc.update("INSERT INTO users (id, email, name, file_id, created_at) VALUES (?, ?, ?, ?, ?)",
			id, email, "A2.1 Synthetic User", "a2-1-synthetic-file", "2026-06-01 09:00:00");
	}

	private void insertCard(long id, long userId) {
		jdbc.update("INSERT INTO cards (id, user_id, card_no, cvc, created_at) VALUES (?, ?, NULL, NULL, ?)",
			id, userId, "2026-06-01 09:00:00");
	}

	private void insertTransaction(long id, long cardId) {
		jdbc.update("INSERT INTO transactions (id, card_id, transaction_date_time, merchant_name, category, amount, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
			id, cardId, "2026-06-03 12:00:00", "A2.1 Synthetic Shop", "식비", 1200, "2026-06-01 09:00:00");
	}

	private void insertBudget(long id, long userId) {
		jdbc.update("INSERT INTO budgets (id, user_id, budget_date, amount, category, initial_amount, initial_file_id, predicted_at, is_overridden) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
			id, userId, "2026-06-01", 1000, "식비", 900, "a2-1-prediction", "2026-05-31 18:00:00", false);
	}

	private record DatabaseSnapshot(Map<String, List<Map<String, Object>>> tables) {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class RuntimeFailureProbeConfiguration {
		@Bean
		RuntimeFailureProbe notFoundRuntimeFailureProbe() {
			return new RuntimeFailureProbe();
		}
	}

	// Boot excludes @TestComponent from ordinary component scans; only the explicit configuration above adds it.
	@TestComponent
	@RestController
	static class RuntimeFailureProbe {
		@GetMapping(FAILURE_PATH)
		String fail() {
			throw new RuntimeException(INTERNAL_MARKER);
		}
	}
}
