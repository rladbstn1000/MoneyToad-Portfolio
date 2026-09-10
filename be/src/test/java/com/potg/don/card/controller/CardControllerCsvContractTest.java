package com.potg.don.card.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.method.HandlerMethod;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potg.don.analysisJob.service.AnalysisJobService;
import com.potg.don.auth.jwt.JwtAuthenticationFilter;
import com.potg.don.auth.jwt.JwtUtil;
import com.potg.don.auth.oauth.CustomOAuth2UserService;
import com.potg.don.auth.oauth.OAuth2SuccessHandler;
import com.potg.don.card.dto.request.CardRequest;
import com.potg.don.card.entity.Card;
import com.potg.don.card.service.CardService;
import com.potg.don.dummy.service.DummyService;
import com.potg.don.exception.GlobalExceptionHandler;
import com.potg.don.global.config.SecurityConfig;
import com.potg.don.transaction.dto.response.AnalysisTriggerResponse;
import com.potg.don.transaction.dto.response.CsvUploadResponse;
import com.potg.don.transaction.service.CsvService;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;

/**
 * Servlet call-contract test only: real JWT/security/controller, in-memory service doubles.
 * No DonApplication, JPA, scheduler, database, CSV client or external OAuth invocation.
 */
@WebMvcTest(controllers = CardController.class,
	properties = "spring.config.location=classpath:application-a2-card-mvc.yml")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ContextConfiguration(classes = CardControllerCsvContractTest.CardMvcConfiguration.class)
class CardControllerCsvContractTest {

	private static final String SYNTHETIC_SECRET = UUID.randomUUID().toString();
	private static final String CARD_MARKER = "synthetic-card-marker";
	private static final String CVC_MARKER = "not-a-cvc";

	@Configuration(proxyBeanMethods = false)
	@Import({CardController.class, SecurityConfig.class, JwtAuthenticationFilter.class,
		JwtUtil.class, GlobalExceptionHandler.class})
	static class CardMvcConfiguration {
	}

