package io.github.dglearner.shop.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
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
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
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

    @ExceptionHandler
    ResponseEntity<ProblemDetail> api(ApiException e) {
        ProblemDetail body = problem(e.status(), e.code(), e.getMessage());
        e.properties().forEach(body::setProperty);
        return ResponseEntity.status(e.status()).body(body);
    }

    /**
     * The database is unreachable, the connection pool is exhausted or a lock wait timed out. The same request may
     * succeed later, so this is a 503 rather than a 500.
     */
    @ExceptionHandler({TransientDataAccessException.class, DataAccessResourceFailureException.class})
    ResponseEntity<ProblemDetail> databaseUnavailable(DataAccessException e) {
        log.atWarn().addKeyValue("error_type", e.getClass().getSimpleName())
                .log("Database unavailable: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "Database is unavailable"));
    }

    /** Anything unexpected: logged with its stack trace, answered without internals. */
    @ExceptionHandler
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.internalServerError()
                .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error"));
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
    protected ResponseEntity<Object> handleServletRequestBindingException(ServletRequestBindingException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (e instanceof MissingRequestHeaderException missing) {
            return validationFailed(List.of(Map.of("field", missing.getHeaderName(), "message", "is required")));
        }
        return super.handleServletRequestBindingException(e, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
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
                .map(f -> Map.of("field", snakeCase(f.getField()), "message", String.valueOf(f.getDefaultMessage())))
                .toList();
    }

    /** Reports field paths the way clients write them in JSON: {@code items[0].unitPrice} → {@code items[0].unit_price}. */
    static String snakeCase(String path) {
        StringBuilder out = new StringBuilder(path.length() + 8);
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && Character.isLetterOrDigit(path.charAt(i - 1))) {
                    out.append('_');
                }
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String parameterName(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.name().isEmpty()) {
            return header.name();
        }
        return String.valueOf(parameter.getParameterName());
    }

    private static ProblemDetail problem(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
