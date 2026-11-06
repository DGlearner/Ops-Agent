package io.github.dglearner.shop.order.catalog;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The product cache (docs/shop-design.md §17). {@code strategy} is how an update invalidates the cached copy; the
 * other two exist so the experiments can compare them:
 * <ul>
 *   <li>DELETE: update MySQL, then delete the entry (cache aside);</li>
 *   <li>DOUBLE_DELETE: and delete it again after {@code double-delete-delay};</li>
 *   <li>VERSIONED: leave a marker with the new version instead of deleting, and only ever write an entry over an
 *       older version (the default).</li>
 * </ul>
 * Entries live {@code ttl} plus a random part of {@code ttl-jitter}, so entries loaded together do not expire together.
 * {@code enabled=false} reads every product page from MySQL, as the baseline the experiments compare against.
 * {@code rebuild-delay} is a fault for experiments (§17.5): a rebuild waits this long between reading MySQL and writing
 * Redis, as if the query had been slow, which widens the window in which an update can race it. Zero in normal use.
 */
@Validated
@ConfigurationProperties("shop.product-cache")
public record ProductCacheProperties(@DefaultValue("true") boolean enabled,
        @NotNull @DefaultValue("VERSIONED") Strategy strategy,
        @NotNull @DefaultValue("30m") Duration ttl, @NotNull @DefaultValue("5m") Duration ttlJitter,
        @NotNull @DefaultValue("1m") Duration missingTtl, @NotNull @DefaultValue("1m") Duration markerTtl,
        @DefaultValue("true") boolean local, @NotNull @DefaultValue("5s") Duration localTtl,
        @Min(1) @DefaultValue("10000") int localMaxSize,
        @DefaultValue("true") boolean rebuildLock, @NotNull @DefaultValue("3s") Duration lockLease,
        @NotNull @DefaultValue("500ms") Duration lockWait, @NotNull @DefaultValue("500ms") Duration doubleDeleteDelay,
        @DefaultValue("true") boolean bloom, @Min(1) @DefaultValue("16") int databasePermits,
        @NotNull @DefaultValue("0ms") Duration rebuildDelay) {

    public enum Strategy {
        DELETE, DOUBLE_DELETE, VERSIONED
    }
}
