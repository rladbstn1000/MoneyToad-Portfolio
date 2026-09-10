package com.potg.don.transaction.dto.response;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnalysisTriggerResponse {

	@JsonProperty("file_id")
	private String fileId;

	private Integer year;
	private Integer month;

	@JsonProperty("leak_amount")
	private Double leakAmount;

	@JsonProperty("transactions_count")
	private Integer transactionsCount;

	private Details details;

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Details {

		@JsonProperty("total_predicted")
		private Double totalPredicted;

		@JsonProperty("categories_count")
		private Integer categoriesCount;

		@JsonProperty("category_predictions")
		private Map<String, CategoryPrediction> categoryPredictions;

		@JsonProperty("prediction_date")
		private String predictionDate; // 예: "2025-09-01"

		@JsonProperty("created_at")
		private String createdAt;      // 예: "2025-09-25T02:19:16"
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class CategoryPrediction {

		@JsonProperty("predicted_amount")
		private Double predictedAmount;

		@JsonProperty("lower_bound")
		private Double lowerBound;

		@JsonProperty("upper_bound")
		private Double upperBound;
	}
}