	@DynamicPropertySource
	static void syntheticJwtSecret(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> SYNTHETIC_SECRET);
	}

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private JwtUtil jwtUtil;
	@Autowired private JwtAuthenticationFilter jwtFilter;
	@Autowired private SecurityFilterChain securityFilterChain;
	@Autowired private ApplicationContext applicationContext;

	@MockitoBean private CardService cardService;
	@MockitoBean private DummyService dummyService;
	@MockitoBean private CsvService csvService;
	@MockitoBean private AnalysisJobService analysisJobService;
	@MockitoBean private UserRepository userRepository;
	@MockitoBean private CustomOAuth2UserService oAuth2UserService;
	@MockitoBean private OAuth2SuccessHandler successHandler;
	@MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

	@BeforeEach
	void verifySliceBoundaryAndRealFilter() {
		assertThat(securityFilterChain.getFilters()).contains(jwtFilter);
		assertThat(applicationContext.getBeansOfType(javax.sql.DataSource.class)).isEmpty();
	}

	@Test
	void postKeepsUser101AndCard202InTheirOwnArguments() throws Exception {
		assertCardContract(true, 101L, 202L);
	}

	@Test
	void postKeepsUser202AndCard101InTheirOwnArguments() throws Exception {
		assertCardContract(true, 202L, 101L);
	}

	@Test
	void patchKeepsUser101AndCard202InTheirOwnArgumentsAndUsesUpload() throws Exception {
		assertCardContract(false, 101L, 202L);
	}

	@Test
	void patchKeepsUser202AndCard101InTheirOwnArgumentsAndUsesUpload() throws Exception {
		assertCardContract(false, 202L, 101L);
	}

	@Test
	void absentBearerStopsBothCardMutationsAtExistingJwtFilter() throws Exception {
		for (boolean registration : new boolean[] {true, false}) {
			MvcResult result = mvc.perform(cardRequest(registration)).andReturn();
			assertThat(result.getResponse().getStatus()).isEqualTo(401);
			assertThat(result.getHandler()).isNull();
			assertThat(responseJson(result)).isEqualTo(objectMapper.valueToTree(Map.of(
				"status", 401, "error", "Unauthorized", "message", "인증 토큰이 필요합니다.")));
		}
		verifyNoInteractions(cardService, dummyService, csvService, analysisJobService, userRepository,
			oAuth2UserService, successHandler);
	}

	private void assertCardContract(boolean registration, long userId, long cardId) throws Exception {
		User user = User.createUser("a2-user-" + userId + "@example.invalid", "Synthetic controller user");
		ReflectionTestUtils.setField(user, "id", userId);
		when(userRepository.findById(userId)).thenReturn(Optional.of(user));

		CardRequest payload = objectMapper.readValue(cardPayload(), CardRequest.class);
		Card card = Card.createCard(payload, user);
		ReflectionTestUtils.setField(card, "id", cardId);
		if (registration) {
			when(cardService.registerCard(eq(userId), any(CardRequest.class))).thenReturn(card);
		} else {
			when(cardService.updateCard(eq(userId), any(CardRequest.class))).thenReturn(card);
		}
		CsvUploadResponse upload = new CsvUploadResponse();
		upload.setFileId("a2-controller-file-" + userId);
		AnalysisTriggerResponse analysis = new AnalysisTriggerResponse();
		analysis.setFileId(upload.getFileId());
		when(csvService.uploadCsvAndSaveFileId(userId, cardId)).thenReturn(upload);
		when(csvService.triggerAnalysis(userId)).thenReturn(analysis);

		MvcResult result = mvc.perform(cardRequest(registration)
			.header("Authorization", "Bearer " + jwtUtil.createAccessToken(userId, user.getEmail())))
			.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(registration ? 201 : 200);
		assertThat(result.getHandler()).isInstanceOf(HandlerMethod.class);
		HandlerMethod handler = (HandlerMethod)result.getHandler();
		assertThat(handler.getBeanType()).isEqualTo(CardController.class);
		assertThat(handler.getMethod().getName()).isEqualTo(registration ? "registerCard" : "updateCard");
		JsonNode response = responseJson(result);
		assertThat(response.size()).isEqualTo(3);
		assertThat(response.path("cardId").asLong()).isEqualTo(cardId);
		assertThat(response.path("cardNo").asText()).isEqualTo(CARD_MARKER);
		assertThat(response.path("cvc").asText()).isEqualTo(CVC_MARKER);

		ArgumentCaptor<CardRequest> forwardedRequest = ArgumentCaptor.forClass(CardRequest.class);
		InOrder order = inOrder(cardService, dummyService, csvService, analysisJobService);
		if (registration) {
			order.verify(cardService).registerCard(eq(userId), forwardedRequest.capture());
			verify(cardService, never()).updateCard(any(), any());
		} else {
			order.verify(cardService).updateCard(eq(userId), forwardedRequest.capture());
			verify(cardService, never()).registerCard(any(), any());
		}
		order.verify(dummyService).populateFromPool(cardId);
		order.verify(csvService).uploadCsvAndSaveFileId(userId, cardId);
		order.verify(csvService).triggerAnalysis(userId);
		order.verify(csvService).saveBudgetsFromTriggerResponse(userId, analysis);
		order.verify(analysisJobService).enqueue(userId, upload.getFileId());
		order.verifyNoMoreInteractions();
		verify(csvService, never()).changeCsvAndSaveFileId(any(), any());
		verifyNoMoreInteractions(cardService, dummyService, csvService, analysisJobService);
		verify(userRepository).findById(userId);
		verifyNoMoreInteractions(userRepository);
		assertThat(forwardedRequest.getValue().getCardNo()).isEqualTo(CARD_MARKER);
		assertThat(forwardedRequest.getValue().getCvc()).isEqualTo(CVC_MARKER);
		verifyNoInteractions(oAuth2UserService, successHandler);

		// IDs and status only; request headers, token, card fields and user data are excluded.
		System.out.println("A2_CARD_CONTRACT " + objectMapper.writeValueAsString(Map.of(
			"method", registration ? "POST" : "PATCH", "httpStatus", result.getResponse().getStatus(),
			"userId", userId, "cardId", cardId, "csvBranch", "upload", "databaseVerified", false)));
	}

	private MockHttpServletRequestBuilder cardRequest(boolean registration) throws Exception {
		return (registration ? post("/api/cards") : patch("/api/cards"))
			.contextPath("/api").contentType(MediaType.APPLICATION_JSON).content(cardPayload());
	}

	private String cardPayload() throws Exception {
		return objectMapper.writeValueAsString(Map.of("cardNo", CARD_MARKER, "cvc", CVC_MARKER));
	}

	private JsonNode responseJson(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsByteArray());
	}
}
