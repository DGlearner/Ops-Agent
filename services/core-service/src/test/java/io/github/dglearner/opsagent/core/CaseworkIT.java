package io.github.dglearner.opsagent.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.opsagent.contracts.OpsJson;
import io.github.dglearner.opsagent.contracts.Severity;
import io.github.dglearner.opsagent.core.remediation.LabOperator;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.TreeMap;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Cases, remediations and verifications (docs/agent-design.md §13): who may write what on a case, a card run once
 * against the lab, and a verification that only counts with the current token. The lab is FakeLab.
 */
class CaseworkIT extends CoreTestSupport {

    @Autowired
    FakeLab lab;

    @BeforeEach
    void resetLab() {
        lab.reset();
    }

    @Test
    void aCaseKeepsWhoSaidWhatAndOnlyAcceptsWhatTheSourceMayClaim() throws Exception {
        String incident = incident(Instant.parse("2031-03-01T10:00:00Z"));
        HttpResponse<String> created = post("/api/v1/cases", "{\"incident_ids\":[\"" + incident + "\"]}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode c = json(created);
        String id = c.path("case_id").asString();
        assertThat(c.path("status").asString()).isEqualTo("open");
        // The same incident again: the same case.
        HttpResponse<String> again = post("/api/v1/cases", "{\"incident_ids\":[\"" + incident + "\"]}");
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(json(again).path("case_id").asString()).isEqualTo(id);

        int v = c.path("version").asInt();
        // A model cannot write a fact, nor quote the user without the words, nor decide.
        assertProblem(entry(id, "{\"kind\":\"fact\",\"text\":\"MySQL 停了\",\"source_type\":\"model\"}", v), 400,
                "VALIDATION_FAILED");
        assertProblem(entry(id, "{\"kind\":\"statement\",\"text\":\"用户说重启过\",\"source_type\":\"user\"}", v), 400,
                "VALIDATION_FAILED");
        assertProblem(entry(id, "{\"kind\":\"decision\",\"text\":\"先重启\",\"source_type\":\"model\"}", v), 400,
                "VALIDATION_FAILED");
        assertProblem(entry(id, "{\"kind\":\"hypothesis\",\"text\":\"MySQL 停了\",\"status\":\"supported\","
                + "\"source_type\":\"model\"}", v), 400, "VALIDATION_FAILED");
        // What may be written is, with its source; a write on an old version is refused.
        JsonNode h = json(entry(id, "{\"kind\":\"hypothesis\",\"text\":\"MySQL 停了\",\"source_type\":\"model\","
                + "\"evidence\":[{\"kind\":\"alert\",\"id\":\"a1\"}]}", v));
        assertThat(h.path("version").asInt()).isEqualTo(v + 1);
        assertThat(h.path("state_version").asInt()).as("a hypothesis is not what actions are decided on")
                .isEqualTo(c.path("state_version").asInt());
        assertProblem(entry(id, "{\"kind\":\"note\",\"text\":\"x\",\"source_type\":\"system\"}", v), 409,
                "STALE_VERSION");
        JsonNode first = json(entry(id, "{\"kind\":\"constraint\",\"text\":\"不要重启数据库\",\"source_type\":\"user\","
                + "\"data\":{\"quote\":\"先别动数据库\",\"forbids\":[\"start_container\"]}}", v + 1));
        assertThat(first.path("state_version").asInt()).isEqualTo(c.path("state_version").asInt() + 1);
        String constraint = first.path("entries").get(1).path("entry_id").asString();
        JsonNode lifted = json(entry(id, "{\"kind\":\"constraint\",\"text\":\"可以重启数据库\",\"source_type\":\"user\","
                + "\"data\":{\"quote\":\"现在可以重启了\",\"forbids\":[]},\"supersedes\":\"" + constraint + "\"}", v + 2));
        assertThat(lifted.path("entries").get(1).path("status").asString()).isEqualTo("superseded");
        assertThat(lifted.path("entries").get(2).path("supersedes").asString()).isEqualTo(constraint);

        // The hypothesis is supported only with evidence; then only a person resolves the case.
        String hypothesis = lifted.path("entries").get(0).path("entry_id").asString();
        JsonNode supported = json(post("/api/v1/cases/" + id + "/entries/" + hypothesis + "/status",
                "{\"status\":\"supported\",\"expected_version\":" + lifted.path("version").asInt() + "}"));
        assertThat(supported.path("entries").get(0).path("status").asString()).isEqualTo("supported");
        int now = supported.path("version").asInt();
        assertProblem(post("/api/v1/cases/" + id + "/transitions", "{\"to_status\":\"resolved\",\"actor\":\"agent\","
                + "\"expected_version\":" + now + "}"), 409, "SIGN_OFF_REQUIRED");
        JsonNode resolved = json(post("/api/v1/cases/" + id + "/transitions", "{\"to_status\":\"resolved\","
                + "\"actor\":\"user\",\"expected_version\":" + now + "}"));
        assertThat(resolved.path("status").asString()).isEqualTo("resolved");
        // Resolved: the incident may start a new case.
        assertThat(post("/api/v1/cases", "{\"incident_ids\":[\"" + incident + "\"]}").statusCode()).isEqualTo(201);
    }

    @Test
    void aConfirmedCardRunsOnceAndTheCaseWaitsForItsVerification() throws Exception {
        String incident = incident(Instant.parse("2031-03-02T10:00:00Z"));
        JsonNode c = json(post("/api/v1/cases", "{\"incident_ids\":[\"" + incident + "\"]}"));
        String id = c.path("case_id").asString();
        int state = c.path("state_version").asInt();
        Map<String, Object> params = Map.of("name", "shop-mysql");
        String digest = digest(id, incident, "start_container", params);

        // What was confirmed is not what would run: nothing happens.
        assertProblem(remediate("a-1", id, incident, "start_container", "{\"name\":\"shop-redis\"}", digest, state),
                409, "PAYLOAD_MISMATCH");
        assertProblem(remediate("a-1", id, incident, "start_container", "{\"name\":\"shop-mysql\"}", digest, state - 1),
                409, "STALE_VERSION");
        assertProblem(remediate("a-1", id, incident, "drop_database", "{}", digest, state), 400, "VALIDATION_FAILED");
        assertThat(lab.changes).isEmpty();

        JsonNode run = json(remediate("a-1", id, incident, "start_container", "{\"name\":\"shop-mysql\"}", digest,
                state));
        assertThat(run.path("status").asString()).as(run.toString()).isEqualTo("executed");
        assertThat(run.path("precheck").path("state").asString()).isEqualTo("exited");
        assertThat(run.path("receipt").path("observed").path("state").asString()).isEqualTo("running");
        // The same card again: the same receipt, the container not started twice.
        JsonNode again = json(remediate("a-1", id, incident, "start_container", "{\"name\":\"shop-mysql\"}", digest,
                state));
        assertThat(again.path("remediation_id").asString()).isEqualTo(run.path("remediation_id").asString());
        assertThat(lab.changes).containsExactly("start shop-mysql");
        assertThat(getJson("/api/v1/cases/" + id).path("status").asString()).isEqualTo("verifying");

        // Two workers: the first one's lease runs out, the second takes over; the first one's late answer is refused.
        AtomicReference<HttpResponse<String>> claimed = new AtomicReference<>();
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> {
            claimed.set(claim("worker-a"));
            return claimed.get().statusCode() == 200;
        });
        int tokenA = json(claimed.get()).path("token").asInt();
        assertThat(verificationOf(id).path("status").asString()).isEqualTo("leased");
        Thread.sleep(2500);
        HttpResponse<String> taken = claim("worker-b");
        assertThat(taken.statusCode()).isEqualTo(200);
        int tokenB = json(taken).path("token").asInt();
        assertThat(tokenB).isEqualTo(tokenA + 1);
        String vid = json(taken).path("verification_id").asString();
        assertProblem(post("/api/v1/verifications/" + vid + "/result", "{\"token\":" + tokenA
                + ",\"outcome\":\"not_recovered\"}"), 409, "STALE_TOKEN");
        JsonNode done = json(post("/api/v1/verifications/" + vid + "/result", "{\"token\":" + tokenB
                + ",\"outcome\":\"recovered\",\"result\":{\"checks\":[]}}"));
        assertThat(done.path("outcome").asString()).isEqualTo("recovered");
        // Recovered is not resolved: the case is being watched until a person signs it off.
        assertThat(getJson("/api/v1/cases/" + id).path("status").asString()).isEqualTo("monitoring");
    }

