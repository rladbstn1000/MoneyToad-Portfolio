package com.potg.don.auth.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Server-only material. Never expose through a record, logging or a response. */
public final class DemoGatewaySettings {
    public static final String HEADER = "X-MoneyToad-Gateway";
    public static final String CLIENT_HEADER = "X-MoneyToad-Client-IP";
    private final boolean enabled;
    private final byte[] secret;

    private DemoGatewaySettings(boolean enabled, String secret) {
        this.enabled = enabled;
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.US_ASCII);
    }

    public static DemoGatewaySettings validatedSettings(Environment environment) {
        boolean enabled = environment.acceptsProfiles(Profiles.of("demo"))
            && "public-demo".equals(environment.getProperty("app.deployment.kind"));
        if (!enabled) return new DemoGatewaySettings(false, null);
        try {
            String secret = environment.getProperty("app.demo.gateway-secret");
            if (!canonical(secret)) throw invalid();
            return new DemoGatewaySettings(true, secret);
        } catch (RuntimeException ignored) {
            // No supplied setting or nested exception may enter startup diagnostics.
            throw invalid();
        }
    }

    public boolean enabled() { return enabled; }

    public boolean matches(String candidate) {
        return enabled && canonical(candidate)
            && MessageDigest.isEqual(secret, candidate.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean canonical(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) return false;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            return decoded.length == 32
                && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value);
        } catch (IllegalArgumentException ignored) { return false; }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("DEMO_AUTH_PROFILE: INVALID_GATEWAY_SETTINGS");
    }

    @Override public String toString() { return "DemoGatewaySettings[redacted]"; }
}
