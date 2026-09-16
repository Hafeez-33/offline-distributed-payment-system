package com.demo.upimesh.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Filter that attaches a correlation ID (X-Request-ID) to every incoming HTTP request,
 * populates the SLF4J MDC with 'requestId', and propagates the header in the response.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements Filter {

    public static final String HEADER_REQUEST_ID = "X-Request-ID";
    public static final String MDC_KEY_REQUEST_ID = "requestId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest && response instanceof HttpServletResponse httpResponse) {
            String requestId = httpRequest.getHeader(HEADER_REQUEST_ID);
            if (requestId == null || requestId.isBlank()) {
                requestId = UUID.randomUUID().toString();
            } else {
                // Sanitize inbound correlation ID to avoid log injection
                requestId = requestId.replaceAll("[^a-zA-Z0-9_-]", "").trim();
                if (requestId.length() > 64) {
                    requestId = requestId.substring(0, 64);
                }
                if (requestId.isEmpty()) {
                    requestId = UUID.randomUUID().toString();
                }
            }

            MDC.put(MDC_KEY_REQUEST_ID, requestId);
            httpResponse.setHeader(HEADER_REQUEST_ID, requestId);

            try {
                chain.doFilter(request, response);
            } finally {
                MDC.remove(MDC_KEY_REQUEST_ID);
            }
        } else {
            chain.doFilter(request, response);
        }
    }
}
