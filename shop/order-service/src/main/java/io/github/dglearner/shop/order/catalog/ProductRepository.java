package io.github.dglearner.shop.order.catalog;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProductRepository {

    private final JdbcClient jdbc;

    public ProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Product> findActive() {
        return jdbc.sql("SELECT sku, name, price_cents FROM product WHERE active ORDER BY sku")
                .query((rs, row) -> new Product(rs.getString("sku"), rs.getString("name"), rs.getLong("price_cents")))
                .list();
    }

    /** Active products among {@code skus}, by SKU; unknown or inactive SKUs are simply absent. */
    public Map<String, Product> findActive(Collection<String> skus) {
        return jdbc.sql("SELECT sku, name, price_cents FROM product WHERE active AND sku IN (:skus)")
                .param("skus", skus)
                .query((rs, row) -> new Product(rs.getString("sku"), rs.getString("name"), rs.getLong("price_cents")))
                .list().stream()
                .collect(Collectors.toMap(Product::sku, Function.identity()));
    }

    /** The product page, active or not; straight from MySQL (the cache calls this to rebuild). */
    public Optional<ProductDetail> findDetail(String sku) {
        return jdbc.sql("SELECT * FROM product WHERE sku = :sku")
                .param("sku", sku)
                .query((rs, row) -> new ProductDetail(rs.getString("sku"), rs.getString("name"),
                        rs.getString("description"), rs.getLong("price_cents"), rs.getBoolean("active"),
                        rs.getLong("version"), rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    public List<String> allSkus() {
        return jdbc.sql("SELECT sku FROM product").query(String.class).list();
    }

    /** Applies the changes and bumps the version; the new version, or empty if there is no such product. */
    OptionalLong update(String sku, ProductChanges changes) {
        int updated = jdbc.sql("""
                        UPDATE product SET name = COALESCE(:name, name), description = COALESCE(:description, description),
                               price_cents = COALESCE(:price, price_cents), active = COALESCE(:active, active),
                               version = version + 1, updated_at = NOW(3)
                        WHERE sku = :sku""")
                .param("sku", sku).param("name", changes.name()).param("description", changes.description())
                .param("price", changes.priceCents()).param("active", changes.active())
                .update();
        if (updated == 0) {
            return OptionalLong.empty();
        }
        // The row is locked by the update until the transaction ends, so this is the version it wrote.
        return OptionalLong.of(jdbc.sql("SELECT version FROM product WHERE sku = :sku").param("sku", sku)
                .query(Long.class).single());
    }

    /** Must run in the transaction that changed the product; keeps the newest version per SKU. */
    void queueInvalidation(String sku, long version, Duration lease) {
        jdbc.sql("""
                        INSERT INTO product_cache_invalidation (sku, version, attempts, next_attempt_at)
                        VALUES (:sku, :version, 0, NOW(3) + INTERVAL :lease MICROSECOND) AS queued
                        ON DUPLICATE KEY UPDATE
                            version = GREATEST(product_cache_invalidation.version, queued.version),
                            attempts = 0, next_attempt_at = queued.next_attempt_at""")
                .param("sku", sku).param("version", version).param("lease", lease.toNanos() / 1000)
                .update();
    }

    /** Invalidations whose next attempt is due: SKU to version. */
    Map<String, Long> dueInvalidations(int limit) {
        Map<String, Long> due = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT sku, version FROM product_cache_invalidation WHERE next_attempt_at <= NOW(3)
                        ORDER BY next_attempt_at LIMIT :limit""")
                .param("limit", limit)
                .query(rs -> {
                    due.put(rs.getString("sku"), rs.getLong("version"));
                });
        return due;
    }

    /** Takes a due invalidation for one attempt; false if another worker took it first. */
    boolean claimInvalidation(String sku, Duration lease) {
        return jdbc.sql("""
                        UPDATE product_cache_invalidation SET next_attempt_at = NOW(3) + INTERVAL :lease MICROSECOND
                        WHERE sku = :sku AND next_attempt_at <= NOW(3)""")
                .param("sku", sku).param("lease", lease.toNanos() / 1000).update() == 1;
    }

    /** Done up to {@code version}; a newer change queued meanwhile keeps its row. */
    void invalidationDone(String sku, long version) {
        jdbc.sql("DELETE FROM product_cache_invalidation WHERE sku = :sku AND version <= :version")
                .param("sku", sku).param("version", version).update();
    }

    void scheduleInvalidationRetry(String sku) {
        // Exponential backoff from 2 s up to 30 s; MySQL reads attempts before the increment (left to right).
        jdbc.sql("""
                        UPDATE product_cache_invalidation
                        SET next_attempt_at = NOW(3) + INTERVAL LEAST(2000 << LEAST(attempts, 20), 30000) * 1000 MICROSECOND,
                            attempts = attempts + 1
                        WHERE sku = :sku""")
                .param("sku", sku).update();
    }
}
