package io.github.dglearner.shop.order.order;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Fingerprint of what an order request asks for, so a reused Idempotency-Key with a different request is detected.
 * Item order does not matter: lines must already be sorted by SKU.
 */
final class RequestHash {

    static String of(String userId, List<OrderLine> sortedLines, String paymentToken) {
        String canonical = userId + "\n" + paymentToken + "\n"
                + sortedLines.stream().map(l -> l.sku() + ":" + l.quantity()).collect(Collectors.joining(","));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    private RequestHash() {
    }
}
