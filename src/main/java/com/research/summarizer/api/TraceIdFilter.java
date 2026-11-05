package com.research.summarizer.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Gives every request a trace id, so all log lines of one request (controller and all three agents) can be found
 * together. The id is put in the logging MDC and returned in the X-Trace-Id response header.
 * A caller may send its own X-Trace-Id header; otherwise a new id is generated.
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Trace-Id";
    static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || !traceId.matches("[A-Za-z0-9-]{1,64}")) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Server threads are reused, so the id must not leak into the next request.
            MDC.remove(MDC_KEY);
        }
    }
}