    @Test
    void aCardIsNotRunWhenTheLabNoLongerLooksLikeItDid() throws Exception {
        String incident = incident(Instant.parse("2031-03-03T10:00:00Z"));
        JsonNode c = json(post("/api/v1/cases", "{\"incident_ids\":[\"" + incident + "\"]}"));
        String id = c.path("case_id").asString();
        lab.containers.put("shop-mysql", "running");
        Map<String, Object> params = Map.of("name", "shop-mysql");
        JsonNode run = json(remediate("b-1", id, incident, "start_container", "{\"name\":\"shop-mysql\"}",
                digest(id, incident, "start_container", params), c.path("state_version").asInt()));
        assertThat(run.path("status").asString()).isEqualTo("blocked");
        assertThat(run.path("receipt").path("summary").asString()).contains("已经在运行");
        assertThat(lab.changes).isEmpty();
        assertThat(getJson("/api/v1/cases/" + id).path("status").asString()).isEqualTo("diagnosing");

        // A start that does not take: failed from what is read back, not from the call.
        lab.containers.put("shop-mysql", "exited");
        lab.startDoesNothing = true;
        int state = getJson("/api/v1/cases/" + id).path("state_version").asInt();
        JsonNode failed = json(remediate("b-2", id, incident, "start_container", "{\"name\":\"shop-mysql\"}",
                digest(id, incident, "start_container", params), state));
        assertThat(failed.path("status").asString()).isEqualTo("failed");
        assertThat(getJson("/api/v1/verifications?case_id=" + id).path("items")).isEmpty();
    }

