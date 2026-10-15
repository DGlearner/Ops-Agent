package io.github.dglearner.opsagent.ingest.api;

import io.github.dglearner.opsagent.ingest.auth.InvalidApiKeyException;
import io.github.dglearner.opsagent.ingest.publish.PublishFailedException;
import io.github.dglearner.opsagent.ingest.ratelimit.RateLimitExceededException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as an RFC 9457 problem detail with a stable {@code code}, plus {@code errors} for field-level
 * validation failures.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Clients should back off at least this long after Kafka trouble. */
    private static final long UNAVAILABLE_RETRY_SECONDS = 5;

    @ExceptionHandler
    ResponseEntity<ProblemDetail> invalidApiKey(InvalidApiKeyException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "ApiKey")
                .body(problem(HttpStatus.UNAUTHORIZED, "INVALID_API_KEY", e.getMessage()));
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> rateLimited(RateLimitExceededException e) {
        long seconds = Math.max(1, (e.retryAfter().toMillis() + 999) / 1000);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                .body(problem(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", e.getMessage()));
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> publishFailed(PublishFailedException e) {
        log.warn("Publishing to Kafka failed: {}", e.getMessage(), e.getCause());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(UNAVAILABLE_RETRY_SECONDS))
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "KAFKA_UNAVAILABLE",
                        "Logs could not be stored; retry the request"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return validationFailed(fieldErrors(e.getBindingResult().getFieldErrors()));
    }

    /**
     * Raised instead of {@link MethodArgumentNotValidException} when any controller parameter carries a constraint,
     * e.g. the Idempotency-Key header. Body errors are reported per field, header errors under the header name.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (ParameterValidationResult result : e.getParameterValidationResults()) {
            if (result instanceof ParameterErrors bodyErrors) {
                errors.addAll(fieldErrors(bodyErrors.getFieldErrors()));
            } else {
                String name = parameterName(result.getMethodParameter());
                result.getResolvableErrors().forEach(err ->
                        errors.add(Map.of("field", name, "message", String.valueOf(err.getDefaultMessage()))));
            }
        }
        return validationFailed(errors);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PayloadTooLargeException tooLarge) {
                return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                        .body(problem(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE", tooLarge.getMessage()));
            }
        }
        return ResponseEntity.badRequest()
                .body(problem(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "Request body is not valid JSON"));
    }

    private static ResponseEntity<Object> validationFailed(List<Map<String, String>> errors) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request is invalid");
        body.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(body);
    }

    private static List<Map<String, String>> fieldErrors(List<FieldError> errors) {
        return errors.stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList();
    }

    private static String parameterName(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.name().isEmpty()) {
            return header.name();
        }
        return String.valueOf(parameter.getParameterName());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
