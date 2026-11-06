package io.github.dglearner.opsagent.core.remediation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The digest the agent computes for a card (agent/tests/test_chat_casework.py uses the same payload and value). */
class DigestTest {

    @Test
    void keysAreSortedAtEveryLevelWhateverOrderTheyCameIn() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("on", true);
        params.put("name", "payment.fast-fail");
        params.put("minutes", 5);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("params", params);
        payload.put("op", "set_switch");
        payload.put("incident_id", "i-1");
        payload.put("case_id", "c-1");

        assertThat(Digest.of(payload)).isEqualTo("sha256:3cb2607372b08ba7e84d96459e9f0af951cd4a1e69b70fb0cdfb18d7c779e319");
    }

    @Test
    void parametersAreCheckedAgainstTheCatalog() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Operations.params("kill_transaction",
                Map.of("trx_id", "12; DROP TABLE stock", "thread_id", 7), java.util.Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Operations.params("start_container",
                Map.of("name", "ops-agent-postgres-1"), java.util.Set.of("shop-mysql")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Operations.params("set_switch", Map.of("name", "payment.fast-fail", "on", true), java.util.Set.of()))
                .containsEntry("minutes", 10);
    }
}
