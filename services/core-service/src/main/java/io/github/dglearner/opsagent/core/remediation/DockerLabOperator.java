package io.github.dglearner.opsagent.core.remediation;

import io.github.dglearner.opsagent.contracts.OpsJson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The lab on this machine: docker for its containers and its MySQL, order-service's management port for the
 * switches. Arguments are passed as separate words, never through a shell; the only values that reach a command are a
 * container name from the configuration and numbers.
 */
@Component
class DockerLabOperator implements LabOperator {

    private static final String TRANSACTIONS = """
            SELECT t.trx_id, t.trx_mysql_thread_id, TIMESTAMPDIFF(SECOND, t.trx_started, NOW()), t.trx_state,
                   IF(@@performance_schema = 1, (SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                    WHERE w.BLOCKING_ENGINE_TRANSACTION_ID = t.trx_id), -1), IFNULL(p.USER, '')
            FROM information_schema.innodb_trx t
            LEFT JOIN information_schema.processlist p ON p.ID = t.trx_mysql_thread_id
            WHERE t.trx_started <= NOW() - INTERVAL %d SECOND
            ORDER BY t.trx_started""";

    private final LabProperties properties;
    private final HttpClient http = HttpClient.newHttpClient();

    DockerLabOperator(LabProperties properties) {
        this.properties = properties;
    }

    @Override
    public String containerState(String name) {
        Result result = run(List.of("docker", "inspect", "-f", "{{.State.Status}}", docker(name)));
        if (result.exit() != 0) {
            if (result.output().contains("No such object") || result.output().contains("No such container")) {
                return "missing";
            }
            throw new LabException("docker inspect failed: " + result.output(), false);
        }
        return result.output().strip();
    }

    @Override
    public void startContainer(String name) {
        Result result = run(List.of("docker", "start", docker(name)));
        if (result.exit() != 0) {
            throw new LabException("docker start failed: " + result.output(), false);
        }
    }

    @Override
    public List<Transaction> transactions(long minAgeSeconds) {
        Result result = mysql(TRANSACTIONS.formatted(Math.max(0, minAgeSeconds)));
        List<Transaction> rows = new ArrayList<>();
        for (String line : result.output().split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 6 || !f[0].matches("\\d+")) {
                continue;
            }
            int blocking = Integer.parseInt(f[4]);
            rows.add(new Transaction(f[0], Long.parseLong(f[1]), Long.parseLong(f[2]), f[3],
                    blocking < 0 ? null : blocking, f[5]));
        }
        return rows;
    }

    @Override
    public void killThread(long threadId) {
        if (threadId <= 0) {
            throw new IllegalArgumentException("thread id " + threadId);
        }
        mysql("KILL " + threadId);
    }

    @Override
    public List<Switch> switches() {
        JsonNode body = call(HttpRequest.newBuilder(switchesUri()).GET());
        List<Switch> result = new ArrayList<>();
        for (JsonNode s : body) {
            result.add(toSwitch(s));
        }
        return result;
    }

    @Override
    public Switch setSwitch(String name, boolean on, int minutes) {
        String body = OpsJson.toJson(Map.of("name", name, "on", on, "minutes", minutes));
        return toSwitch(call(HttpRequest.newBuilder(switchesUri()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))));
    }

    private Result mysql(String sql) {
        Result result = run(List.of("docker", "exec", "-e", "MYSQL_PWD=" + properties.mysqlRootPassword(),
                properties.mysqlContainer(), "mysql", "-uroot", "-B", "-N", "-e", sql));
        if (result.exit() != 0) {
            throw new LabException("mysql failed: " + result.output(), false);
        }
        return result;
    }

    private String docker(String name) {
        String container = properties.containers().get(name);
        if (container == null) {
            throw new IllegalArgumentException("No container " + name + " in ops.lab.containers");
        }
        return container;
    }

    private URI switchesUri() {
        return properties.orderManagement().resolve("/actuator/switches");
    }

    private JsonNode call(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = http.send(request.timeout(properties.commandTimeout()).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new LabException("order-service answered " + response.statusCode() + ": "
                        + response.body().substring(0, Math.min(200, response.body().length())), false);
            }
            return OpsJson.mapper().readTree(response.body());
        } catch (IOException e) {
            // Sent or not, nobody knows: the switch may have been set.
            throw new LabException("order-service did not answer: " + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LabException("interrupted", true);
        }
    }

    private static Switch toSwitch(JsonNode s) {
        String until = s.path("until").asString(null);
        return new Switch(s.path("name").asString(), s.path("on").asBoolean(),
                until == null || until.isEmpty() ? null : Instant.parse(until));
    }

    private record Result(int exit, String output) {
    }

    private Result run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(properties.commandTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new LabException(command.get(0) + " " + command.get(1) + " timed out", true);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            // mysql warns about the password on the command line even with MYSQL_PWD; not an error.
            output = output.lines().filter(l -> !l.startsWith("mysql: [Warning]")).reduce("", (a, b) -> a + b + "\n");
            return new Result(process.exitValue(), output.length() > 4000 ? output.substring(0, 4000) : output);
        } catch (IOException e) {
            throw new LabException("cannot run " + command.get(0) + ": " + e.getMessage(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LabException("interrupted", true);
        }
    }
}
