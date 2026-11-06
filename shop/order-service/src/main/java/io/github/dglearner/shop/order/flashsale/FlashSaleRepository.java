package io.github.dglearner.shop.order.flashsale;

import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FlashSaleRepository {

    private final JdbcClient jdbc;

    public FlashSaleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(FlashSale sale) {
        jdbc.sql("""
                        INSERT INTO flash_sale (sale_id, sku, stock, starts_at, ends_at, created_at)
                        VALUES (:id, :sku, :stock, :starts, :ends, NOW(3))""")
                .param("id", sale.saleId()).param("sku", sale.sku()).param("stock", sale.stock())
                .param("starts", Timestamp.from(sale.startsAt())).param("ends", Timestamp.from(sale.endsAt()))
                .update();
    }

    public Optional<FlashSale> find(String saleId) {
        return jdbc.sql("SELECT * FROM flash_sale WHERE sale_id = :id")
                .param("id", saleId)
                .query((rs, row) -> new FlashSale(rs.getString("sale_id"), rs.getString("sku"), rs.getInt("stock"),
                        rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant()))
                .optional();
    }

    /** The sale's orders by status. */
    public Map<String, Long> orderCounts(String saleId) {
        Map<String, Long> counts = new TreeMap<>();
        jdbc.sql("SELECT status, COUNT(*) AS n FROM orders WHERE flash_sale_id = :id GROUP BY status")
                .param("id", saleId)
                .query(rs -> {
                    counts.put(rs.getString("status"), rs.getLong("n"));
                });
        return counts;
    }

    /** Cancelled orders of the sale whose unit has not been given back to Redis yet. */
    public long pendingReturns(String saleId) {
        return jdbc.sql("SELECT COUNT(*) FROM flash_sale_return WHERE sale_id = :id")
                .param("id", saleId).query(Long.class).single();
    }
}
