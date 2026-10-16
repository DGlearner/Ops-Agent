package io.github.dglearner.shop.loadgen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Generates order requests: random users, one to three distinct SKUs, some declined cards, some limited items. */
final class OrderMix {

    static final String HOT_SKU = "SKU-2001";

    record Line(String sku, int quantity) {
    }

    record OrderRequest(String userId, List<Line> lines, String paymentToken) {
    }

    private final Random random;
    private final List<String> regularSkus;
    private final boolean hotAvailable;
    private final Options options;

    OrderMix(List<String> catalogueSkus, Options options, Random random) {
        this.random = random;
        this.options = options;
        this.regularSkus = catalogueSkus.stream().filter(s -> !s.equals(HOT_SKU)).toList();
        this.hotAvailable = catalogueSkus.contains(HOT_SKU);
        if (regularSkus.isEmpty()) {
            throw new IllegalStateException("The catalogue has no regular SKUs to order");
        }
    }

    synchronized OrderRequest next() {
        String user = "u-" + (1000 + random.nextInt(options.users()));
        List<String> skus = new ArrayList<>(regularSkus);
        Collections.shuffle(skus, random);
        int count = 1 + random.nextInt(Math.min(3, skus.size()));
        List<Line> lines = new ArrayList<>();
        for (String sku : skus.subList(0, count)) {
            lines.add(new Line(sku, 1 + random.nextInt(3)));
        }
        if (hotAvailable && random.nextDouble() < options.hotRate()) {
            lines.add(new Line(HOT_SKU, 1));
        }
        String token = random.nextDouble() < options.declineRate() ? "tok_declined" : "tok_ok";
        return new OrderRequest(user, List.copyOf(lines), token);
    }

    synchronized boolean duplicate() {
        return random.nextDouble() < options.duplicateRate();
    }

    /** Whether a duplicate goes out alongside the original (true) or after it completed (false). */
    synchronized boolean concurrentDuplicate() {
        return random.nextBoolean();
    }
}
