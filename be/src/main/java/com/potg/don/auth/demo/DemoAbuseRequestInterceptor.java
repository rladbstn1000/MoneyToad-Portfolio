package com.potg.don.auth.demo;

import java.io.IOException;
import java.util.Collections;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Runs after the existing request/body contract, immediately before the controller. */
public final class DemoAbuseRequestInterceptor implements HandlerInterceptor {
    private final DemoAbuseLimiter limiter;
    public DemoAbuseRequestInterceptor(DemoAbuseLimiter limiter) { this.limiter = limiter; }

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod method)) return true;
        boolean login = method.getBeanType() == DemoAuthController.class
            && "login".equals(method.getMethod().getName()) && "POST".equals(request.getMethod());
        boolean ready = method.getBeanType() == DemoReadinessController.class
            && "ready".equals(method.getMethod().getName())
            && ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()));
        if (!login && !ready) return true;
        if (!Boolean.TRUE.equals(request.getAttribute(DemoGatewayFilter.VERIFIED_ATTRIBUTE))) {
            reject(response, 403, "DEMO_GATEWAY_REJECTED", 0);
            return false;
        }
        DemoAbuseLimiter.Decision result;
        if (login) {
            var values = Collections.list(request.getHeaders(DemoGatewaySettings.CLIENT_HEADER));
            DemoClientAddress address;
            try {
                if (values.size() != 1) throw new IllegalArgumentException();
                address = DemoClientAddress.parse(values.getFirst());
            } catch (IllegalArgumentException invalid) {
                reject(response, 403, "DEMO_CLIENT_ADDRESS_REJECTED", 0);
                return false;
            }
            result = limiter.tryLogin(address);
        } else result = limiter.tryReadiness();
        if (result.allowed()) return true;
        reject(response, 429, login ? "DEMO_LOGIN_RATE_LIMITED" : "DEMO_READINESS_RATE_LIMITED", result.retryAfterSeconds());
        return false;
    }

    private static void reject(HttpServletResponse response, int status, String code, long retryAfter) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        if (retryAfter > 0) response.setHeader("Retry-After", Long.toString(retryAfter));
        // Fixed public codes only; no tokens, addresses, counts, clocks or settings.
        response.getWriter().write("{\"status\":" + status + ",\"error\":\""
            + (status == 429 ? "Too Many Requests" : "Forbidden") + "\",\"code\":\"" + code
            + "\",\"message\":\"Demo request temporarily unavailable.\"}");
    }
}
