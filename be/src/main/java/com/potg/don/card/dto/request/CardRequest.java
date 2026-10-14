package com.potg.don.card.dto.request;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import lombok.Getter;

@Getter
@JsonDeserialize(using = CardRequest.Deserializer.class)
public class CardRequest {
	private String cardNo;

	/** Reject the legacy field before advancing the parser to its value. */
	public static final class Deserializer extends JsonDeserializer<CardRequest> {
		@Override
		public CardRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
			try {
				JsonToken token = parser.currentToken();
				if (token == JsonToken.START_OBJECT) token = parser.nextToken();
				CardRequest request = new CardRequest();
				while (token == JsonToken.FIELD_NAME) {
					String field = parser.currentName();
					if ("cvc".equals(field)) throw invalid();
					if (parser.nextToken() == null) throw invalid();
					if ("cardNo".equals(field)) {
						// Keep the existing String coercion and null semantics for card numbers.
						request.cardNo = parser.currentToken() == JsonToken.VALUE_NULL
							? null : context.readValue(parser, String.class);
					} else {
						// Preserve the selected mapper's existing unknown-property policy.
						context.handleUnknownProperty(parser, this, request, field);
					}
					token = parser.nextToken();
				}
				if (token != JsonToken.END_OBJECT) throw invalid();
				return request;
			} catch (JsonProcessingException error) {
				// Do not retain a parser/source reference, submitted value or original cause.
				throw invalid();
			}
		}

		private static MismatchedInputException invalid() {
			return MismatchedInputException.from((JsonParser)null, CardRequest.class, "CARD_REQUEST_INVALID");
		}
	}
}
