package io.github.dglearner.opsagent.ingest.publish;

/** Kafka did not acknowledge every record in time. The client may retry the whole request. */
public class PublishFailedException extends RuntimeException {

    public PublishFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
