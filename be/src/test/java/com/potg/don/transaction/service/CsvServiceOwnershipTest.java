package com.potg.don.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.potg.don.budget.repository.BudgetRepository;
import com.potg.don.card.entity.Card;
import com.potg.don.card.repository.CardRepository;
import com.potg.don.transaction.client.CsvClient;
import com.potg.don.transaction.dto.response.CsvUploadResponse;
import com.potg.don.transaction.entity.Transaction;
import com.potg.don.transaction.repository.TransactionRepository;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;

import jakarta.persistence.EntityNotFoundException;

/** Interaction ordering only; committed database invariance is tested separately. */
@ExtendWith(MockitoExtension.class)
class CsvServiceOwnershipTest {
	@Mock private CsvClient client;
	@Mock private UserRepository users;
	@Mock private TransactionRepository transactions;
	@Mock private BudgetRepository budgets;
	@Mock private CardRepository cards;
	private CsvService service;
	private User user;

	@BeforeEach
	void setUp() {
		service = new CsvService(client, users, transactions, budgets, cards);
		user = spy(User.createUser("a2-unit@example.invalid", "Synthetic A2"));
		ReflectionTestUtils.setField(user, "id", 101L);
		user.updateFileId("a2-old-file");
		clearInvocations(user);
	}

	@ParameterizedTest(name = "{0} rejects card {1} before transaction access")
	@CsvSource({"upload, 101", "upload, 909", "change, 101", "change, 909"})
	void rejectsForeignAndMissingCardsWithoutReadsOrWrites(String operation, long cardId) {
		// Valid cross fixture elsewhere is user 202/card 101, never inferred from cardId.
		when(users.findById(101L)).thenReturn(Optional.of(user));
		when(cards.findByIdAndUser_Id(cardId, 101L)).thenReturn(Optional.empty());

		EntityNotFoundException failure = assertThrows(EntityNotFoundException.class,
			() -> invoke(operation, 101L, cardId));

		assertThat(failure).hasMessage("카드를 찾을 수 없습니다");
		var order = inOrder(users, cards);
		order.verify(users).findById(101L);
		order.verify(cards).findByIdAndUser_Id(cardId, 101L);
		verifyNoMoreInteractions(users, cards);
		verifyNoInteractions(transactions, client, budgets);
		verify(user, never()).updateFileId(any());
		assertThat(user.getFileId()).isEqualTo("a2-old-file");
	}

	@ParameterizedTest(name = "{0} validates user before all export work")
	@ValueSource(strings = {"upload", "change"})
	void rejectsMissingUserBeforeCardOrTransactionAccess(String operation) {
		when(users.findById(909L)).thenReturn(Optional.empty());

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
			() -> invoke(operation, 909L, 202L));

		assertThat(failure).hasMessage("사용자를 찾을 수 없습니다: 909");
		verify(users).findById(909L);
		verifyNoMoreInteractions(users);
		verifyNoInteractions(cards, transactions, client, budgets, user);
	}

	@Test
	void uploadChecksOwnershipBeforeReadingAndCallingClient() {
		owned();
		CsvUploadResponse response = response();
		when(client.uploadCsv(any(byte[].class), eq(202L))).thenReturn(response);

		assertThat(service.uploadCsvAndSaveFileId(101L, 202L)).isSameAs(response);

		var order = inOrder(users, cards, transactions, client, user);
		order.verify(users).findById(101L);
		order.verify(cards).findByIdAndUser_Id(202L, 101L);
		order.verify(transactions).findAllByCard_IdOrderByTransactionDateTimeAsc(202L);
		order.verify(client).uploadCsv(any(byte[].class), eq(202L));
		order.verify(user).updateFileId("a2-new-file");
		assertThat(user.getFileId()).isEqualTo("a2-new-file");
		verifyNoMoreInteractions(users, cards, transactions, client);
		verifyNoInteractions(budgets);
	}

	@Test
	void changeUsesTheValidatedUsersExistingFileId() {
		owned();
		when(client.changeCsv(eq("a2-old-file"), any(byte[].class), eq(202L))).thenReturn(response());

		service.changeCsvAndSaveFileId(101L, 202L);

		var order = inOrder(users, cards, transactions, client);
		order.verify(users).findById(101L);
		order.verify(cards).findByIdAndUser_Id(202L, 101L);
		order.verify(transactions).findAllByCard_IdOrderByTransactionDateTimeAsc(202L);
		order.verify(client).changeCsv(eq("a2-old-file"), any(byte[].class), eq(202L));
		verifyNoMoreInteractions(users, cards, transactions, client);
		assertThat(user.getFileId()).isEqualTo("a2-new-file");
		verifyNoInteractions(budgets);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = " ")
	void changeKeepsUploadFallbackWhenFileIdIsAbsent(String priorFileId) {
		user.updateFileId(priorFileId);
		owned();
		when(client.uploadCsv(any(byte[].class), eq(202L))).thenReturn(response());

		service.changeCsvAndSaveFileId(101L, 202L);

		verify(client).uploadCsv(any(byte[].class), eq(202L));
		verifyNoMoreInteractions(client);
		verify(cards).findByIdAndUser_Id(202L, 101L);
		verify(cards, never()).findById(anyLong());
		assertThat(user.getFileId()).isEqualTo("a2-new-file");
	}

	private void owned() {
		when(users.findById(101L)).thenReturn(Optional.of(user));
		when(cards.findByIdAndUser_Id(202L, 101L)).thenReturn(Optional.of(mock(Card.class)));
		when(transactions.findAllByCard_IdOrderByTransactionDateTimeAsc(202L)).thenReturn(List.of(
			Transaction.builder().id(1001L).transactionDateTime(LocalDateTime.of(2026, 9, 1, 12, 0))
				.merchantName("Synthetic shop").category("식비").amount(1200).build()));
	}

	private CsvUploadResponse response() {
		CsvUploadResponse response = new CsvUploadResponse();
		response.setFileId("a2-new-file");
		return response;
	}

	private CsvUploadResponse invoke(String operation, long userId, long cardId) {
		return operation.equals("upload") ? service.uploadCsvAndSaveFileId(userId, cardId)
			: service.changeCsvAndSaveFileId(userId, cardId);
	}
}
