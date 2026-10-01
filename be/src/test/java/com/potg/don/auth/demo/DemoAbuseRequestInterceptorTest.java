package com.potg.don.auth.demo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;

class DemoAbuseRequestInterceptorTest {
    private final DemoAbuseLimiter limiter = new DemoAbuseLimiter(new AtomicLong()::get);
    private final DemoAbuseRequestInterceptor interceptor = new DemoAbuseRequestInterceptor(limiter);
    private final DemoAuthController controller = new DemoAuthController(mock(DemoAuthService.class), mock(DemoRefreshCookie.class));
    private final DemoAuthRequestInterceptor contract = new DemoAuthRequestInterceptor(
        new DemoAuthHttpConfiguration.Settings("https://demo.example.invalid", true));

    private HandlerMethod login() throws Exception { return new HandlerMethod(controller, DemoAuthController.class.getMethod("login", jakarta.servlet.http.HttpServletRequest.class)); }
    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/api/auth/demo/login");
        request.setContextPath("/api");
        request.setAttribute(DemoGatewayFilter.VERIFIED_ATTRIBUTE, Boolean.TRUE);
        request.addHeader("Origin", "https://demo.example.invalid");
        request.addHeader("X-MoneyToad-Demo", "1");
        request.addHeader(DemoGatewaySettings.CLIENT_HEADER, "192.0.2.1");
        request.setContentType("application/json");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test void bodyContractRunsFirstAndLimiterDoesNotReadConsumedBody() throws Exception {
        var request = request();
        var response = new MockHttpServletResponse();
        assertTrue(contract.preHandle(request, response, login()));
        assertEquals(-1, request.getInputStream().read());
        assertTrue(interceptor.preHandle(request, response, login()));
        assertEquals(1, limiter.addressCount());
    }

    @Test void malformedBodyRejectsWithoutConsumingQuota() throws Exception {
        for (int i = 0; i < 10; i++) {
            var request = request();
            request.setContent("{\"id\":1}".getBytes(StandardCharsets.UTF_8));
            assertThrows(ResponseStatusException.class, () -> contract.preHandle(request, new MockHttpServletResponse(), login()));
        }
        assertEquals(0, limiter.addressCount());
        for (int i = 0; i < 5; i++) assertTrue(interceptor.preHandle(request(), new MockHttpServletResponse(), login()));
    }

    @Test void missingVerifiedGatewayRejectsWithoutTrustingForwardedAddress() throws Exception {
        var request = request();
        request.removeAttribute(DemoGatewayFilter.VERIFIED_ATTRIBUTE);
        request.addHeader("CF-Connecting-IP", "192.0.2.2");
        request.addHeader("X-Forwarded-For", "192.0.2.3");
        var response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, login()));
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("DEMO_GATEWAY_REJECTED"));
        assertEquals(0, limiter.addressCount());
    }

    @Test void missingAndDuplicateAddressRejectBeforeQuota() throws Exception {
        for (int variant = 0; variant < 2; variant++) {
            var request = request();
            if (variant == 0) request.removeHeader(DemoGatewaySettings.CLIENT_HEADER);
            else request.addHeader(DemoGatewaySettings.CLIENT_HEADER, "192.0.2.2");
            var response = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(request, response, login()));
            assertEquals(403, response.getStatus());
            assertTrue(response.getContentAsString().contains("DEMO_CLIENT_ADDRESS_REJECTED"));
        }
        assertEquals(0, limiter.addressCount());
    }

    @ParameterizedTest @ValueSource(strings={"192.0.2.1,192.0.2.2", "invalid.example", "192.0.2.1:6379", "192.0.2.1/24", "fe80::1%lo", ""})
    void invalidAddressHasFixedErrorAndNoQuota(String address) throws Exception {
        var request = request();
        request.removeHeader(DemoGatewaySettings.CLIENT_HEADER);
        request.addHeader(DemoGatewaySettings.CLIENT_HEADER, address);
        var response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, login()));
        assertEquals(403, response.getStatus());
        assertEquals(0, limiter.addressCount());
        assertNull(response.getHeader("Set-Cookie"));
    }

    @Test void loginLimitReturnsNoCookieAndPositiveDeltaSeconds() throws Exception {
        for (int i = 0; i < 5; i++) assertTrue(interceptor.preHandle(request(), new MockHttpServletResponse(), login()));
        var response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request(), response, login()));
        assertEquals(429, response.getStatus());
        assertEquals("60", response.getHeader("Retry-After"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertNull(response.getHeader("Set-Cookie"));
        assertTrue(response.getContentAsString().contains("DEMO_LOGIN_RATE_LIMITED"));
        assertFalse(response.getContentAsString().contains("192.0.2.1"));
    }

    @Test void readinessHasIndependentWindowAndProtectsHeadHandlerToo() throws Exception {
        var ready = new DemoReadinessController(mock(DemoReadinessService.class));
        var handler = new HandlerMethod(ready, DemoReadinessController.class.getMethod("ready"));
        var request = request();
        request.setMethod("GET");
        for (int i = 0; i < 60; i++) assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), handler));
        request.setMethod("HEAD");
        var response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, handler));
        assertEquals(429, response.getStatus());
        assertEquals("60", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("DEMO_READINESS_RATE_LIMITED"));
        assertTrue(interceptor.preHandle(request(), new MockHttpServletResponse(), login()));
    }

    @Test void reissueAndSessionAndLogoutNeverConsumeLoginWindow() throws Exception {
        var request = request();
        for (int i = 0; i < 20; i++) {
            assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new HandlerMethod(controller,
                DemoAuthController.class.getMethod("reissue", jakarta.servlet.http.HttpServletRequest.class))));
            assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new HandlerMethod(controller,
                DemoAuthController.class.getMethod("session", org.springframework.security.core.Authentication.class))));
            assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new HandlerMethod(controller,
                DemoAuthController.class.getMethod("logout", org.springframework.security.core.Authentication.class))));
        }
        assertEquals(0, limiter.addressCount());
        assertTrue(interceptor.preHandle(request(), new MockHttpServletResponse(), login()));
    }
}