    @Test
    void aNewRemediationMakesTheOldVerificationStale() throws Exception {
        String incident = incident(Instant.parse("2031-03-04T10:00:00Z"));
        JsonNode c = json(post("/api/v1/cases", "{\"incident_ids\":[\"" + incident + "\"]}"));
        String id = c.path("case_id").asString();
        Map<String, Object> on = Map.of("name", "payment.fast-fail", "on", true, "minutes", 5);
        JsonNode first = json(remediate("c-1", id, incident, "set_switch", OpsJson.toJson(on),
                digest(id, incident, "set_switch", on), c.path("state_version").asInt()));
        assertThat(first.path("status").asString()).isEqualTo("executed");
        Map<String, Object> off = Map.of("name", "payment.fast-fail", "on", false, "minutes", 5);
        int state = getJson("/api/v1/cases/" + id).path("state_version").asInt();
        json(remediate("c-2", id, incident, "set_switch", OpsJson.toJson(off), digest(id, incident, "set_switch", off),
                state));
        JsonNode verifications = getJson("/api/v1/verifications?case_id=" + id).path("items");
        assertThat(verifications).hasSize(2);
        assertThat(verifications.get(0).path("status").asString()).isEqualTo("stale");
        assertThat(verifications.get(0).path("criteria").path("goal").asString()).isEqualTo("contain");
        assertThat(verifications.get(1).path("status").asString()).isEqualTo("pending");
        assertThat(lab.changes).containsExactly("switch payment.fast-fail true 5", "switch payment.fast-fail false 5");
    }

    @Test
    void whatIsDoneKeepsTheRunItCameFrom() throws Exception {
        String incident = incident(Instant.parse("2031-03-05T10:00:00Z"));
        JsonNode c = json(post("/api/v1/cases", "{\"incident_ids\":[\"" + incident + "\"]}"));
        String id = c.path("case_id").asString();
        JsonNode noted = json(entry(id, "{\"kind\":\"hypothesis\",\"text\":\"可能是连接池\",\"source_type\":\"model\","
                + "\"run_id\":\"run-1\"}", c.path("version").asInt()));
        assertThat(noted.path("entries").get(0).path("run_id").asString()).isEqualTo("run-1");

        lab.containers.put("shop-mysql", "exited");
        Map<String, Object> params = Map.of("name", "shop-mysql");
        String body = "{\"action_id\":\"o-1\",\"case_id\":\"" + id + "\",\"incident_id\":\"" + incident
                + "\",\"op\":\"start_container\",\"params\":{\"name\":\"shop-mysql\"},\"payload_digest\":\""
                + digest(id, incident, "start_container", params) + "\",\"expected_case_version\":"
                + c.path("state_version").asInt() + ",\"run_id\":\"run-1\",\"session_id\":\"s-1\","
                + "\"snapshot_digest\":\"sha256:abc\"}";
        JsonNode done = json(post("/api/v1/remediations", body));
        assertThat(done.path("status").asString()).isEqualTo("executed");
        assertThat(done.path("run_id").asString()).isEqualTo("run-1");
        assertThat(done.path("session_id").asString()).isEqualTo("s-1");
        assertThat(done.path("snapshot_digest").asString()).isEqualTo("sha256:abc");
        // The worker sees it when it claims, and it is not part of what was confirmed: the digest above did not hold it.
        assertThat(verificationOf(id).path("run_id").asString()).isEqualTo("run-1");

        assertProblem(post("/api/v1/remediations", body.replace("\"o-1\"", "\"o-2\"").replace("run-1",
                "r".repeat(101))), 400, "VALIDATION_FAILED");
    }

