package io.github.dglearner.shop.order.catalog;

import io.github.dglearner.shop.common.ApiException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The catalogue: the list from MySQL, product pages through the cache (§17), and changes to products. */
@RestController
public class ProductController {

    private final ProductRepository products;
    private final ProductCache cache;
    private final ProductService service;

    public ProductController(ProductRepository products, ProductCache cache, ProductService service) {
        this.products = products;
        this.cache = cache;
        this.service = service;
    }

    @GetMapping("/api/v1/products")
    public List<Product> list() {
        return products.findActive();
    }

    @GetMapping("/api/v1/products/{sku}")
    public ProductDetail detail(@PathVariable String sku) {
        return cache.get(sku).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND",
                "No product " + sku).with("sku", sku));
    }

    /** No authentication, like the rest of the shop, whose ports are reachable from this machine only (§3). */
    @PatchMapping("/api/v1/products/{sku}")
    public ProductDetail update(@PathVariable String sku, @Valid @RequestBody ProductChanges changes) {
        return service.update(sku, changes);
    }
}
