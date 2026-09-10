package com.potg.don.transaction.client;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import com.potg.don.transaction.dto.response.AnalysisTriggerResponse;
import com.potg.don.transaction.dto.response.BaselineResponse;
import com.potg.don.transaction.dto.response.CsvStatusResponse;
import com.potg.don.transaction.dto.response.CsvUploadResponse;

@Component
public class CsvClient {

	private final WebClient webClient;
	private final URI baseUri;

	public CsvClient(WebClient webClient, @Value("${ai.base-url}") String baseUrl) {
		this.webClient = webClient;
		URI uri;
		try {
			uri = URI.create(baseUrl.trim().replaceAll("/+$", ""));
		} catch (IllegalArgumentException | NullPointerException error) {
			throw new IllegalArgumentException("ai.base-url must be an absolute HTTP(S) base URL");
		}
		if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
			|| uri.getHost() == null || uri.getRawUserInfo() != null
			|| uri.getRawQuery() != null || uri.getRawFragment() != null
			|| uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535) {
			throw new IllegalArgumentException("ai.base-url must be an absolute HTTP(S) base URL");
		}
		this.baseUri = URI.create(uri.toASCIIString());
	}

	private URI endpoint(String path, String fileId) {
		UriComponentsBuilder uri = UriComponentsBuilder.fromUri(baseUri).path(path);
		if (fileId != null) {
			uri.queryParam("file_id", UriUtils.encode(fileId, StandardCharsets.UTF_8));
		}
		return uri.build(true).toUri();
	}

	private static final String UPLOAD_PATH = "/api/ai/csv/upload";
	private static final String CHANGE_PATH = "/api/ai/csv/change";
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
			.uri(endpoint(UPLOAD_PATH, null))
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
			.uri(endpoint(CHANGE_PATH, fileId))
			.contentType(MediaType.MULTIPART_FORM_DATA)
			.body(BodyInserters.fromMultipartData(mb.build()))
			.retrieve()
			.bodyToMono(CsvUploadResponse.class)
			.block();
	}

	public AnalysisTriggerResponse triggerAnalysis(String fileId) {
		return webClient.post()
			.uri(endpoint(ANALYZE_PATH, fileId))
			.retrieve()
			.bodyToMono(AnalysisTriggerResponse.class)   // 본문 무시
			.block();
	}

	/**
	 * 상태 확인: GET /api/ai/csv/status?file_id=...  (status == "none" 이면 완료)
	 */
	public CsvStatusResponse getCsvStatus(String fileId) {
		return webClient.get()
			.uri(endpoint(STATUS_PATH, fileId))
			.retrieve()
			.bodyToMono(CsvStatusResponse.class)
			.block();
	}

	public BaselineResponse getBaseline(String fileId) {
		return webClient.get()
			.uri(endpoint(BASELINE_PATH, fileId))
			.retrieve()
			.bodyToMono(BaselineResponse.class)
			.block();
	}
}
