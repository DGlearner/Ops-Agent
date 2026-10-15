package io.github.dglearner.opsagent.ingest.api;

import java.io.IOException;

/** Thrown while reading a request body that exceeds {@code ops.ingest.max-request-size}. */
public class PayloadTooLargeException extends IOException {

    public PayloadTooLargeException(long limitBytes) {
        super("Request body exceeds " + limitBytes + " bytes");
    }
}
