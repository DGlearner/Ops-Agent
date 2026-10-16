package io.github.dglearner.shop.order.client;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Why a call to another service failed. The distinction that matters is whether the other side may have acted:
 * only {@link Kind#NOT_SENT} guarantees it did not.
 */
public record CallFailure(Kind kind, String detail) {

    public enum Kind {
        /** No connection was made (refused, unknown host, connect timeout): the request had no effect. */
        NOT_SENT,
        /** No response within the read timeout: the request may or may not have been processed. */
        TIMEOUT,
        /** An error status, an unexpected answer, or a connection that broke mid-request. */
        ERROR
    }

    public static CallFailure from(ResourceAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpConnectTimeoutException || t instanceof ConnectException
                    || t instanceof UnknownHostException || t instanceof NoRouteToHostException) {
                return new CallFailure(Kind.NOT_SENT, t.getClass().getSimpleName());
            }
            if (t instanceof HttpTimeoutException) {
                return new CallFailure(Kind.TIMEOUT, "no response within the read timeout");
            }
        }
        return new CallFailure(Kind.ERROR, String.valueOf(e.getMostSpecificCause()));
    }

    public static CallFailure response(int status, String code) {
        return new CallFailure(Kind.ERROR, "HTTP " + status + (code.isEmpty() ? "" : " " + code));
    }

    public String describe() {
        return kind + ": " + detail;
    }
}
