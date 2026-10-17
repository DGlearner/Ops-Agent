package io.github.dglearner.opsagent.stream.parse;

import static io.github.dglearner.opsagent.stream.Datasets.raw;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.opsagent.contracts.NormalizedEvent;
import io.github.dglearner.opsagent.contracts.RawLogRecord;
import io.github.dglearner.opsagent.contracts.Severity;
import io.github.dglearner.opsagent.contracts.SourceType;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ParsersTest {

    private final ParserRegistry registry = ParserRegistry.withDefaultParsers();

    @Test
    void nginxServerErrorAndSlowRequest() {
        NormalizedEvent e = parsed(raw(SourceType.NGINX, "web-01", "nginx",
                "203.0.113.33 - - [01/Jan/2026:00:06:07 +0000] \"GET /api/orders HTTP/1.1\" 502 237 \"-\" "
                        + "\"Mozilla/5.0\" upstream=\"10.0.2.11:8080\" request_time=3.274"));

        assertThat(e.eventType()).isEqualTo("http_server_error");
        assertThat(e.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(e.timestamp()).isEqualTo(Instant.parse("2026-01-01T00:06:07Z"));
        assertThat(e.fields()).containsEntry("status", 502L).containsEntry("upstream", "10.0.2.11:8080")
                .containsEntry("request_time", 3.274).containsEntry("slow", true);
        assertThat(e.tags()).contains("slow");
    }

    @Test
    void sshFailedPasswordForInvalidUser() {
        NormalizedEvent e = parsed(raw(SourceType.LINUX, "app-01", "sshd",
                "Jan 01 00:01:43 app-01 sshd[15300]: Failed password for invalid user support from 198.51.100.240 "
                        + "port 51370 ssh2"));

        assertThat(e.eventType()).isEqualTo("ssh_login_failed");
        assertThat(e.fields()).containsEntry("user", "support").containsEntry("src_ip", "198.51.100.240")
                .containsEntry("invalid_user", true).containsEntry("port", 51370L);
    }

    @Test
    void sudoKeepsWorkingDirectoryAndRunAsUser() {
        NormalizedEvent e = parsed(raw(SourceType.LINUX, "db-01", "sudo",
                "Jan 01 00:00:48 db-01 sudo: nginx : TTY=pts/1 ; PWD=/home/nginx ; USER=root ; "
                        + "COMMAND=/usr/bin/journalctl -n 20"));

        assertThat(e.fields()).containsEntry("user", "nginx").containsEntry("pwd", "/home/nginx")
                .containsEntry("run_as", "root").containsEntry("command", "/usr/bin/journalctl -n 20");
    }

    @Test
    void diskCheckSeverityFollowsStatus() {
        NormalizedEvent e = parsed(raw(SourceType.LINUX, "db-01", "node-exporter",
                "Jan 01 00:03:35 db-01 node-exporter[7649]: disk_check mount=/data usage_percent=93 status=critical"));

        assertThat(e.eventType()).isEqualTo("disk_usage");
        assertThat(e.severity()).isEqualTo(Severity.HIGH);
        assertThat(e.fields()).containsEntry("mount", "/data").containsEntry("usage_percent", 93L);
    }

    @Test
    void unknownSyslogProgramIsStillAnEvent() {
        NormalizedEvent e = parsed(raw(SourceType.LINUX, "app-01", null,
                "Jan 01 00:00:01 app-01 postfix/smtpd[811]: connect from unknown[203.0.113.9]"));

        assertThat(e.eventType()).isEqualTo("syslog");
        assertThat(e.component()).isEqualTo("postfix/smtpd");
    }

    @Test
    void syslogYearRollsBackAcrossNewYear() {
        RawLogRecord line = new RawLogRecord(io.github.dglearner.opsagent.contracts.Ids.newId(), null, "t",
                SourceType.LINUX, "h", null, null, "Dec 31 23:59:58 h kernel: tick", Instant.parse("2026-01-01T00:00:05Z"));

        assertThat(parsed(line).timestamp()).isEqualTo(Instant.parse("2025-12-31T23:59:58Z"));
    }

    @Test
    void mysqlLinesByContent() {
        assertThat(parsed(raw(SourceType.MYSQL, "db-01", "mysql",
                "Jan 01 00:04:29 db-01 mysql-healthcheck[2513]: ERROR connect mysql://db-01:3306 failed: "
                        + "Connection refused")).fields()).containsEntry("target", "db-01:3306");
        NormalizedEvent slow = parsed(raw(SourceType.MYSQL, "db-02", "mysql",
                "Jan 01 00:05:56 db-02 mysqld[63586]: [Warning] Slow query db=users query_time=1.775s "
                        + "rows_examined=1659"));
        assertThat(slow.eventType()).isEqualTo("mysql_slow_query");
        assertThat(slow.fields()).containsEntry("query_seconds", 1.775);
        assertThat(parsed(raw(SourceType.MYSQL, "db-01", "mysql",
                "Can't connect to MySQL server on 'db-01' (111)")).eventType()).isEqualTo("mysql_connection_refused");
    }

    @Test
    void appLogfmtWithQuotedValues() {
        NormalizedEvent e = parsed(raw(SourceType.APP_SERVICE, "worker-01", null,
                "2026-01-01T00:10:50Z ERROR service=payment-api host=worker-01 event=db_query_failed "
                        + "db_host=db-01:3306 error=\"Connection refused\" attempt=1"));

        assertThat(e.eventType()).isEqualTo("app_db_connection_error");
        assertThat(e.service()).isEqualTo("payment-api");
        assertThat(e.fields()).containsEntry("error", "Connection refused").containsEntry("attempt", 1L)
                .containsEntry("level", "ERROR");
    }

    @Test
    void ecsJsonLineTakesItsTypeFromEventActionAndKeepsItsFields() {
        NormalizedEvent e = parsed(raw(SourceType.APP_SERVICE, "kozy", null, """
                {"@timestamp":"2026-10-16T10:10:47.123456789Z","log":{"level":"WARN",\
                "logger":"io.github.dglearner.shop.order.order.OrderService"},"process":{"pid":1,\
                "thread":{"name":"exec-1"}},"service":{"name":"order-service","node":{}},\
                "message":"Inventory reservation failed: no response within the read timeout",\
                "trace.id":"dc83b5e63bb1cc9ccd114163b66d80fc","span.id":"ee08a3fcd75b8efe",\
                "event":{"action":"dependency_call_failed"},"dependency":"inventory","operation":"reserve",\
                "order_id":"01a1441a","failure":"TIMEOUT","attempt":2,"ecs":{"version":"8.11"}}"""));

        assertThat(e.eventType()).isEqualTo("dependency_call_failed");
        assertThat(e.severity()).isEqualTo(Severity.LOW);
        assertThat(e.service()).isEqualTo("order-service");
        assertThat(e.component()).isEqualTo("OrderService");
        assertThat(e.timestamp()).isEqualTo(Instant.parse("2026-10-16T10:10:47.123456789Z"));
        assertThat(e.message()).isEqualTo("Inventory reservation failed: no response within the read timeout");
        assertThat(e.fields()).containsEntry("dependency", "inventory").containsEntry("failure", "TIMEOUT")
                .containsEntry("order_id", "01a1441a").containsEntry("trace.id", "dc83b5e63bb1cc9ccd114163b66d80fc")
                .containsEntry("attempt", 2L).containsEntry("level", "WARN")
                .doesNotContainKeys("process.pid", "ecs.version", "event.action", "message", "@timestamp");
    }

    @Test
    void ecsJsonErrorKeepsTheErrorTypeButNotTheStackTrace() {
        NormalizedEvent error = parsed(raw(SourceType.APP_SERVICE, "kozy", "inventory-service", """
                {"@timestamp":"2026-10-16T10:11:00Z","log":{"level":"ERROR","logger":"x.ApiExceptionHandler"},\
                "message":"Unhandled error","event":{"action":"unhandled_error"},\
                "error":{"type":"java.lang.IllegalStateException","message":"boom",\
                "stack_trace":"java.lang.IllegalStateException: boom\\n\\tat x.y(Y.java:1)"}}"""));
        NormalizedEvent startup = parsed(raw(SourceType.APP_SERVICE, "kozy", null, """
                {"@timestamp":"2026-10-16T10:00:00Z","log":{"level":"INFO","logger":"x.OrderApplication"},\
                "message":"Started OrderApplication in 12.1 seconds","service":{"name":"order-service"}}"""));

        assertThat(error.eventType()).isEqualTo("unhandled_error");
        assertThat(error.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(error.fields()).containsEntry("error.type", "java.lang.IllegalStateException")
                .doesNotContainKey("error.stack_trace");
        assertThat(startup.eventType()).isEqualTo("app_log");
        assertThat(startup.severity()).isEqualTo(Severity.INFO);
    }

    @Test
    void brokenJsonIsUnparsed() {
        assertThat(registry.parse(raw(SourceType.APP_SERVICE, "kozy", null, "{\"@timestamp\":\"2026-10-16T10:00:00Z\"")))
                .isInstanceOf(ParseOutcome.Unparsed.class);
        assertThat(registry.parse(raw(SourceType.APP_SERVICE, "kozy", null, "{\"message\":\"no time\"}")))
                .isInstanceOf(ParseOutcome.Unparsed.class);
    }

    @Test
    void dockerOomKill() {
        NormalizedEvent e = parsed(raw(SourceType.DOCKER, "app-02", "api-gateway",
                "2026-01-01T00:13:40Z dockerd[18631]: event=die container=api-gateway.1 "
                        + "image=registry.local/api-gateway:1.8.4 exitCode=137 OOMKilled=true"));

        assertThat(e.eventType()).isEqualTo("container_oom_killed");
        assertThat(e.severity()).isEqualTo(Severity.HIGH);
        assertThat(e.fields()).containsEntry("exitCode", 137L).containsEntry("OOMKilled", true);
    }

    @Test
    void linesWithoutTheExpectedFormatAreUnparsedWithAReason() {
        ParseOutcome outcome = registry.parse(raw(SourceType.NGINX, "web-01", "nginx", "not an access log"));

        assertThat(outcome).isInstanceOfSatisfying(ParseOutcome.Unparsed.class,
                u -> assertThat(u.log().reason()).isEqualTo("no nginx pattern matched"));
    }

    @Test
    void secretsAreRedactedInMessageAndFields() {
        NormalizedEvent e = parsed(raw(SourceType.APP_SERVICE, "app-01", "auth-api",
                "2026-01-01T00:00:00Z WARN service=auth-api password=hunter2 token=abc123 "
                        + "url=\"postgres://app:s3cret@db-01/app\" msg=\"login failed for bob\""));

        assertThat(e.message()).doesNotContain("hunter2", "abc123", "s3cret");
        assertThat(e.fields()).containsEntry("password", "***").containsEntry("token", "***")
                .containsEntry("url", "postgres://app:***@db-01/app");
        assertThat(e.eventType()).isEqualTo("app_login_failed");
    }

    @Test
    void secretsAreRedactedInJsonLinesFieldsIncluded() {
        NormalizedEvent e = parsed(raw(SourceType.APP_SERVICE, "app-01", "auth-api",
                "{\"@timestamp\":\"2026-01-01T00:00:00Z\",\"message\":\"login with password=fake-secret-12\","
                        + "\"http\":{\"request\":{\"headers\":{\"Authorization\":\"Bearer fake-opaque-12\"}}}}"));

        assertThat(e.message()).isEqualTo("login with password=***");
        assertThat(e.fields()).containsEntry("http.request.headers.Authorization", "***");
        assertThat(e.toString()).doesNotContain("fake-");
    }

    @Test
    void longOrCutOffLinesAreStillMaskedInUnparsed() {
        for (String line : List.of(
                "{\"message\":\"login\",\"password\":\"fake-secret-18",
                "{\"message\":\"login\",\"password\":\"fake-" + "a".repeat(16 * 1024) + "\"")) {
            ParseOutcome outcome = registry.parse(raw(SourceType.APP_SERVICE, "app-01", "auth-api", line));

            assertThat(outcome).isInstanceOfSatisfying(ParseOutcome.Unparsed.class, u ->
                    assertThat(u.log().raw().message()).doesNotContain("fake-"));
        }
    }

    /** The whole chain, parser and redaction, on a thread with a deliberately small stack (see RedactorTest). */
    @Test
    void longQuotedLogfmtValuesNeedNoDeepRecursion() throws InterruptedException {
        for (String value : List.of("x".repeat(16 * 1024), "x\\\"".repeat(4 * 1024))) {
            RawLogRecord line = raw(SourceType.APP_SERVICE, "worker-01", null,
                    "2026-01-01T00:10:50Z ERROR service=payment-api event=db_query_failed error=\"" + value + "\" attempt=1");
            AtomicReference<Object> result = new AtomicReference<>();
            Thread thread = new Thread(null, () -> {
                try {
                    result.set(registry.parse(line));
                } catch (Throwable e) {
                    result.set(e);
                }
            }, "small-stack", 256 * 1024);
            thread.start();
            thread.join();

            assertThat(result.get()).as("outcome, or the error thrown").isInstanceOfSatisfying(ParseOutcome.Parsed.class,
                    p -> assertThat(p.event().fields()).containsEntry("attempt", 1L));
        }
    }

    private NormalizedEvent parsed(RawLogRecord raw) {
        ParseOutcome outcome = registry.parse(raw);
        assertThat(outcome).as("outcome for %s", raw.message()).isInstanceOf(ParseOutcome.Parsed.class);
        return ((ParseOutcome.Parsed) outcome).event();
    }
}
