package com.potg.don.transaction.client;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import com.potg.don.transaction.dto.response.AnalysisTriggerResponse;
import com.potg.don.transaction.dto.response.BaselineResponse;
import com.potg.don.transaction.dto.response.CsvStatusResponse;
import com.potg.don.transaction.dto.response.CsvUploadResponse;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CsvClient {

	private final WebClient webClient;

	private static final String UPLOAD_URL = "https://j13a409.p.ssafy.io/api/ai/csv/upload";
	private static final String CHANGE_PATH = "/api/ai/csv/change"; // base 없이 path만
	private static final String ANALYZE_PATH = "/api/ai/data";
	private static final String STATUS_PATH = "/api/ai/csv/status";
	private static final String BASELINE_PATH = "/api/ai/data/baseline";

	public CsvUploadResponse uploadCsv(byte[] csvBytes, Long cardId) {
		String filename = "transactions_" + cardId + ".csv";
		ByteArrayResource filePart = new ByteArrayResource(csvBytes) {
			@Override
			public String getFilename() {
				return filename;
			}
		};

		MultipartBodyBuilder mb = new MultipartBodyBuilder();
		mb.part("file", filePart)
			.contentType(MediaType.TEXT_PLAIN); // 서버가 text/csv 요구 시 MediaType.parseMediaType("text/csv")로 교체

		return webClient.post()
			.uri(UPLOAD_URL)
			.contentType(MediaType.MULTIPART_FORM_DATA)
			.body(BodyInserters.fromMultipartData(mb.build()))
			.retrieve()
			.bodyToMono(CsvUploadResponse.class)
			.block();
	}

	/**
	 * 교체 업로드(put): ?file_id=... + multipart/form-data(file)
	 */
	public CsvUploadResponse changeCsv(String fileId, byte[] csvBytes, Long cardId) {
		String filename = "transactions_" + cardId + ".csv";
		ByteArrayResource filePart = new ByteArrayResource(csvBytes) {
			@Override
			public String getFilename() {
				return filename;
			}
		};

		MultipartBodyBuilder mb = new MultipartBodyBuilder();
		mb.part("file", filePart).filename(filename).contentType(MediaType.parseMediaType("text/csv"));

		return webClient.put()
			.uri(uriBuilder -> uriBuilder.scheme("https")
				.host("j13a409.p.ssafy.io")
				.path(CHANGE_PATH)
				.queryParam("file_id", fileId) // API가 query param을 받는다고 했음
				.build())
			.contentType(MediaType.MULTIPART_FORM_DATA)
			.body(BodyInserters.fromMultipartData(mb.build()))
			.retrieve()
			.bodyToMono(CsvUploadResponse.class)
			.block();
	}

	public AnalysisTriggerResponse triggerAnalysis(String fileId) {
		return webClient.post()
			.uri(uriBuilder -> uriBuilder.scheme("https")
				.host("j13a409.p.ssafy.io")
				.path(ANALYZE_PATH)
				.queryParam("file_id", fileId)
				.build())
			.retrieve()
			.bodyToMono(AnalysisTriggerResponse.class)   // 본문 무시
			.block();
	}

	/**
	 * 상태 확인: GET /api/ai/csv/status?file_id=...  (status == "none" 이면 완료)
	 */
	public CsvStatusResponse getCsvStatus(String fileId) {
		return webClient.get()
			.uri(u -> u.scheme("https")
				.host("j13a409.p.ssafy.io")
				.path(STATUS_PATH)
				.queryParam("file_id", fileId)
				.build())
			.retrieve()
			.bodyToMono(CsvStatusResponse.class)
			.block();
	}

	public BaselineResponse getBaseline(String fileId) {
		return webClient.get()
			.uri(u -> u.scheme("https")
				.host("j13a409.p.ssafy.io")
				.path(BASELINE_PATH)
				.queryParam("file_id", fileId)
				.build())
			.retrieve()
			.bodyToMono(BaselineResponse.class)
			.block();
	}
}
