package com.potg.don.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.method.HandlerMethod;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potg.don.auth.jwt.JwtAuthenticationFilter;
import com.potg.don.auth.jwt.JwtUtil;
import com.potg.don.budget.controller.BudgetController;

import jakarta.persistence.EntityNotFoundException;

/** Real servlet filters and MySQL; intentionally no test-managed rollback transaction. */
@SpringBootTest(properties = "spring.config.location=classpath:application-a1.yml")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BudgetOwnershipIntegrationTest {

	private static final long USER_A = 101L;
	private static final long USER_B = 202L;
	private static final long BUDGET_A = 1001L;
	private static final long BUDGET_B = 2002L;
	private static final long OTHER_BUDGET_A = 3003L;
	private static final long MISSING_BUDGET = 9009L;
	private static final Set<String> MUTABLE_COLUMNS = Set.of("amount", "is_overridden", "overridden_at");
	private static final Map<String, Object> NOT_FOUND = Map.of(
		"status", 404, "error", "Not Found", "message", "누수를 찾을 수 없습니다");

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private JwtUtil jwtUtil;
	@Autowired private JwtAuthenticationFilter jwtFilter;
	@Autowired private SecurityFilterChain securityFilterChain;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private DataSource dataSource;
	@Autowired private PlatformTransactionManager transactionManager;

	@BeforeEach
	void commitSyntheticFixtures() throws Exception {
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		try (Connection connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
			assertThat(connection.getMetaData().getURL()).startsWith("jdbc:mysql://127.0.0.1:");
		}
		assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).startsWith("moneytoad_a1");
		assertThat(securityFilterChain.getFilters()).contains(jwtFilter);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_job", Long.class)).isZero();

		// This transaction ends BEFORE MockMvc; after snapshots use fresh JDBC reads.
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			jdbc.update("DELETE FROM budgets WHERE id IN (?, ?, ?)", BUDGET_A, BUDGET_B, OTHER_BUDGET_A);
			jdbc.update("DELETE FROM users WHERE id IN (?, ?)", USER_A, USER_B);
			insertUser(USER_A, "a1-a@example.invalid", "Synthetic A");
			insertUser(USER_B, "a1-b@example.invalid", "Synthetic B");
			insertBudget(BUDGET_A, USER_A, 11000, "식비");
			insertBudget(BUDGET_B, USER_B, 22000, "카페");
			insertBudget(OTHER_BUDGET_A, USER_A, 33000, "교통 / 차량");
		});
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
	}

	@Test
	@Order(1)
	void ownerAUpdatesOnlyMutableFields() throws Exception {
		assertOwnerUpdate("owner_a", USER_A, BUDGET_A, 15000);
	}

	@Test
	@Order(2)
	void foreignBudgetReturnsSameGeneric404AndPreservesEveryRow() throws Exception {
		List<Map<String, Object>> before = snapshot();
		MvcResult result = request(USER_A, BUDGET_B, 77777);
		List<Map<String, Object>> after = snapshot();
		recordEvidence("foreign_budget", result, before, after);
		// Both this and missingBudget use the EXACT same error JSON contract.
		assertAll(
			() -> assertBudgetHandler(result),
			() -> assertThat(result.getResponse().getStatus()).as("foreign budget status").isEqualTo(404),
			() -> assertThat(result.getResolvedException()).isInstanceOf(EntityNotFoundException.class),
			() -> assertThat(responseJson(result)).isEqualTo(objectMapper.valueToTree(NOT_FOUND)),
			() -> assertThat(after).as("all committed budget columns after foreign request").isEqualTo(before)
		);
	}

	@Test
	@Order(3)
	void missingBudgetReturnsSameGeneric404AndPreservesEveryRow() throws Exception {
		List<Map<String, Object>> before = snapshot();
		MvcResult result = request(USER_A, MISSING_BUDGET, 77777);
		List<Map<String, Object>> after = snapshot();
		recordEvidence("missing_budget", result, before, after);
		assertAll(
			() -> assertBudgetHandler(result),
			() -> assertThat(result.getResponse().getStatus()).isEqualTo(404),
			() -> assertThat(result.getResolvedException()).isInstanceOf(EntityNotFoundException.class),
			() -> assertThat(responseJson(result)).isEqualTo(objectMapper.valueToTree(NOT_FOUND)),
			() -> assertThat(after).isEqualTo(before)
		);
	}

	@Test
	@Order(4)
	void missingBearerIsRejectedByExistingJwtFilterWithoutWrites() throws Exception {
		List<Map<String, Object>> before = snapshot();
		MvcResult result = request(null, BUDGET_A, 77777);
		List<Map<String, Object>> after = snapshot();
		recordEvidence("missing_bearer", result, before, after);
		assertAll(
			() -> assertThat(result.getResponse().getStatus()).isEqualTo(401),
			() -> assertThat(result.getHandler()).isNull(),
			() -> assertThat(responseJson(result)).isEqualTo(objectMapper.valueToTree(Map.of(
				"status", 401, "error", "Unauthorized", "message", "인증 토큰이 필요합니다."))),
			() -> assertThat(after).isEqualTo(before)
		);
	}

	@Test
	@Order(5)
	void ownerBUpdatesOnlyMutableFields() throws Exception {
		assertOwnerUpdate("owner_b", USER_B, BUDGET_B, 25000);
	}

	private void assertOwnerUpdate(String scenario, long userId, long budgetId, int amount) throws Exception {
		List<Map<String, Object>> before = snapshot();
		MvcResult result = request(userId, budgetId, amount);
		List<Map<String, Object>> after = snapshot();
		recordEvidence(scenario, result, before, after);
		assertBudgetHandler(result);
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		JsonNode expectedResponse = objectMapper.readTree(
			objectMapper.writeValueAsBytes(Map.of("budgetId", budgetId, "budget", amount)));
		assertThat(responseJson(result)).isEqualTo(expectedResponse);
		assertThat(after).hasSize(before.size());
		for (int i = 0; i < before.size(); i++) {
			Map<String, Object> oldRow = before.get(i);
			Map<String, Object> newRow = after.get(i);
			if (((Number)oldRow.get("id")).longValue() != budgetId) {
				assertThat(newRow).isEqualTo(oldRow);
				continue;
			}
			assertThat(newRow.get("amount")).isEqualTo(amount);
			assertThat(newRow.get("is_overridden")).isEqualTo(true);
			assertThat(newRow.get("overridden_at")).isNotNull().isNotEqualTo(oldRow.get("overridden_at"));
			Map<String, Object> preserved = new LinkedHashMap<>(newRow);
			MUTABLE_COLUMNS.forEach(column -> preserved.put(column, oldRow.get(column)));
			assertThat(preserved).as("initial prediction, ownership and other columns preserved").isEqualTo(oldRow);
		}
	}

	private MvcResult request(Long userId, long budgetId, int amount) throws Exception {
		MockHttpServletRequestBuilder request = patch("/api/budgets").contextPath("/api")
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("budgetId", budgetId, "budget", amount)));
		if (userId != null) {
			String email = userId == USER_A ? "a1-a@example.invalid" : "a1-b@example.invalid";
			request.header("Authorization", "Bearer " + jwtUtil.createAccessToken(userId, email));
		}
		return mvc.perform(request).andReturn();
	}

	private void assertBudgetHandler(MvcResult result) {
		assertThat(result.getHandler()).isInstanceOf(HandlerMethod.class);
		HandlerMethod handler = (HandlerMethod)result.getHandler();
		assertThat(handler.getBeanType()).isEqualTo(BudgetController.class);
		assertThat(handler.getMethod().getName()).isEqualTo("updateBudget");
	}

	private JsonNode responseJson(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsByteArray());
	}

	private List<Map<String, Object>> snapshot() {
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		return jdbc.queryForList("SELECT * FROM budgets ORDER BY id");
	}

	private void insertUser(long id, String email, String name) {
		jdbc.update("INSERT INTO users (id, email, name, created_at) VALUES (?, ?, ?, ?)",
			id, email, name, "2025-08-01 09:00:00");
	}

	private void insertBudget(long id, long userId, int amount, String category) {
		jdbc.update("""
			INSERT INTO budgets (id, user_id, budget_date, amount, category, initial_amount,
			    initial_file_id, predicted_at, is_overridden, overridden_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""", id, userId, "2025-08-01", amount, category, amount - 1000,
			"a1-synthetic-" + id, "2025-07-31 18:30:00", false, null);
	}

	private void recordEvidence(String scenario, MvcResult result, List<Map<String, Object>> before,
		List<Map<String, Object>> after) throws Exception {
		List<Map<String, Object>> changes = new ArrayList<>();
		for (int i = 0; i < Math.min(before.size(), after.size()); i++) {
			Map<String, Object> oldRow = before.get(i);
			Map<String, Object> newRow = after.get(i);
			List<String> columns = oldRow.keySet().stream()
				.filter(column -> !java.util.Objects.equals(oldRow.get(column), newRow.get(column))).sorted().toList();
			if (!columns.isEmpty()) {
				changes.add(Map.of("budgetId", oldRow.get("id"), "columns", columns,
					"amountBefore", oldRow.get("amount"), "amountAfter", newRow.get("amount")));
			}
		}
		// Deliberately excludes request headers, tokens, secrets and user information.
		System.out.println("A1_DB_EVIDENCE " + objectMapper.writeValueAsString(Map.of(
			"scenario", scenario, "httpStatus", result.getResponse().getStatus(), "changedRows", changes.size(),
			"changes", changes, "rowsBefore", before.size(), "rowsAfter", after.size())));
	}
}
