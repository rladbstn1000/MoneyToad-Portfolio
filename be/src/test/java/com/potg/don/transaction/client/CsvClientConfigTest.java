package com.potg.don.transaction.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potg.don.global.config.WebClientConfig;
import com.potg.don.transaction.dto.response.AnalysisTriggerResponse;
import com.potg.don.transaction.dto.response.BaselineResponse;
import com.potg.don.transaction.dto.response.CsvStatusResponse;
import com.potg.don.transaction.dto.response.CsvUploadResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Mono;

/** Narrow Spring + actual WebClient HTTP checks. No Boot app, database or CsvClient mock. */
class CsvClientConfigTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final byte[] CSV = "transaction_id,amount\n1,1200\n".getBytes(StandardCharsets.UTF_8);
	private static final long CARD_ID = 701L;
	private static final String FILE_ID = "file id&plus+/hash#percent%?equals=한글";
	private static final String RESPONSE_FILE = "synthetic-file";
	private static final String PREFIX = "/synthetic-prefix";
	private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
	private final AtomicInteger accepted = new AtomicInteger();
	private final AtomicInteger blocked = new AtomicInteger();
	private HttpServer server;
	private ExecutorService executor;
	private volatile byte[] responseBody;
	private int port;

	private enum Operation {
		UPLOAD("POST", "/api/ai/csv/upload"),
		CHANGE("PUT", "/api/ai/csv/change"),
		TRIGGER("POST", "/api/ai/data"),
		STATUS("GET", "/api/ai/csv/status"),
		BASELINE("GET", "/api/ai/data/baseline");

		private final String method;
		private final String path;

		Operation(String method, String path) {
			this.method = method;
			this.path = path;
		}

		boolean multipart() {
			return this == UPLOAD || this == CHANGE;
		}
	}

	private record CapturedRequest(String method, String path, String rawQuery,
		String contentType, byte[] body, String peer) { }

	@BeforeEach
	void startDedicatedLoopbackServer() throws IOException {
		InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
		server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
		port = server.getAddress().getPort();
		executor = Executors.newSingleThreadExecutor(task -> {
			Thread thread = new Thread(task, "csv-config-loopback-stub");
			thread.setDaemon(true);
			return thread;
		});
		server.setExecutor(executor);
		responseBody = "{}".getBytes(StandardCharsets.UTF_8);
		server.createContext("/", this::serve);
		server.start();
	}

	@AfterEach
	void stopOnlyThisTestServer() throws InterruptedException {
		if (server != null) server.stop(0);
		if (executor != null) {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	static Stream<Arguments> endpointCases() {
		return Stream.of("root", "prefix", "prefix_trailing")
			.flatMap(baseCase -> Stream.of(Operation.values()).map(operation -> Arguments.of(operation, baseCase)));
	}

	@ParameterizedTest(name = "{0} uses configured {1} through actual loopback HTTP")
	@MethodSource("endpointCases")
	void configuredEndpointPreservesHttpContract(Operation operation, String baseCase) throws Exception {
		String suffix = switch (baseCase) {
			case "prefix" -> PREFIX;
			case "prefix_trailing" -> PREFIX + "/";
			default -> "";
		};
		checkEndpoint(operation, baseCase, Map.of("ai.base-url", origin() + suffix), false);
	}

	static Stream<Arguments> encodedBasePaths() {
		return Stream.of(Arguments.of("encoded_prefix", "/synthetic%20prefix/"),
			Arguments.of("unicode_prefix", "/합성/"));
	}

	@ParameterizedTest(name = "{0} preserves the base path encoding through actual HTTP")
	@MethodSource("encodedBasePaths")
	void basePathEncodingIsPreserved(String baseCase, String suffix) throws Exception {
		checkEndpoint(Operation.STATUS, baseCase, Map.of("ai.base-url", origin() + suffix), false);
	}

	@Test
	void invalidBaseConfigurationFailsBeforeAnyRequest() throws Exception {
		Map<String, String> cases = new LinkedHashMap<>();
		cases.put("missing", null);
		cases.put("empty", "");
		cases.put("blank", " \t ");
		cases.put("relative", "/synthetic-prefix");
		cases.put("unsupported_scheme", "ftp://127.0.0.1:" + port);
		cases.put("query", origin() + "?synthetic=true");
		cases.put("fragment", origin() + "#synthetic");
		cases.put("userinfo", "http://synthetic-user@127.0.0.1:" + port);
		cases.put("zero_port", "http://127.0.0.1:0");
		cases.put("invalid_port", "http://127.0.0.1:65536");
		List<Executable> assertions = new ArrayList<>();
		for (var entry : cases.entrySet()) {
			Map<String, Object> properties = entry.getValue() == null
				? Map.of() : Map.of("ai.base-url", entry.getValue());
			boolean rejected = startupRejected(properties, false);
			var evidence = evidence("invalid_base", "spring_context");
			evidence.put("configCase", entry.getKey());
			evidence.put("startupRejected", rejected);
			evidence.put("networkUntouched", networkUntouched());
			emit(evidence);
			assertions.add(() -> assertThat(rejected).as("startup rejects " + entry.getKey()).isTrue());
		}
		assertions.add(() -> assertThat(networkUntouched()).isTrue());
		assertAll(assertions);
	}

	@Test
	void outboundGuardRejectsOtherDestinationsBeforeConnectorOrDns() throws Exception {
		int wrongPort = port == 65535 ? 65534 : port + 1;
		List<URI> forbidden = List.of(
			URI.create("http://csv-config-blocked.invalid/guard"),
			URI.create("https://127.0.0.1:" + port + "/guard"),
			URI.create("http://127.0.0.1:" + wrongPort + "/guard"),
			URI.create("http://synthetic-user@127.0.0.1:" + port + "/guard"));
		int rejectedCalls = 0;
		try (var context = context(Map.of("ai.base-url", origin()), false)) {
			WebClient webClient = context.getBean(WebClient.class);
			for (URI uri : forbidden) {
				try {
					webClient.get().uri(uri).retrieve().bodyToMono(String.class).block();
				} catch (RuntimeException | AssertionError expected) {
					rejectedCalls++;
				}
			}
		}
		var evidence = evidence("guard_self_test", "outbound_guard");
		evidence.put("invocationSucceeded", rejectedCalls == forbidden.size());
		evidence.put("networkUntouched", accepted.get() == 0 && requests.isEmpty());
		emit(evidence);
		assertThat(rejectedCalls).isEqualTo(forbidden.size());
		assertThat(blocked.get()).isEqualTo(forbidden.size());
		assertThat(accepted.get()).isZero();
		assertThat(requests).isEmpty();
	}

	@Test
	void mainYamlResolvesSyntheticAiBaseUrlWithoutFallback() throws Exception {
		checkEndpoint(Operation.STATUS, "root", Map.of("AI_BASE_URL", origin()), true);
	}

	@Test
	void mainYamlWithoutAiBaseUrlFailsBeforeAnyRequest() throws Exception {
		boolean rejected = startupRejected(Map.of(), true);
		var evidence = evidence("yaml_missing_env", "spring_context");
		evidence.put("placeholderMappingExact", exactMainYamlMapping());
		evidence.put("startupRejected", rejected);
		evidence.put("networkUntouched", networkUntouched());
		emit(evidence);
		assertAll(() -> assertThat(exactMainYamlMapping()).isTrue(),
			() -> assertThat(rejected).isTrue(), () -> assertThat(networkUntouched()).isTrue());
	}

	private void checkEndpoint(Operation operation, String baseCase, Map<String, Object> properties,
		boolean mainYaml) throws Exception {
		responseBody = responseFor(operation);
		Object response = null;
		boolean succeeded = false;
		try (var context = context(properties, mainYaml)) {
			response = invoke(context.getBean(CsvClient.class), operation);
			succeeded = true;
		} catch (RuntimeException | AssertionError failure) {
			// Do not rethrow reactive errors: checkpoints can include the rejected
			// destination. RED is reported using safe booleans/counts below.
		}
		CapturedRequest request = requests.size() == 1 ? requests.get(0) : null;
		String prefix = switch (baseCase) {
			case "root" -> "";
			case "encoded_prefix" -> "/synthetic%20prefix";
			case "unicode_prefix" -> "/%ED%95%A9%EC%84%B1";
			default -> PREFIX;
		};
		String expectedPath = prefix + operation.path;
		String body = request == null ? "" : new String(request.body(), StandardCharsets.UTF_8);
		boolean methodMatches = request != null && operation.method.equals(request.method());
		boolean pathMatches = request != null && expectedPath.equals(request.path());
		boolean queryMatches = request != null && exactQuery(request.rawQuery(), operation);
		boolean multipartMatches = request != null && (operation.multipart()
			? request.contentType() != null && request.contentType().startsWith("multipart/form-data;")
				&& body.split("name=\"file\"", -1).length == 2
			: request.body().length == 0);
		boolean filenameMatches = !operation.multipart() || body.contains("filename=\"transactions_701.csv\"");
		boolean payloadMatches = !operation.multipart()
			|| body.contains("\r\n\r\n" + new String(CSV, StandardCharsets.UTF_8) + "\r\n");
		boolean partContentTypeMatches = !operation.multipart() || body.toLowerCase(java.util.Locale.ROOT)
			.contains("content-type: " + (operation == Operation.UPLOAD ? "text/plain" : "text/csv"));
		boolean responseMatches = responseMatches(response, operation);
		boolean loopbackPeer = request != null && "127.0.0.1".equals(request.peer());
		boolean mappingExact = !mainYaml || exactMainYamlMapping();
		var evidence = evidence(mainYaml ? "yaml_mapping" : "endpoint_contract", "http_loopback");
		evidence.put("operation", operation.name().toLowerCase(java.util.Locale.ROOT));
		evidence.put("baseCase", baseCase);
		evidence.put("expectedMethod", operation.method);
		evidence.put("expectedPath", expectedPath);
		evidence.put("requestBodyBytes", request == null ? 0 : request.body().length);
		evidence.put("csvPayloadBytes", operation.multipart() ? CSV.length : 0);
		evidence.put("invocationSucceeded", succeeded);
		evidence.put("methodMatches", methodMatches);
		evidence.put("pathMatches", pathMatches);
		evidence.put("queryMatches", queryMatches);
		evidence.put("multipartMatches", multipartMatches);
		evidence.put("filenameMatches", filenameMatches);
		evidence.put("payloadMatches", payloadMatches);
		evidence.put("partContentTypeMatches", partContentTypeMatches);
		evidence.put("responseMatches", responseMatches);
		evidence.put("loopbackPeer", loopbackPeer);
		evidence.put("placeholderMappingExact", mappingExact);
		emit(evidence);
		boolean invocationSucceeded = succeeded;
		assertAll(() -> assertThat(blocked.get()).as("out-of-stub attempts rejected before connector").isZero(),
			() -> assertThat(accepted.get()).isEqualTo(1), () -> assertThat(requests.size()).isEqualTo(1),
			() -> assertThat(invocationSucceeded).isTrue(), () -> assertThat(methodMatches).isTrue(),
			() -> assertThat(pathMatches).isTrue(), () -> assertThat(queryMatches).isTrue(),
			() -> assertThat(multipartMatches).isTrue(), () -> assertThat(filenameMatches).isTrue(),
			() -> assertThat(payloadMatches).isTrue(), () -> assertThat(partContentTypeMatches).isTrue(),
			() -> assertThat(responseMatches).isTrue(), () -> assertThat(loopbackPeer).isTrue(),
			() -> assertThat(mappingExact).isTrue());
	}

	private AnnotationConfigApplicationContext context(Map<String, Object> values, boolean mainYaml) throws IOException {
		// Do not even register the inherited process environment/system property sources.
		StandardEnvironment environment = new StandardEnvironment() {
			@Override protected void customizePropertySources(MutablePropertySources sources) { }
		};
		environment.getPropertySources().addFirst(new MapPropertySource("csv-config-synthetic", values));
		if (mainYaml) {
			for (PropertySource<?> source : mainYamlSources()) environment.getPropertySources().addLast(source);
		}
		var context = new AnnotationConfigApplicationContext();
		context.setEnvironment(environment);
		context.registerBean("csvConfigPlaceholders", PropertySourcesPlaceholderConfigurer.class,
			PropertySourcesPlaceholderConfigurer::new);
		context.registerBean("csvConfigBuilder", WebClient.Builder.class,
			() -> WebClient.builder().filter(this::allowOnlyStub));
		context.register(WebClientConfig.class, CsvClient.class);
		try {
			context.refresh();
			return context;
		} catch (RuntimeException failure) {
			context.close();
			throw failure;
		}
	}

	private Mono<ClientResponse> allowOnlyStub(ClientRequest request, ExchangeFunction next) {
		URI uri = request.url();
		if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
			|| uri.getPort() != port || uri.getUserInfo() != null || uri.getFragment() != null) {
			blocked.incrementAndGet();
			return Mono.error(new AssertionError("CSV test rejected destination before connector"));
		}
		accepted.incrementAndGet();
		return next.exchange(request);
	}

	private boolean startupRejected(Map<String, Object> properties, boolean mainYaml) throws IOException {
		try (var ignored = context(properties, mainYaml)) {
			return false;
		} catch (RuntimeException expected) {
			return true;
		}
	}

	private List<PropertySource<?>> mainYamlSources() throws IOException {
		return new YamlPropertySourceLoader().load("csv-config-main-yaml", new ClassPathResource("application.yml"));
	}

	private boolean exactMainYamlMapping() throws IOException {
		return mainYamlSources().stream().anyMatch(source -> "${AI_BASE_URL}".equals(source.getProperty("ai.base-url")));
	}

	private void serve(HttpExchange exchange) throws IOException {
		try (exchange) {
			requests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
				exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders().getFirst("Content-Type"),
				exchange.getRequestBody().readAllBytes(), exchange.getRemoteAddress().getAddress().getHostAddress()));
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, responseBody.length);
			exchange.getResponseBody().write(responseBody);
		}
	}

	private String origin() {
		return "http://127.0.0.1:" + port;
	}

	private boolean networkUntouched() {
		return accepted.get() == 0 && blocked.get() == 0 && requests.isEmpty();
	}

	private static boolean exactQuery(String rawQuery, Operation operation) {
		if (operation == Operation.UPLOAD) return rawQuery == null;
		if (rawQuery == null || !rawQuery.startsWith("file_id=")) return false;
		String encoded = rawQuery.substring("file_id=".length());
		if (!encoded.matches("[A-Za-z0-9._~%\\-]*")) return false;
		try {
			return FILE_ID.equals(URLDecoder.decode(encoded, StandardCharsets.UTF_8));
		} catch (IllegalArgumentException invalidEncoding) {
			return false;
		}
	}

	private static Object invoke(CsvClient client, Operation operation) {
		return switch (operation) {
			case UPLOAD -> client.uploadCsv(CSV, CARD_ID);
			case CHANGE -> client.changeCsv(FILE_ID, CSV, CARD_ID);
			case TRIGGER -> client.triggerAnalysis(FILE_ID);
			case STATUS -> client.getCsvStatus(FILE_ID);
			case BASELINE -> client.getBaseline(FILE_ID);
		};
	}

	private static byte[] responseFor(Operation operation) throws IOException {
		Map<String, Object> prediction = Map.of("predicted_amount", 1200.0, "lower_bound", 1000.0, "upper_bound", 1400.0);
		Object value = switch (operation) {
			case UPLOAD, CHANGE -> Map.of("file_id", RESPONSE_FILE, "csv_file", "synthetic.csv", "size_bytes", CSV.length,
				"uploaded_at", "2026-02-03T12:00:00", "replaced_at", "2026-02-04T12:00:00",
				"validation", Map.of("valid_rows", 1, "total_rows", 1, "baseline_ready", true));
			case TRIGGER -> Map.of("file_id", RESPONSE_FILE, "year", 2026, "month", 2, "transactions_count", 1,
				"leak_amount", 50.0, "details", Map.of("total_predicted", 1200.0, "categories_count", 1,
					"category_predictions", Map.of("식비", prediction)));
			case STATUS -> Map.of("csv_file", "synthetic.csv", "status", "none", "progress", "100",
				"last_updated", "2026-02-03T12:00:00", "details", "synthetic ready");
			case BASELINE -> Map.of("file_id", RESPONSE_FILE, "months_count", 1, "category_filter", "식비",
				"baseline_months", List.of(Map.of("year", 2026, "month", 2, "total_predicted", 1200.0,
					"categories_count", 1, "category_predictions", Map.of("식비", prediction),
					"training_data_until", "2026-01-31")));
		};
		return JSON.writeValueAsBytes(value);
	}

	private static boolean responseMatches(Object value, Operation operation) {
		return switch (operation) {
			case UPLOAD, CHANGE -> value instanceof CsvUploadResponse response
				&& RESPONSE_FILE.equals(response.getFileId()) && response.getSizeBytes() == CSV.length
				&& "synthetic.csv".equals(response.getCsvFile()) && response.getValidation() != null
				&& Integer.valueOf(1).equals(response.getValidation().getValidRows())
				&& Boolean.TRUE.equals(response.getValidation().getBaselineReady())
				&& "2026-02-04T12:00:00".equals(response.getReplacedAt());
			case TRIGGER -> value instanceof AnalysisTriggerResponse response
				&& RESPONSE_FILE.equals(response.getFileId()) && Integer.valueOf(1).equals(response.getTransactionsCount())
				&& response.getDetails() != null && Double.valueOf(1200).equals(response.getDetails().getTotalPredicted())
				&& response.getDetails().getCategoryPredictions() != null
				&& response.getDetails().getCategoryPredictions().get("식비") != null
				&& Double.valueOf(1000).equals(response.getDetails().getCategoryPredictions().get("식비").getLowerBound());
			case STATUS -> value instanceof CsvStatusResponse response
				&& "synthetic.csv".equals(response.getCsvFile()) && "none".equals(response.getStatus())
				&& "100".equals(response.getProgress()) && "2026-02-03T12:00:00".equals(response.getLastUpdated());
			case BASELINE -> value instanceof BaselineResponse response && RESPONSE_FILE.equals(response.getFileId())
				&& Integer.valueOf(1).equals(response.getMonthsCount()) && response.getBaselineMonths() != null
				&& response.getBaselineMonths().size() == 1
				&& Integer.valueOf(2026).equals(response.getBaselineMonths().get(0).getYear())
				&& response.getBaselineMonths().get(0).getCategoryPredictions() != null
				&& response.getBaselineMonths().get(0).getCategoryPredictions().get("식비") != null
				&& Double.valueOf(1400).equals(response.getBaselineMonths().get(0).getCategoryPredictions().get("식비").getUpperBound());
		};
	}

	private Map<String, Object> evidence(String scenario, String layer) {
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("scenario", scenario);
		evidence.put("layer", layer);
		evidence.put("operation", "none");
		evidence.put("baseCase", "none");
		evidence.put("configCase", "none");
		evidence.put("expectedHost", "127.0.0.1");
		evidence.put("port", port);
		evidence.put("acceptedRequests", accepted.get());
		evidence.put("blockedAttempts", blocked.get());
		evidence.put("stubRequests", requests.size());
		return evidence;
	}

	private static void emit(Map<String, Object> evidence) throws IOException {
		System.out.println("CSV_CONFIG_EVIDENCE " + JSON.writeValueAsString(evidence));
	}
}
