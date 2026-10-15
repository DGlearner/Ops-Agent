package io.github.dglearner.opsagent.contracts;

/** Topic names. Partitions and retention are defined in {@code deploy/compose/kafka/create-topics.sh}. */
public final class Topics {

    public static final String LOGS_RAW = "logs.raw";
    public static final String LOGS_NORMALIZED = "logs.normalized";
    public static final String LOGS_UNPARSED = "logs.unparsed";
    public static final String RULES_CHANGELOG = "rules.changelog";
    public static final String TOPOLOGY_CHANGELOG = "topology.changelog";
    public static final String ALERTS = "alerts";
    public static final String INCIDENT_CANDIDATES = "incident.candidates";
    public static final String INCIDENT_EVENTS = "incident.events";
    public static final String AGENT_TASKS_HIGH = "agent.tasks.high";
    public static final String AGENT_TASKS_LOW = "agent.tasks.low";
    public static final String AGENT_RESULTS = "agent.results";

    /** Dead-letter topic used by Spring Kafka error handlers. */
    public static String deadLetter(String topic) {
        return topic + ".dlt";
    }

    private Topics() {
    }
}
