package io.github.dglearner.shop.order.catalog;

import io.github.dglearner.shop.common.LogEvents;
import io.github.dglearner.shop.order.redis.RedisFailureLog;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Carries out the queued cache invalidations (the outbox of §17.3): right after the change commits, and again from
 * the retry job until Redis has taken it. Without this, a Redis outage at the wrong moment would leave the old copy
 * in place for its whole time to live.
 */
@Component
public class ProductCacheInvalidations {

    private static final Logger log = LoggerFactory.getLogger(ProductCacheInvalidations.class);

    private final ProductRepository products;
    private final ProductCache cache;
    private final RedisFailureLog redisFailures;
    private final MeterRegistry registry;

    public ProductCacheInvalidations(ProductRepository products, ProductCache cache, RedisFailureLog redisFailures,
            MeterRegistry registry) {
        this.products = products;
        this.cache = cache;
        this.redisFailures = redisFailures;
        this.registry = registry;
    }

    /** MySQL failures propagate; the row stays and is retried. */
    void invalidate(String sku, long version) {
        try {
            cache.invalidate(sku, version);
        } catch (DataAccessException e) {
            redisFailures.failed("product_invalidate", e);
            registry.counter("shop.product.cache.invalidations", "result", "retry").increment();
            products.scheduleInvalidationRetry(sku);
            log.atWarn().addKeyValue(LogEvents.ACTION, "product_cache_invalidation_failed").addKeyValue("sku", sku)
                    .addKeyValue("version", version).log("Cache invalidation failed, will retry");
            return;
        }
        products.invalidationDone(sku, version);
        registry.counter("shop.product.cache.invalidations", "result", "done").increment();
    }

    /** Retries invalidations that Redis did not take, alongside the other background recovery (§7.7). */
    @Component
    @ConditionalOnBooleanProperty(name = "shop.order.recovery.enabled", matchIfMissing = true)
    static class Retry {

        private final ProductRepository products;
        private final ProductCacheInvalidations invalidations;

        Retry(ProductRepository products, ProductCacheInvalidations invalidations) {
            this.products = products;
            this.invalidations = invalidations;
        }

        @Scheduled(fixedDelayString = "${shop.order.recovery.interval}",
                initialDelayString = "${shop.order.recovery.interval}")
        void run() {
            try {
                for (Map.Entry<String, Long> due : products.dueInvalidations(50).entrySet()) {
                    if (products.claimInvalidation(due.getKey(), ProductService.LEASE)) {
                        invalidations.invalidate(due.getKey(), due.getValue());
                    }
                }
            } catch (DataAccessException e) {
                log.atWarn().addKeyValue(LogEvents.ACTION, "recovery_step_failed")
                        .addKeyValue("step", "product cache invalidations")
                        .addKeyValue("error_type", e.getClass().getSimpleName())
                        .log("Recovery step 'product cache invalidations' failed: {}", e.getMessage());
            }
        }
    }
}
