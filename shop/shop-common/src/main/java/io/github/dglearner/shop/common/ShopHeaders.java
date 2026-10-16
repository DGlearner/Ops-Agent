package io.github.dglearner.shop.common;

/** HTTP headers shared by the shop services. */
public final class ShopHeaders {

    /** Client-chosen key that makes a create request safe to retry. */
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /** Set to {@code true} on a response that repeats the result of an earlier identical request. */
    public static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private ShopHeaders() {
    }
}
