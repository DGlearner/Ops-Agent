package io.github.dglearner.opsagent.ingest.auth;

public class InvalidApiKeyException extends RuntimeException {

    public InvalidApiKeyException() {
        super("Missing or unknown X-Api-Key");
    }
}
