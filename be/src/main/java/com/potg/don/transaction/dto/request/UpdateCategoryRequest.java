package com.potg.don.transaction.dto.request;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.potg.don.transaction.exception.InvalidTransactionCategoryException;

import lombok.Getter;

@Getter
public class UpdateCategoryRequest {
	private String category;

	@JsonSetter("category")
	public void readCategory(JsonNode value) {
		// Jackson otherwise coerces JSON numbers/booleans to Strings before service validation.
		if (value == null || !value.isTextual()) {
			throw new InvalidTransactionCategoryException();
		}
		category = value.textValue();
	}
}
