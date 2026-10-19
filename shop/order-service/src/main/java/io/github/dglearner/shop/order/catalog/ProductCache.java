package io.github.dglearner.shop.order.catalog;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.dglearner.shop.common.ApiException;
import io.github.dglearner.shop.common.LogEvents;
import io.github.dglearner.shop.order.redis.RedisFailureLog;
import io.github.dglearner.shop.order.redis.RedisLock;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Product pages through two cache levels (docs/shop-design.md §17): a small per-instance cache in front of Redis, then
 * MySQL. MySQL is the source of truth; orders are priced from it, never from here.
 * <ul>
 *   <li><b>Penetration</b> (requests for products that do not exist): a Bloom filter in Redis answers "certainly not"
 *       without MySQL; what gets past it and is still missing is cached as "no such product" for a minute.</li>
 *   <li><b>Breakdown</b> (a hot entry expires and every request rebuilds it at once): one rebuild per key at a time,
 *       under a {@link RedisLock}; the others wait for its copy, briefly.</li>
 *   <li><b>Avalanche</b> (many entries expire together, or Redis is gone): random extra time to live; when Redis cannot
 *       be reached, reads go to MySQL through a fixed number of permits, and the local cache absorbs repeats.</li>
 *   <li><b>Consistency</b>: see {@link ProductCacheProperties} and {@link #invalidate}.</li>
 * </ul>
 */
@Component
public class ProductCache {

    private static final Logger log = LoggerFactory.getLogger(ProductCache.class);
    /** Where an update announces "product X is now at version N", so every instance drops its local copy. */
    static final String CHANNEL = "shop:product:invalidated";
    private static final String BLOOM = "shop:product:bloom";
    /** Added once the filter holds every SKU: if it is absent the filter is missing (Redis lost it), not empty. */
    private static final String BLOOM_LOADED = "__loaded__";
    private static final long POLL_MILLIS = 20;
    private static final RedisScript<Long> SET_IF_NEWER = RedisScript.of(
            new ClassPathResource("catalog/set-if-newer.lua"), Long.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> BLOOM_CHECK = RedisScript.of(
            "return redis.call('BF.MEXISTS', KEYS[1], ARGV[1], ARGV[2])", List.class);

    /** Test seam: runs during a rebuild, between reading MySQL and writing Redis. Production has none. */
    @FunctionalInterface
    public interface RebuildHook {
        void afterDatabaseRead(String sku);
    }

    /** A cached entry at a version: the product, none (no such product), or stale (invalidated, rebuild it). */
    record Entry(long version, ProductDetail product, boolean stale) {
    }

    private final StringRedisTemplate redis;
    private final ProductRepository products;
    private final JsonMapper json;
    private final ProductCacheProperties properties;
    private final RedisLock lock;
    private final RedisFailureLog redisFailures;
    private final MeterRegistry registry;
    private final RebuildHook hook;
    private final Semaphore databasePermits;
    private final Cache<String, Entry> local;
    /** The newest version announced per SKU; the local cache never keeps anything older. */
    private final Map<String, Long> announced = new ConcurrentHashMap<>();
    private final ScheduledExecutorService laterDeletes = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("product-cache-second-delete").daemon().factory());
    private final AtomicBoolean bloomLoading = new AtomicBoolean();

    public ProductCache(StringRedisTemplate redis, ProductRepository products, JsonMapper json,
            ProductCacheProperties properties, RedisLock lock, RedisFailureLog redisFailures, MeterRegistry registry,
            ObjectProvider<RebuildHook> hook) {
        this.redis = redis;
        this.products = products;
        this.json = json;
        this.properties = properties;
        this.lock = lock;
        this.redisFailures = redisFailures;
        this.registry = registry;
        this.hook = hook.getIfAvailable(() -> sku -> {
        });
        this.databasePermits = new Semaphore(properties.databasePermits());
        this.local = Caffeine.newBuilder().expireAfterWrite(properties.localTtl())
                .maximumSize(properties.localMaxSize()).build();
    }

    public Optional<ProductDetail> get(String sku) {
        if (!properties.enabled()) {
            registry.counter("shop.product.database.reads", "reason", "cache_off").increment();
            served("database");
            return products.findDetail(sku);
        }
        if (properties.local()) {
            Entry cached = local.getIfPresent(sku);
            if (cached != null) {
                served("local");
                return Optional.ofNullable(cached.product());
            }
        }
        Entry entry;
        try {
            entry = decode(redis.opsForValue().get(key(sku)));
        } catch (DataAccessException e) {
            redisFailures.failed("product_get", e);
            return fromDatabase(sku, "redis_down");
        }
        if (entry != null && !entry.stale()) {
            served("redis");
            keepLocally(sku, entry);
            return Optional.ofNullable(entry.product());
        }
        if (entry == null && !mightExist(sku)) {
            served("bloom");
            return Optional.empty();
        }
        return rebuild(sku);
    }

    /**
     * Called after a change to the product committed, with its new version; also retried from the outbox until it
     * succeeds. Throws if Redis cannot be reached. The local copies go first, here and (through the channel) on the
     * other instances, then the Redis entry, according to the strategy:
     * <ul>
     *   <li>DELETE: a rebuild that read the old row before the update committed can still write it back after this
     *       delete, and that old copy then stays until it expires;</li>
     *   <li>DOUBLE_DELETE: deletes again a moment later, which removes such a copy if the rebuild finished by then;</li>
     *   <li>VERSIONED: writes a marker carrying the new version, and rebuilds only ever write over older versions, so
     *       a rebuild holding the old row is refused however late it comes.</li>
     * </ul>
     */
    public void invalidate(String sku, long version) {
        forgetLocally(sku, version);
        switch (properties.strategy()) {
            case DELETE -> redis.delete(key(sku));
            case DOUBLE_DELETE -> {
                redis.delete(key(sku));
                laterDeletes.schedule(() -> deleteAgain(sku), properties.doubleDeleteDelay().toMillis(),
                        TimeUnit.MILLISECONDS);
            }
            case VERSIONED -> redis.execute(SET_IF_NEWER, List.of(key(sku)), Long.toString(version), version + "|S",
                    Long.toString(properties.markerTtl().toMillis()));
        }
        redis.convertAndSend(CHANNEL, sku + "|" + version);
    }

    /** An update somewhere announced a new version: drop the local copy. */
    void announced(String message) {
        int bar = message.lastIndexOf('|');
        if (bar > 0) {
            forgetLocally(message.substring(0, bar), Long.parseLong(message.substring(bar + 1)));
        }
    }

    /** Puts every SKU into the Bloom filter (idempotent), then the marker that says it is complete. */
    public void loadBloom() {
        if (!properties.bloom() || !bloomLoading.compareAndSet(false, true)) {
            return;
        }
        try {
            List<String> skus = new ArrayList<>(products.allSkus());
            skus.add(BLOOM_LOADED);
            redis.execute((RedisCallback<Object>) connection -> {
                try {
                    // 0.1% false positives at up to 100 000 SKUs; an existing filter is kept.
                    connection.execute("BF.RESERVE", bytes(BLOOM), bytes("0.001"), bytes("100000"));
                } catch (DataAccessException e) {
                    if (e.getMostSpecificCause().getMessage() == null
                            || !e.getMostSpecificCause().getMessage().contains("exists")) {
                        throw e;
                    }
                }
                List<byte[]> args = new ArrayList<>(List.of(bytes(BLOOM)));
                skus.forEach(s -> args.add(bytes(s)));
                return connection.execute("BF.MADD", args.toArray(byte[][]::new));
            });
            log.atInfo().addKeyValue(LogEvents.ACTION, "product_bloom_loaded").addKeyValue("skus", skus.size() - 1)
                    .log("Product Bloom filter loaded");
        } catch (DataAccessException e) {
            redisFailures.failed("bloom_load", e);
        } finally {
            bloomLoading.set(false);
        }
    }

    /** False only when the Bloom filter is complete and says the SKU is not in it. */
    private boolean mightExist(String sku) {
        if (!properties.bloom()) {
            return true;
        }
        try {
            List<?> answer = redis.execute(BLOOM_CHECK, List.of(BLOOM), sku, BLOOM_LOADED);
            if (((Number) answer.get(1)).longValue() == 0) {
                // The filter is gone (e.g. Redis restarted empty): let requests through and load it again.
                Thread.ofVirtual().start(this::loadBloom);
                return true;
            }
            return ((Number) answer.get(0)).longValue() == 1;
        } catch (DataAccessException e) {
            redisFailures.failed("bloom_check", e);
            return true;
        }
    }

    /** One rebuild per key at a time; the others wait for its copy, then give up and read MySQL themselves. */
    private Optional<ProductDetail> rebuild(String sku) {
        if (!properties.rebuildLock()) {
            return load(sku);
        }
        String lockKey = key(sku) + ":lock";
        Optional<String> token;
        try {
            token = lock.tryAcquire(lockKey, properties.lockLease());
        } catch (DataAccessException e) {
            redisFailures.failed("product_lock", e);
            return fromDatabase(sku, "redis_down");
        }
        if (token.isPresent()) {
            count("shop.product.cache.lock", "acquired");
            try {
                // Another holder may have rebuilt it between our read and our lock.
                Entry again = decode(redis.opsForValue().get(key(sku)));
                if (again != null && !again.stale()) {
                    served("redis");
                    keepLocally(sku, again);
                    return Optional.ofNullable(again.product());
                }
                return load(sku);
            } finally {
                try {
                    lock.release(lockKey, token.get());
                } catch (DataAccessException e) {
                    redisFailures.failed("product_unlock", e); // expires with its lease
                }
            }
        }
        long deadline = System.nanoTime() + properties.lockWait().toNanos();
        while (System.nanoTime() < deadline) {
            sleep(POLL_MILLIS);
            Entry entry;
            try {
                entry = decode(redis.opsForValue().get(key(sku)));
            } catch (DataAccessException e) {
                redisFailures.failed("product_get", e);
                return fromDatabase(sku, "redis_down");
            }
            if (entry != null && !entry.stale()) {
                count("shop.product.cache.lock", "waited");
                served("redis");
                keepLocally(sku, entry);
                return Optional.ofNullable(entry.product());
            }
        }
        count("shop.product.cache.lock", "wait_timeout");
        return fromDatabase(sku, "lock_wait_timeout");
    }

    /** Reads MySQL and writes the entry: over anything older (VERSIONED) or unconditionally (the other strategies). */
    private Optional<ProductDetail> load(String sku) {
        Optional<ProductDetail> product = readDatabase(sku, "rebuild");
        if (!properties.rebuildDelay().isZero()) {
            sleep(properties.rebuildDelay().toMillis());
        }
        hook.afterDatabaseRead(sku);
        Entry entry = new Entry(product.map(ProductDetail::version).orElse(0L), product.orElse(null), false);
        long ttl = product.isPresent()
                ? properties.ttl().toMillis() + ThreadLocalRandom.current().nextLong(properties.ttlJitter().toMillis() + 1)
                : properties.missingTtl().toMillis();
        try {
            if (properties.strategy() == ProductCacheProperties.Strategy.VERSIONED) {
                Long written = redis.execute(SET_IF_NEWER, List.of(key(sku)), Long.toString(entry.version()),
                        encode(entry), Long.toString(ttl));
                count("shop.product.cache.writes", Long.valueOf(1).equals(written) ? "written" : "refused_older");
            } else {
                redis.opsForValue().set(key(sku), encode(entry), Duration.ofMillis(ttl));
                count("shop.product.cache.writes", "written");
            }
        } catch (DataAccessException e) {
            redisFailures.failed("product_set", e);
        }
        keepLocally(sku, entry);
        return product;
    }

    /** MySQL directly, when Redis cannot help: at most {@code database-permits} at once, the rest get a 503. */
    private Optional<ProductDetail> fromDatabase(String sku, String reason) {
        Optional<ProductDetail> product = readDatabase(sku, reason);
        keepLocally(sku, new Entry(product.map(ProductDetail::version).orElse(0L), product.orElse(null), false));
        return product;
    }

    private Optional<ProductDetail> readDatabase(String sku, String reason) {
        boolean permitted;
        try {
            permitted = databasePermits.tryAcquire(properties.lockWait().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            permitted = false;
        }
        if (!permitted) {
            registry.counter("shop.product.database.reads", "reason", "refused").increment();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PRODUCT_UNAVAILABLE",
                    "Product details are unavailable right now; try again shortly").with("sku", sku);
        }
        try {
            registry.counter("shop.product.database.reads", "reason", reason).increment();
            served("database");
            return products.findDetail(sku);
        } finally {
            databasePermits.release();
        }
    }

    /** Keeps the entry locally unless a newer version was announced, checked again after the put (it may race). */
    private void keepLocally(String sku, Entry entry) {
        if (!properties.local() || entry.version() < announced.getOrDefault(sku, 0L)) {
            return;
        }
        local.put(sku, entry);
        if (entry.version() < announced.getOrDefault(sku, 0L)) {
            local.invalidate(sku);
        }
    }

    private void forgetLocally(String sku, long version) {
        announced.merge(sku, version, Math::max);
        local.invalidate(sku);
    }

    private void deleteAgain(String sku) {
        try {
            redis.delete(key(sku));
        } catch (DataAccessException e) {
            redisFailures.failed("product_second_delete", e);
        }
    }

    private String encode(Entry entry) {
        return entry.product() == null ? entry.version() + "|M"
                : entry.version() + "|P|" + json.writeValueAsString(entry.product());
    }

    private Entry decode(String value) {
        if (value == null) {
            return null;
        }
        int bar = value.indexOf('|');
        long version = Long.parseLong(value.substring(0, bar));
        return switch (value.charAt(bar + 1)) {
            case 'P' -> new Entry(version, json.readValue(value.substring(bar + 3), ProductDetail.class), false);
            case 'M' -> new Entry(version, null, false);
            default -> new Entry(version, null, true);
        };
    }

    private void served(String by) {
        registry.counter("shop.product.reads", "served_by", by).increment();
    }

    private void count(String name, String result) {
        registry.counter(name, "result", result).increment();
    }

    static String key(String sku) {
        return "shop:product:{" + sku + "}";
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
