package io.github.dglearner.shop.order.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Map;
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
}
