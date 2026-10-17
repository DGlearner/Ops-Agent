package io.github.dglearner.opsagent.contracts;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One valid instance of every contract record. */
final class Samples {

    static final Instant T0 = Instant.parse("2026-01-01T00:05:00Z");
    static final UUID EVENT_ID = UUID.fromString("019a0000-0000-7000-8000-000000000001");

    static RawLogRecord raw() {
        return new RawLogRecord(EVENT_ID, UUID.fromString("019a0000-0000-7000-8000-000000000002"), "demo-source",
                SourceType.LINUX, "app-01", "sshd", "/var/log/auth.log",
                "Jan 01 00:01:43 app-01 sshd[15300]: Failed password for support from 198.51.100.240 port 51370 ssh2",
                T0);
    }

    static NormalizedEvent normalized() {
        return new NormalizedEvent(EVENT_ID, "demo-source", SourceType.LINUX, "/var/log/auth.log", "app-01", "sshd",
                "ssh", "ssh_login_failed", Severity.LOW, Instant.parse("2026-01-01T00:01:43Z"), T0,
                raw().message(), Map.of("user", "support", "src_ip", "198.51.100.240", "port", 51370),
                List.of("linux", "ssh"));
    }

    static UnparsedLog unparsed() {
        return new UnparsedLog(raw(), "no linux pattern matched");
    }

    static RuleDefinition rule() {
        return new RuleDefinition("ssh-brute-force", 1, true, "SSH brute force",
                new RuleDefinition.Match(List.of("ssh_login_failed", "ssh_invalid_user"), List.of(SourceType.LINUX),
                        null, null),
                List.of("host", "fields.src_ip"), Duration.ofMinutes(5), 5, Severity.MEDIUM, Duration.ofMinutes(10),
                "{count} failed SSH logins from {fields.src_ip} on {host} within {window}");
    }

    static TopologyEntry topology() {
        return new TopologyEntry("login-service", "mysql", List.of("mysql"), "payments", 3);
    }

    static Alert alert() {
        return new Alert(Ids.nameBased("ssh-brute-force|host=app-01|fields.src_ip=198.51.100.240|" + T0),
                "ssh-brute-force", 1, "ssh-brute-force|host=app-01|fields.src_ip=198.51.100.240", Severity.MEDIUM,
                "sshd", "sshd", "app-01", Map.of("host", "app-01", "fields.src_ip", "198.51.100.240"),
                T0.minus(Duration.ofMinutes(5)), T0, 5, 5, T0.minusSeconds(200), T0, List.of(EVENT_ID),
                "5 failed SSH logins from 198.51.100.240 on app-01 within 5m", T0,
                List.of(new Alert.Sample(EVENT_ID, T0, "Failed password for root from 198.51.100.240 port 22 ssh2",
                        null)));
    }

    static IncidentCandidate candidate() {
        return new IncidentCandidate("mysql", T0, T0.plusSeconds(90), List.of(alert().alertId()),
                List.of("mysql", "auth-api"), List.of("db-01", "app-01"), Severity.HIGH, 2, List.of());
    }

    static AgentTask agentTask() {
        return new AgentTask(UUID.fromString("01a14d7e-1c2b-7a00-8000-000000000001"), AgentTask.INCIDENT_ANALYSIS,
                UUID.fromString("01a14d7e-1c2b-7a00-8000-000000000002"), "system", T0, T0.plusSeconds(180), "zh");
    }

    static AgentResult agentResult() {
        return new AgentResult(agentTask().taskId(), agentTask().incidentId(), AgentResult.COMPLETED,
                "order-service and payment-service lost their shared MySQL", false,
                List.of(new AgentResult.Hypothesis("shop-mysql", "The shop's MySQL stopped answering", "high",
                        List.of(new AgentResult.Evidence("alert", alert().alertId().toString(), "DATABASE_UNAVAILABLE"),
                                new AgentResult.Evidence("topology", "shop-mysql", "shared dependency")))),
                List.of("Check the MySQL container"), "test-model", 4, 5300, null, T0.plusSeconds(20));
    }

    private Samples() {
    }
}
