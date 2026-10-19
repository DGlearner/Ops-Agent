package io.github.dglearner.shop.order.catalog;

import io.github.dglearner.shop.common.ApiException;
import io.github.dglearner.shop.common.LogEvents;
import java.time.Duration;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Changes products: MySQL first, then the cache, never the other way round (docs/shop-design.md §17.3). */
@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);
    /** The change's own thread invalidates right after the commit; the retry job only steps in after this. */
    static final Duration LEASE = Duration.ofSeconds(10);

    private final ProductRepository products;
    private final ProductCacheInvalidations invalidations;
    private final TransactionTemplate tx;

    public ProductService(ProductRepository products, ProductCacheInvalidations invalidations,
            TransactionTemplate tx) {
        this.products = products;
        this.invalidations = invalidations;
        this.tx = tx;
    }

    /**
     * Updates the product and queues its cache invalidation in the same transaction, then invalidates. Updating the
     * cache instead of invalidating it would race: two updates can reach Redis in the opposite order to MySQL.
     */
    public ProductDetail update(String sku, ProductChanges changes) {
        if (changes.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOTHING_TO_CHANGE", "No field to change").with("sku", sku);
        }
        OptionalLong version = tx.execute(status -> {
            OptionalLong updated = products.update(sku, changes);
            updated.ifPresent(v -> products.queueInvalidation(sku, v, LEASE));
            return updated;
        });
        if (version == null || version.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "No product " + sku).with("sku", sku);
        }
        log.atInfo().addKeyValue(LogEvents.ACTION, "product_updated").addKeyValue("sku", sku)
                .addKeyValue("version", version.getAsLong()).log("Product updated");
        invalidations.invalidate(sku, version.getAsLong());
        return products.findDetail(sku).orElseThrow();
    }
}
