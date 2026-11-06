package io.github.dglearner.opsagent.core.remediation;

import io.github.dglearner.opsagent.contracts.OpsJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The digest of what a card says will be done: JSON with sorted keys and no spaces, SHA-256. The agent computes the
 * same (chat/actions.py, {@code json.dumps(..., sort_keys=True, separators=(",", ":"), ensure_ascii=False)}); a
 * confirm whose digest differs from the request's was made on something else than what would run.
 */
final class Digest {

    private Digest() {
    }

    static String of(Map<String, Object> payload) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(OpsJson.toJson(sorted(payload)).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> out = new TreeMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), sorted(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(Digest::sorted).toList();
        }
        return value;
    }
}
