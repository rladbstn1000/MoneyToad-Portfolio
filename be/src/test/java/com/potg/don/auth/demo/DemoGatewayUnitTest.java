package com.potg.don.auth.demo;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DemoGatewayUnitTest {
    // Explicit synthetic test-only material; no deployment secret is loaded or generated.
    private static String synthetic() { return "A".repeat(43); }
    private static MockEnvironment environment(String kind) {
        var environment = new MockEnvironment().withProperty("app.deployment.kind", kind);
        environment.setActiveProfiles("demo");
        return environment;
    }
    private static DemoGatewaySettings settings() {
        return DemoGatewaySettings.validatedSettings(environment("public-demo").withProperty("app.demo.gateway-secret", synthetic()));
    }

    @Test void missingPublicSettingFailsWithFixedSanitizedError() {
        assertEquals("DEMO_AUTH_PROFILE: INVALID_GATEWAY_SETTINGS", assertThrows(IllegalStateException.class,
            () -> DemoGatewaySettings.validatedSettings(environment("public-demo"))).getMessage());
    }
    static Stream<String> invalidSettings() {
        return Stream.of("", " ", "short", "A".repeat(42), "A".repeat(44), "A".repeat(42) + "B", "A".repeat(42) + "=", "A".repeat(43) + "\n");
    }
    @ParameterizedTest @MethodSource("invalidSettings")
    void invalidPublicSettingFailsWithoutEcho(String input) {
        assertEquals("DEMO_AUTH_PROFILE: INVALID_GATEWAY_SETTINGS", assertThrows(IllegalStateException.class,
            () -> DemoGatewaySettings.validatedSettings(environment("public-demo").withProperty("app.demo.gateway-secret", input))).getMessage());
    }
    @Test void canonicalSettingMatchesAndDoesNotExposeValue() {
        var settings = settings();
        assertTrue(settings.enabled());
        assertTrue(settings.matches(synthetic()));
        assertFalse(settings.matches(null));
        assertFalse(settings.matches("B".repeat(42) + "A"));
        assertEquals("DemoGatewaySettings[redacted]", settings.toString());
    }
    @Test void localAndOAuthModesDoNotActivateGateway() {
        assertFalse(DemoGatewaySettings.validatedSettings(environment("local-demo")).enabled());
        assertFalse(DemoGatewaySettings.validatedSettings(new MockEnvironment()
            .withProperty("app.deployment.kind", "standard")).enabled());
    }
    @Test void validRequestPassesOnceAndSetsVerifiedAttribute() throws Exception {
        var request = request("POST", "/auth/demo/login");
        request.addHeader(DemoGatewaySettings.HEADER, synthetic());
        var calls = new AtomicInteger();
        new DemoGatewayFilter(settings()).doFilter(request, new MockHttpServletResponse(), (req, res) -> calls.incrementAndGet());
        assertEquals(1, calls.get());
        assertEquals(Boolean.TRUE, request.getAttribute(DemoGatewayFilter.VERIFIED_ATTRIBUTE));
    }
    @Test void missingWrongAndDuplicateRejectBeforeChainAndNeverEcho() throws Exception {
        for (int variant = 0; variant < 3; variant++) {
            var request = request("POST", "/auth/demo/login");
            if (variant == 1) request.addHeader(DemoGatewaySettings.HEADER, "invalid");
            if (variant == 2) {
                request.addHeader(DemoGatewaySettings.HEADER, synthetic());
                request.addHeader(DemoGatewaySettings.HEADER, synthetic());
            }
            var response = new MockHttpServletResponse();
            new DemoGatewayFilter(settings()).doFilter(request, response, (req, res) -> fail("CHAIN_MUST_NOT_RUN"));
            assertEquals(403, response.getStatus());
            assertEquals("no-store", response.getHeader("Cache-Control"));
            assertNull(response.getHeader("Set-Cookie"));
            assertFalse(response.getContentAsString().contains(synthetic()));
            assertTrue(response.getContentAsString().contains("DEMO_GATEWAY_REJECTED"));
        }
    }
    @Test void onlyExactGetLivenessIsExemptIncludingOptionsAndHead() throws Exception {
        var calls = new AtomicInteger();
        new DemoGatewayFilter(settings()).doFilter(request("GET", "/test"), new MockHttpServletResponse(), (req, res) -> calls.incrementAndGet());
        assertEquals(1, calls.get());
        for (String[] entry : new String[][] {{"HEAD", "/test"}, {"POST", "/test"}, {"GET", "/test/"},
            {"GET", "/test/subpath"}, {"OPTIONS", ""}, {"OPTIONS", "/auth/demo/login"}, {"GET", "/auth/demo/ready"}, {"GET", "/unknown"}}) {
            var response = new MockHttpServletResponse();
            new DemoGatewayFilter(settings()).doFilter(request(entry[0], entry[1]), response, (req, res) -> fail("CHAIN_MUST_NOT_RUN"));
            assertEquals(403, response.getStatus());
        }
    }
    private static MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, "/api" + path);
        request.setContextPath("/api");
        return request;
    }
}
