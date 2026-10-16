package io.github.dglearner.shop.inventory.stock;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StockRepository {

    private final JdbcClient jdbc;

    public StockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Stock> findAll() {
        return jdbc.sql("SELECT * FROM stock ORDER BY sku").query(StockRepository::map).list();
    }

    public Optional<Stock> find(String sku) {
        return jdbc.sql("SELECT * FROM stock WHERE sku = :sku").param("sku", sku).query(StockRepository::map).optional();
    }

    /**
     * Moves {@code quantity} from available to reserved if enough is available. The condition is evaluated on the
     * latest committed row under its row lock, so concurrent reservations can never take more than there is.
     */
    public boolean reserve(String sku, int quantity) {
        return jdbc.sql("""
                        UPDATE stock SET available = available - :q, reserved = reserved + :q, updated_at = NOW(3)
                        WHERE sku = :sku AND available >= :q""")
                .param("sku", sku).param("q", quantity).update() == 1;
    }

    /** Moves a confirmed reservation from reserved to sold. */
    public void confirm(String sku, int quantity) {
        requireOneRow(sku, jdbc.sql("""
                        UPDATE stock SET reserved = reserved - :q, sold = sold + :q, updated_at = NOW(3)
                        WHERE sku = :sku""")
                .param("sku", sku).param("q", quantity).update());
    }

    /** Returns a released reservation from reserved to available. */
    public void release(String sku, int quantity) {
        requireOneRow(sku, jdbc.sql("""
                        UPDATE stock SET available = available + :q, reserved = reserved - :q, updated_at = NOW(3)
                        WHERE sku = :sku""")
                .param("sku", sku).param("q", quantity).update());
    }

    public boolean restock(String sku, int quantity) {
        return jdbc.sql("""
                        UPDATE stock SET total = total + :q, available = available + :q, updated_at = NOW(3)
                        WHERE sku = :sku""")
                .param("sku", sku).param("q", quantity).update() == 1;
    }

    private static void requireOneRow(String sku, int rows) {
        if (rows != 1) {
            throw new IllegalStateException("Stock row missing for reserved SKU " + sku);
        }
    }

    private static Stock map(ResultSet rs, int row) throws SQLException {
        return new Stock(rs.getString("sku"), rs.getInt("total"), rs.getInt("available"), rs.getInt("reserved"),
                rs.getInt("sold"), rs.getTimestamp("updated_at").toInstant());
    }
}
