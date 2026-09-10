package com.potg.don.transaction.dto.response;

// 베이스라인 응답

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
public class BaselineResponse {
	@JsonProperty("file_id")
	private String fileId;

	@JsonProperty("baseline_months")
	private List<BaselineMonth> baselineMonths;

	@JsonProperty("months_count")
	private Integer monthsCount;

	@JsonProperty("category_filter")
	private String categoryFilter;

	@Data
	public static class BaselineMonth {
		private Integer month;
		private Integer year;

		@JsonProperty("total_predicted")
		private Double totalPredicted;

		@JsonProperty("categories_count")
		private Integer categoriesCount;

		@JsonProperty("category_predictions")
		private Map<String, CategoryPrediction> categoryPredictions;

		@JsonProperty("training_data_until")
		private String trainingDataUntil;
	}

	@Data
	public static class CategoryPrediction {
		@JsonProperty("predicted_amount")
		private Double predictedAmount;

		@JsonProperty("lower_bound")
		private Double lowerBound;

		@JsonProperty("upper_bound")
		private Double upperBound;
	}
}

