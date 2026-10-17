package io.github.dglearner.opsagent.core.shared;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * An error answered as RFC 9457 Problem Details with a stable {@code code} (docs/project-design.md §7.1), e.g.
 * {@code ALERT_NOT_FOUND}.
 */
public class ApiProblem extends ErrorResponseException {

    public ApiProblem(HttpStatus status, String code, String detail) {
        super(status, problem(status, code, detail), null);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