    @Test
    void aTransactionThatIsItselfWaitingIsNotEndedAndAnUnknownBlockCountStaysUnknown() throws Exception {
        String incident = incident(Instant.parse("2031-03-07T10:00:00Z"));
        JsonNode c = json(post("/api/v1/cases", "{\"incident_ids\":[\"" + incident + "\"]}"));
        String id = c.path("case_id").asString();
        lab.transactions.add(new LabOperator.Transaction("41", 7, 30, "LOCK WAIT", null, "svc"));
        lab.transactions.add(new LabOperator.Transaction("42", 8, 25, "RUNNING", null, "svc"));

        JsonNode listed = json(get("/api/v1/lab/transactions?min_age_s=0")).path("items");
        assertThat(listed.get(0).path("state").asString()).isEqualTo("LOCK WAIT");
        assertThat(listed.get(1).path("blocking").isNull() || listed.get(1).path("blocking").isMissingNode()).isTrue();

        JsonNode waiting = json(post("/api/v1/remediations", kill(id, incident, "k-1", "41", 7, c)));
        assertThat(waiting.path("status").asString()).isEqualTo("blocked");
        assertThat(waiting.path("receipt").path("summary").asString()).contains("自己在等锁");
        assertThat(lab.changes).doesNotContain("kill 7");

        c = json(get("/api/v1/cases/" + id));
        JsonNode holder = json(post("/api/v1/remediations", kill(id, incident, "k-2", "42", 8, c)));
        assertThat(holder.path("status").asString()).isEqualTo("executed");
        assertThat(holder.path("precheck").path("detail").asString()).contains("阻塞数未知");
        assertThat(lab.changes).contains("kill 8");
    }

    private static String kill(String caseId, String incident, String action, String trx, long thread, JsonNode c)
            throws Exception {
        Map<String, Object> params = Map.of("trx_id", trx, "thread_id", thread);
        return "{\"action_id\":\"" + action + "\",\"case_id\":\"" + caseId + "\",\"incident_id\":\"" + incident
                + "\",\"op\":\"kill_transaction\",\"params\":{\"trx_id\":\"" + trx + "\",\"thread_id\":" + thread
                + "},\"payload_digest\":\"" + digest(caseId, incident, "kill_transaction", params)
                + "\",\"expected_case_version\":" + c.path("state_version").asInt() + "}";
    }

    private String incident(Instant t) throws Exception {
        send(alert("shop-database-unavailable", "order-service", null, Severity.HIGH, t));
        return awaitIncidents(t, 1, 1).getFirst().path("incident_id").asString();
    }

    private HttpResponse<String> entry(String caseId, String body, int version) throws Exception {
        return post("/api/v1/cases/" + caseId + "/entries", body.substring(0, body.length() - 1)
                + ",\"expected_version\":" + version + "}");
    }

    private HttpResponse<String> remediate(String actionId, String caseId, String incidentId, String op,
            String params, String digest, int state) throws Exception {
        return post("/api/v1/remediations", "{\"action_id\":\"" + actionId + "\",\"case_id\":\"" + caseId
                + "\",\"incident_id\":\"" + incidentId + "\",\"op\":\"" + op + "\",\"params\":" + params
                + ",\"payload_digest\":\"" + digest + "\",\"expected_case_version\":" + state + "}");
    }

    private HttpResponse<String> claim(String worker) throws Exception {
        return post("/api/v1/verifications/claim", "{\"worker\":\"" + worker + "\"}");
    }

    private JsonNode verificationOf(String caseId) throws Exception {
        return getJson("/api/v1/verifications?case_id=" + caseId).path("items").get(0);
    }

    /** As the agent computes it: sorted keys, no spaces, SHA-256. */
    static String digest(String caseId, String incidentId, String op, Map<String, Object> params) throws Exception {
        Map<String, Object> payload = new TreeMap<>(Map.of("case_id", caseId, "incident_id", incidentId, "op", op,
                "params", new TreeMap<>(params)));
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(OpsJson.toJson(payload).getBytes(StandardCharsets.UTF_8));
        return "sha256:" + HexFormat.of().formatHex(hash);
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode() / 100).as(response.body()).isEqualTo(2);
        return OpsJson.mapper().readTree(response.body());
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(OpsJson.mapper().readTree(response.body()).path("code").asString()).isEqualTo(code);
    }

}
