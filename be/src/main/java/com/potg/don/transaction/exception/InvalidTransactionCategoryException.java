package com.potg.don.transaction.exception;

/** An invalid category supplied to the transaction edit boundary. Never includes input values. */
public final class InvalidTransactionCategoryException extends RuntimeException {

	public InvalidTransactionCategoryException() {
		super("지원하는 거래 카테고리를 입력해 주세요.");
	}
}
