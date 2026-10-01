package com.potg.don.auth.demo;

import java.io.IOException;
import java.util.Collections;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Constructed only inside the Security chain, never registered as a Servlet bean. */
public final class DemoGatewayFilter extends OncePerRequestFilter {
    static final String VERIFIED_ATTRIBUTE = DemoGatewayFilter.class.getName() + ".verified";
    private final DemoGatewaySettings settings;

    public DemoGatewayFilter(DemoGatewaySettings settings) {
        if (!settings.enabled()) throw new IllegalArgumentException("PUBLIC_DEMO_GATEWAY_REQUIRED");
        this.settings = settings;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // This existing handler returns a constant and has no DB/Redis dependencies.
        return "GET".equals(request.getMethod()) && "/test".equals(path);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain chain) throws IOException, ServletException {
        var values = Collections.list(request.getHeaders(DemoGatewaySettings.HEADER));
        if (values.size() != 1 || !settings.matches(values.getFirst())) {
            response.setStatus(403);
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"status\":403,\"error\":\"Forbidden\",\"code\":\"DEMO_GATEWAY_REJECTED\",\"message\":\"Demo request rejected.\"}");
            return;
        }
        request.setAttribute(VERIFIED_ATTRIBUTE, Boolean.TRUE);
        chain.doFilter(request, response);
    }
}
