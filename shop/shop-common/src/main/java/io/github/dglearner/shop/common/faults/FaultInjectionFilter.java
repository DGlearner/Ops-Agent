package io.github.dglearner.shop.common.faults;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Applies the matching {@link FaultRule} to business requests. Injected failures look like the service's ordinary
 * errors (same status, problem body and code), so a client cannot tell them apart from real ones.
 */
public class FaultInjectionFilter extends OncePerRequestFilter {

    private final FaultInjector injector;

    public FaultInjectionFilter(FaultInjector injector) {
        this.injector = injector;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<FaultRule> match = injector.match(request.getMethod(), request.getRequestURI());
        if (match.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        FaultRule rule = match.get();
        if (rule.phase() == FaultRule.Phase.BEFORE) {
            sleep(rule.delay());
            if (FaultInjector.fails(rule)) {
                writeError(response, rule.errorStatus());
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        // AFTER: the request is processed in full, then its answer is held back, or lost and replaced by an error.
        ContentCachingResponseWrapper held = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, held);
        sleep(rule.delay());
        if (FaultInjector.fails(rule) && !response.isCommitted()) {
            response.reset();
            writeError(response, rule.errorStatus());
            return;
        }
        held.copyBodyToResponse();
    }

    private static void writeError(HttpServletResponse response, int status) throws IOException {
        boolean unavailable = status == 503;
        String body = "{\"type\":\"about:blank\",\"title\":\"" + (unavailable ? "Service Unavailable"
                : "Internal Server Error") + "\",\"status\":" + status + ",\"detail\":\""
                + (unavailable ? "Service is temporarily unavailable" : "Unexpected server error")
                + "\",\"code\":\"" + (unavailable ? "SERVICE_UNAVAILABLE" : "INTERNAL_ERROR") + "\"}";
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sleep(Duration delay) throws IOException {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during an injected delay", e);
        }
    }
}
