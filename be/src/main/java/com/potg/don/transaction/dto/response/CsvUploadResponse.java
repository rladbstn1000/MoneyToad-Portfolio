package com.potg.don.transaction.dto.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CsvUploadResponse {

	@JsonProperty("csv_file")
	private String csvFile;

	@JsonProperty("file_id")
	private String fileId;

	private String checksum;

	@JsonProperty("size_bytes")
	private long sizeBytes;

	@JsonProperty("uploaded_at")
	private String uploadedAt;     // 필요 시 OffsetDateTime으로 변경 가능

	@JsonProperty("replaced_at")
	private String replacedAt;     // null 가능

	@JsonProperty("s3_key")
	private String s3Key;

	@JsonProperty("s3_url")
	private String s3Url;

	private Validation validation;

	// --- nested types ---

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Validation {

		private String status;

		@JsonProperty("total_rows")
		private Integer totalRows;

		@JsonProperty("valid_rows")
		private Integer validRows;

		@JsonProperty("date_range_days")
		private Integer dateRangeDays;

		@JsonProperty("unique_categories")
		private Integer uniqueCategories;

		private List<String> categories;

		@JsonProperty("date_range")
		private DateRange dateRange;

		@JsonProperty("total_amount")
		private Long totalAmount;

		@JsonProperty("validation_errors")
		private List<String> validationErrors;

		@JsonProperty("prophet_warnings")
		private List<String> prophetWarnings;

		@JsonProperty("prophet_errors")
		private List<String> prophetErrors;

		@JsonProperty("prophet_ready")
		private Boolean prophetReady;

		@JsonProperty("baseline_ready")
		private Boolean baselineReady;
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class DateRange {
		private String start;  // 예: "2024-10-01T10:58:00"
		private String end;    // 예: "2025-09-30T19:05:00"
		// 필요하면 OffsetDateTime으로 교체 가능
	}
}
