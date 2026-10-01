package com.potg.don.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.potg.don.auth.demo.DemoSessionService;
import com.potg.don.user.entity.User;
import com.potg.don.user.repository.UserRepository;

/** Runs unchanged against the pre-guard tree: a real authenticated direct request must be refused. */
class DemoGatewayBoundaryIntegrationTest {
    @Test void directPublicRequestWithValidAccessCannotBypassGateway() throws Exception {
        // Public synthetic fixture, never a deployed credential and never printed.
        String synthetic = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        try (var support = new DemoAuthHttpTestSupport();
             var started = support.start("demo", "", "true", "public-demo", false,
                 Map.of("DEMO_GATEWAY_SECRET", synthetic))) {
            assertThat(started.failure() == null).as("Owned public product context starts").isTrue();
            var context = started.context();
            var users = context.getBean(UserRepository.class);
            var user = users.saveAndFlush(User.createUser("gateway-fixture@moneytoad.invalid", "Synthetic Gateway Fixture"));
            var sessions = context.getBean(DemoSessionService.class);
            var tokens = sessions.startForUser(user.getId());
            try {
                int before = started.probe().userLookups.get();
                var response = DemoAuthHttpIntegrationTest.mvc(started).perform(
                    get("/api/auth/demo/session").contextPath("/api")
                        .header("Authorization", "Bearer " + tokens.accessToken())).andReturn().getResponse();
                assertThat(response.getStatus()).isEqualTo(403);
                assertThat(DemoAuthHttpTestSupport.JSON.readTree(response.getContentAsString())
                    .path("code").asText()).isEqualTo("DEMO_GATEWAY_REJECTED");
                assertThat(response.getHeaders("Set-Cookie")).isEmpty();
                assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
                assertThat(started.probe().userLookups.get()).isEqualTo(before);
            } finally {
                var jwt = context.getBean(com.potg.don.auth.jwt.JwtUtil.class);
                sessions.revoke(jwt.validateDemoAccessToken(jwt.parse(tokens.accessToken()), java.time.Instant.now()).sid());
            }
        }
    }
}
