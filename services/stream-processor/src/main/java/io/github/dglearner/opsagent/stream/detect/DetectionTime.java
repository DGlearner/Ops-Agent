package io.github.dglearner.opsagent.stream.detect;

/** Which time drives the detection windows. */
public enum DetectionTime {
    /** The time written in the log line. Correct for live data, including late or out-of-order shipping. */
    EVENT,
    /**
     * The time ingest-service received the line. Lets old datasets be replayed repeatedly: with event time, once the
     * stream time has moved past a dataset, replaying it again counts as late data and is dropped.
     */
    INGEST
}
