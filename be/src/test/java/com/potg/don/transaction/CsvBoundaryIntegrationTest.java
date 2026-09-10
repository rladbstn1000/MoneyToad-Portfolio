package com.potg.don.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potg.don.auth.jwt.JwtAuthenticationFilter;
import com.potg.don.auth.jwt.JwtUtil;
import com.potg.don.transaction.client.CsvClient;
import com.potg.don.transaction.dto.response.CsvUploadResponse;
import com.potg.don.transaction.service.CsvService;

import jakarta.persistence.EntityNotFoundException;

/** Real services, repositories and committed MySQL fixtures; only the complete HTTP client is replaced. */
@SpringBootTest(properties = "spring.config.location=classpath:application-a1.yml")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CsvBoundaryIntegrationTest {

	private static final long USER_A = 101L;
	private static final long USER_B = 202L;
	private static final long CARD_A = 202L;
	private static final long CARD_B = 101L;
	private static final long MISSING_ID = 9009L;
	private static final List<String> TABLES = List.of("users", "cards", "transactions", "budgets", "analysis_job");
	private static final String CONTROLLER_NAME = "com.potg.don.transaction.controller.CsvTestController";
	private static final Set<String> TARGET_MAPPINGS = Set.of("GET /csv", "PATCH /csv", "GET /csv/trigger", "GET /csv/analysis");
	private static final String CARD_NOT_FOUND = "카드를 찾을 수 없습니다";

	@Autowired private CsvService csvService;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private DataSource dataSource;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private MockMvc mvc;
	@Autowired private JwtUtil jwtUtil;
	@Autowired private JwtAuthenticationFilter jwtFilter;
	@Autowired private SecurityFilterChain securityFilterChain;
	@Autowired private ApplicationContext applicationContext;
	@Autowired private ObjectMapper objectMapper;
	@Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping mappings;
	@MockBean private CsvClient csvClient;

	@BeforeEach
	void commitSyntheticFixturesAndInstallStrictClient() throws Exception {
		StrictCsvClientSupport.resetToRejectEveryExternalMethod(csvClient);
		assertThat(mockingDetails(csvClient).isMock()).isTrue();
		assertThat(applicationContext.getBeansOfType(CsvClient.class)).hasSize(1).containsValue(csvClient);
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		try (Connection connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
			assertThat(connection.getMetaData().getURL()).startsWith("jdbc:mysql://127.0.0.1:");
		}
		assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).startsWith("moneytoad_a1");
		assertThat(securityFilterChain.getFilters()).contains(jwtFilter);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_job", Long.class)).isZero();
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			jdbc.update("DELETE FROM transactions WHERE card_id IN (?, ?)", CARD_A, CARD_B);
			jdbc.update("DELETE FROM budgets WHERE user_id IN (?, ?)", USER_A, USER_B);
			jdbc.update("DELETE FROM cards WHERE id IN (?, ?)", CARD_A, CARD_B);
			jdbc.update("DELETE FROM users WHERE id IN (?, ?)", USER_A, USER_B);
			insertUser(USER_A, "a2-a@example.invalid", "a2-existing-a");
			insertUser(USER_B, "a2-b@example.invalid", "a2-existing-b");
			insertCard(CARD_A, USER_A);
			insertCard(CARD_B, USER_B);
			// Lower primary keys are later in time; neither insertion nor primary-key order satisfies the CSV contract.
			insertTransaction(3001, CARD_A, "2026-06-21 12:30:00", "Synthetic A later", "카페", 700);
			insertTransaction(3002, CARD_A, "2026-06-02 09:15:00", "Synthetic A, early", "식비", 500);
			insertTransaction(4001, CARD_B, "2026-06-23 15:30:00", "Synthetic B later", "카페", 900);
			insertTransaction(4002, CARD_B, "2026-06-03 08:15:00", "Synthetic B, early", "식비", 600);
			insertBudget(5001, USER_A);
			insertBudget(5002, USER_B);
		});
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		clearInvocations(csvClient);
	}

	@Test
	@Order(1)
	void targetControllersAreAbsentAndAuthenticatedRequestsHaveNoEffects() throws Exception {
		List<String> registered = new ArrayList<>();
		mappings.getHandlerMethods().forEach((mapping, handler) -> {
			for (String path : mapping.getPatternValues()) {
				mapping.getMethodsCondition().getMethods().forEach(method -> {
					String key = method.name() + " " + path;
					if (TARGET_MAPPINGS.contains(key)) registered.add(key);
				});
			}
		});
		long controllerBeans = Stream.of(applicationContext.getBeanDefinitionNames())
			.map(applicationContext::getType).filter(type -> type != null && type.getName().equals(CONTROLLER_NAME)).count();
		emit("A2_EVIDENCE ", Map.of("scenario", "csv_handlers", "businessHandlerCount", registered.size(),
			"controllerBeanCount", controllerBeans, "registeredMappings", registered, "httpRequestsExecuted", 0));
		// On RED, stop here. Invoking legacy mappings could enqueue Jobs, so no HTTP is sent while present.
		assertAll(() -> assertThat(registered).as("CSV business mappings").isEmpty(),
			() -> assertThat(controllerBeans).as("CSV controller bean count").isZero());
		for (String mapping : TARGET_MAPPINGS.stream().sorted().toList()) {
			String[] pieces = mapping.split(" ", 2);
			for (boolean authenticated : List.of(true, false)) {
				DatabaseSnapshot before = snapshot();
				var builder = request(HttpMethod.valueOf(pieces[0]), "/api" + pieces[1]).contextPath("/api")
					.contentType(MediaType.APPLICATION_JSON);
				if (authenticated) builder.header("Authorization", "Bearer " + jwtUtil.createAccessToken(USER_A, "a2-a@example.invalid"));
				MvcResult result = mvc.perform(builder).andReturn();
				DatabaseSnapshot after = snapshot();
				emit("A2_EVIDENCE ", Map.of("scenario", "removed_csv_http", "mapping", mapping,
					"authenticated", authenticated, "httpStatus", result.getResponse().getStatus(),
					"handler", result.getHandler() == null ? "NONE" : result.getHandler().getClass().getSimpleName(),
					"exception", result.getResolvedException() == null ? "NONE" : result.getResolvedException().getClass().getSimpleName(),
					"clientCalls", StrictCsvClientSupport.calls(csvClient), "dbUnchanged", before.equals(after)));
				assertAll(
					() -> assertTrue(before.equals(after), "Removed CSV route changed committed database rows"),
					() -> assertThat(StrictCsvClientSupport.calls(csvClient)).isZero(),
					() -> {
						if (authenticated) {
							// A2.1 intentionally changes only the missing-resource response contract.
							assertThat(result.getHandler()).isInstanceOf(ResourceHttpRequestHandler.class);
							assertThat(result.getResolvedException()).isInstanceOf(NoResourceFoundException.class);
							assertThat(result.getResponse().getStatus()).isEqualTo(404);
							assertThat(objectMapper.readTree(result.getResponse().getContentAsByteArray()))
								.isEqualTo(objectMapper.valueToTree(Map.of("status", 404, "error", "Not Found",
									"message", "요청한 리소스를 찾을 수 없습니다.")));
						} else {
							assertThat(result.getResponse().getStatus()).isEqualTo(401);
							assertThat(result.getHandler()).isNull();
						}
					});
			}
		}
	}

	@ParameterizedTest(name = "{0} exports only owner {1} card {2}")
	@MethodSource("ownedCases")
	void ownedCardsExportExactCsvAndChangeOnlyRequestingUsersFileId(Operation operation, long userId, long cardId) throws Exception {
		String oldFileId = fileId(userId);
		String nextFileId = "a2-result-owned-" + userId;
		allowExpectedClientMethod(operation, cardId, oldFileId, nextFileId);
		DatabaseSnapshot before = snapshot();
		CsvUploadResponse response = operation.invoke(csvService, userId, cardId);
		DatabaseSnapshot after = snapshot();
		recordDatabaseEvidence("owned_" + operation.name().toLowerCase(), before, after, "NONE");
		assertThat(response.getFileId()).isEqualTo(nextFileId);
		assertFileIdOnlyChange(before, after, userId, nextFileId);
		verifyCsv(operation, cardId, oldFileId);
	}

	@ParameterizedTest(name = "change keeps upload fallback for missing fileId case {index}")
	@NullAndEmptySource
	@ValueSource(strings = {" "})
	void changeWithoutExistingFileIdKeepsUploadFallback(String initialFileId) throws Exception {
		jdbc.update("UPDATE users SET file_id = ? WHERE id = ?", initialFileId, USER_A);
		allowExpectedClientMethod(Operation.UPLOAD, CARD_A, initialFileId, "a2-fallback-result");
		DatabaseSnapshot before = snapshot();
		csvService.changeCsvAndSaveFileId(USER_A, CARD_A);
		DatabaseSnapshot after = snapshot();
		recordDatabaseEvidence("change_upload_fallback", before, after, "NONE");
		assertFileIdOnlyChange(before, after, USER_A, "a2-fallback-result");
		verifyCsv(Operation.UPLOAD, CARD_A, initialFileId);
	}

	@ParameterizedTest(name = "{0} rejects {1} before export")
	@MethodSource("deniedCases")
	void rejectsUnownedOrMissingTargetsBeforeExport(Operation operation, String target) throws Exception {
		long userId = target.equals("missing_user") ? MISSING_ID : USER_A;
		long cardId = target.equals("foreign") ? CARD_B : target.equals("missing_card") ? MISSING_ID : CARD_A;
		String oldFileId = target.equals("missing_user") ? null : fileId(userId);
		Operation clientMethod = operation == Operation.CHANGE && oldFileId != null ? Operation.CHANGE : Operation.UPLOAD;
		// Explicit synthetic response lets RED expose a committed fileId change, never a real network call.
		allowExpectedClientMethod(clientMethod, cardId, oldFileId, "a2-denied-result");
		DatabaseSnapshot before = snapshot();
		RuntimeException failure = null;
		try {
			operation.invoke(csvService, userId, cardId);
		} catch (RuntimeException exception) {
			failure = exception;
		}
		DatabaseSnapshot after = snapshot();
		recordDatabaseEvidence("denied_" + operation.name().toLowerCase() + "_" + target, before, after,
			failure == null ? "NONE" : failure.getClass().getSimpleName());
		RuntimeException actualFailure = failure;
		assertAll(
			() -> {
				if (target.equals("missing_user")) {
					assertThat(actualFailure).isInstanceOf(IllegalArgumentException.class)
						.hasMessage("사용자를 찾을 수 없습니다: " + userId);
				} else {
					assertThat(actualFailure).isInstanceOf(EntityNotFoundException.class).hasMessage(CARD_NOT_FOUND);
				}
			},
			() -> assertThat(StrictCsvClientSupport.calls(csvClient)).as("client double calls before rejection").isZero(),
			() -> assertTrue(before.equals(after), "Rejected CSV service call changed committed database rows"));
	}

	private static Stream<Arguments> ownedCases() {
		return Stream.of(Operation.values()).flatMap(operation -> Stream.of(
			Arguments.of(operation, USER_A, CARD_A), Arguments.of(operation, USER_B, CARD_B)));
	}

	private static Stream<Arguments> deniedCases() {
		return Stream.of(Operation.values()).flatMap(operation -> Stream.of("foreign", "missing_card", "missing_user")
			.map(target -> Arguments.of(operation, target)));
	}

	private void allowExpectedClientMethod(Operation method, long cardId, String oldFileId, String nextFileId) {
		CsvUploadResponse response = new CsvUploadResponse();
		response.setFileId(nextFileId);
		if (method == Operation.UPLOAD) doReturn(response).when(csvClient).uploadCsv(any(), eq(cardId));
		else doReturn(response).when(csvClient).changeCsv(eq(oldFileId), any(), eq(cardId));
		clearInvocations(csvClient);
	}

	private void verifyCsv(Operation method, long cardId, String oldFileId) throws Exception {
		ArgumentCaptor<byte[]> capture = ArgumentCaptor.forClass(byte[].class);
		if (method == Operation.UPLOAD) verify(csvClient).uploadCsv(capture.capture(), eq(cardId));
		else verify(csvClient).changeCsv(eq(oldFileId), capture.capture(), eq(cardId));
		verifyNoMoreInteractions(csvClient);
		String text = new String(capture.getValue(), StandardCharsets.UTF_8);
		assertTrue(text.startsWith("\uFEFF"), "CSV UTF-8 BOM missing");
		try (CSVParser parser = CSVParser.parse(text.substring(1), CSVFormat.DEFAULT.builder()
			.setHeader().setSkipHeaderRecord(true).build())) {
			assertThat(parser.getHeaderNames()).containsExactly("transaction_id", "transaction_date_time", "merchant_name", "category", "amount");
			List<List<String>> actual = parser.getRecords().stream().map(row -> row.toList()).toList();
			List<List<String>> expected = cardId == CARD_A
				? List.of(List.of("3002", "2026-06-02T09:15", "Synthetic A, early", "식비", "500"),
					List.of("3001", "2026-06-21T12:30", "Synthetic A later", "카페", "700"))
				: List.of(List.of("4002", "2026-06-03T08:15", "Synthetic B, early", "식비", "600"),
					List.of("4001", "2026-06-23T15:30", "Synthetic B later", "카페", "900"));
			assertTrue(actual.equals(expected), "CSV must contain the two owned transactions, original columns and chronological order");
			emit("A2_EVIDENCE ", Map.of("scenario", "owned_csv", "cardId", cardId,
				"userId", cardId == CARD_A ? USER_A : USER_B, "csvBranch", method.name().toLowerCase(),
				"csvRows", actual.size(), "csvHeaderValid", true, "csvSorted", true, "expectedClientArguments", true));
		}
	}

	private void assertFileIdOnlyChange(DatabaseSnapshot before, DatabaseSnapshot after, long userId, String expectedFileId) {
		for (String table : TABLES) {
			if (!table.equals("users")) {
				assertTrue(before.tables().get(table).equals(after.tables().get(table)), "Unexpected database change in " + table);
				continue;
			}
			List<Map<String, Object>> oldRows = before.tables().get(table);
			List<Map<String, Object>> newRows = after.tables().get(table);
			assertThat(newRows.size()).isEqualTo(oldRows.size());
			for (int index = 0; index < oldRows.size(); index++) {
				Map<String, Object> oldRow = oldRows.get(index);
				Map<String, Object> newRow = newRows.get(index);
				Map<String, Object> expected = new LinkedHashMap<>(oldRow);
				if (((Number)oldRow.get("id")).longValue() == userId) expected.put("file_id", expectedFileId);
				assertTrue(expected.equals(newRow), "Only the requesting user's file_id may change");
			}
		}
	}

	private DatabaseSnapshot snapshot() {
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
		for (String table : TABLES) tables.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
		return new DatabaseSnapshot(tables);
	}

	private void recordDatabaseEvidence(String scenario, DatabaseSnapshot before, DatabaseSnapshot after, String exception) throws Exception {
		List<String> changedTables = TABLES.stream().filter(table -> !before.tables().get(table).equals(after.tables().get(table))).toList();
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String table : TABLES) counts.put(table, after.tables().get(table).size());
		emit("A2_DB_EVIDENCE ", Map.of("scenario", scenario, "clientCalls", StrictCsvClientSupport.calls(csvClient),
			"dbUnchanged", before.equals(after), "changedTables", changedTables, "exception", exception, "rowCounts", counts));
	}

	private void emit(String prefix, Map<String, Object> summary) throws Exception {
		System.out.println(prefix + objectMapper.writeValueAsString(summary));
	}

	private String fileId(long userId) {
		return jdbc.queryForObject("SELECT file_id FROM users WHERE id = ?", String.class, userId);
	}

	private void insertUser(long id, String email, String fileId) {
		jdbc.update("INSERT INTO users (id, email, name, file_id, created_at) VALUES (?, ?, ?, ?, ?)",
			id, email, "A2 Synthetic User", fileId, "2026-06-01 09:00:00");
	}

	private void insertCard(long id, long userId) {
		// No card number or CVC is required for this boundary; do not collect even synthetic values.
		jdbc.update("INSERT INTO cards (id, user_id, card_no, cvc, created_at) VALUES (?, ?, NULL, NULL, ?)",
			id, userId, "2026-06-01 09:00:00");
	}

	private void insertTransaction(long id, long cardId, String at, String merchant, String category, int amount) {
		jdbc.update("INSERT INTO transactions (id, card_id, transaction_date_time, merchant_name, category, amount, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
			id, cardId, at, merchant, category, amount, "2026-06-01 09:00:00");
	}

	private void insertBudget(long id, long userId) {
		jdbc.update("INSERT INTO budgets (id, user_id, budget_date, amount, category, initial_amount, initial_file_id, predicted_at, is_overridden) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
			id, userId, "2026-06-01", 1000, "식비", 900, "a2-budget-snapshot", "2026-05-31 18:00:00", false);
	}

	private record DatabaseSnapshot(Map<String, List<Map<String, Object>>> tables) {
	}

	private enum Operation {
		UPLOAD, CHANGE;
		CsvUploadResponse invoke(CsvService service, long userId, long cardId) {
			return this == UPLOAD ? service.uploadCsvAndSaveFileId(userId, cardId) : service.changeCsvAndSaveFileId(userId, cardId);
		}
	}
}
