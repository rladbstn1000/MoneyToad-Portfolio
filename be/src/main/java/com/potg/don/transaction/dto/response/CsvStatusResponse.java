package com.potg.don.transaction.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
public class CsvStatusResponse {
	@JsonProperty("csv_file")
	private String csvFile;

	private String status;

	private String progress;

	@JsonProperty("last_updated")
	private String lastUpdated;

	private String details;
}
