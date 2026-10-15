package io.github.dglearner.opsagent.ingest.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Authenticates ingest requests by API key and exposes the source id as a request attribute. Runs inside the
 * DispatcherServlet, so failures are rendered by the same exception handler as every other error.
 */
public class ApiKeyInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Api-Key";
    public static final String SOURCE_ID_ATTRIBUTE = "opsagent.ingest.sourceId";

    private final SourceRegistry sources;

    public ApiKeyInterceptor(SourceRegistry sources) {
        this.sources = sources;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String sourceId = sources.findSourceId(request.getHeader(HEADER)).orElseThrow(InvalidApiKeyException::new);
        request.setAttribute(SOURCE_ID_ATTRIBUTE, sourceId);
        return true;
    }
}
