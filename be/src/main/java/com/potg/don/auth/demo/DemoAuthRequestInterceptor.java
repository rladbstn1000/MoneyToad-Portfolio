package com.potg.don.auth.demo;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Sole body reader for the four demo auth handlers; they deliberately bind no request body. */
public class DemoAuthRequestInterceptor implements HandlerInterceptor {
    private final DemoAuthHttpConfiguration.Settings settings;
    public DemoAuthRequestInterceptor(DemoAuthHttpConfiguration.Settings settings) { this.settings = settings; }

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod method) || method.getBeanType() != DemoAuthController.class) return true;
        response.setHeader("Cache-Control", "no-store");
        boolean post = "POST".equals(request.getMethod());
        var origins = Collections.list(request.getHeaders("Origin"));
        if (origins.size() > 1 || (post && origins.isEmpty())
            || (!origins.isEmpty() && !settings.origin().equals(origins.getFirst()))) throw fail(HttpStatus.FORBIDDEN);
        if (!settings.secure() && (!"http".equals(request.getScheme()) || request.isSecure()
            || !URI.create(settings.origin()).getHost().equals(request.getServerName()))) throw fail(HttpStatus.FORBIDDEN);
        if (request.getQueryString() != null && !request.getQueryString().isEmpty()) throw fail(HttpStatus.BAD_REQUEST);
        if (post) {
            var headers = Collections.list(request.getHeaders("X-MoneyToad-Demo"));
            if (headers.size() != 1 || !"1".equals(headers.getFirst())) throw fail(HttpStatus.FORBIDDEN);
            var types = Collections.list(request.getHeaders("Content-Type"));
            try {
                if (types.size() != 1) throw fail(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
                MediaType type = MediaType.parseMediaType(types.getFirst());
                if (!"application".equalsIgnoreCase(type.getType()) || !"json".equalsIgnoreCase(type.getSubtype())
                    || !Set.of("charset").containsAll(type.getParameters().keySet())
                    || type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset())) {
                    throw fail(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
                }
            } catch (IllegalArgumentException invalidType) { throw fail(HttpStatus.UNSUPPORTED_MEDIA_TYPE); }
        }
        if (request.getContentLengthLong() > 1024) throw fail(HttpStatus.PAYLOAD_TOO_LARGE);
        byte[] body = request.getInputStream().readNBytes(1025);
        if (body.length > 1024) throw fail(HttpStatus.PAYLOAD_TOO_LARGE);
        if (body.length > 0 && (!post || !new String(body, StandardCharsets.UTF_8)
            .matches("[ \\t\\r\\n]*\\{[ \\t\\r\\n]*\\}[ \\t\\r\\n]*"))) throw fail(HttpStatus.BAD_REQUEST);
        return true;
    }

    private static ResponseStatusException fail(HttpStatus status) { return new ResponseStatusException(status, "DEMO_REQUEST_REJECTED"); }
}
