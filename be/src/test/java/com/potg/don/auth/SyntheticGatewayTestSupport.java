package com.potg.don.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Publicly reproducible fixture only. Never deploy or write this value to verification evidence. */
public final class SyntheticGatewayTestSupport {
    private SyntheticGatewayTestSupport() { }
    public static String secret() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            "synthetic-abuse-fixture-key-only".getBytes(StandardCharsets.US_ASCII));
    }
    public static MockHttpServletRequestBuilder gateway(MockHttpServletRequestBuilder request) {
        return request.header("X-MoneyToad-Gateway", secret()).header("X-MoneyToad-Client-IP", "192.0.2.37");
    }
}
