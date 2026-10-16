package io.github.dglearner.shop.inventory.reservation;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {

    private final JdbcClient jdbc;

    public ReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Throws {@code DuplicateKeyException} if the order already has a reservation (or tombstone). */
    public void insert(String orderId, ReservationStatus status) {
        jdbc.sql("""
                        INSERT INTO reservation (order_id, status, created_at, updated_at)
                        VALUES (:id, :status, NOW(3), NOW(3))""")
                .param("id", orderId).param("status", status.name()).update();
    }

    public void insertItems(String orderId, List<LineItem> items) {
        for (LineItem item : items) {
            jdbc.sql("INSERT INTO reservation_item (order_id, sku, quantity) VALUES (:id, :sku, :q)")
                    .param("id", orderId).param("sku", item.sku()).param("q", item.quantity()).update();
        }
    }

    /**
     * Reads the status with a locking read, which sees the latest committed row rather than the transaction's
     * snapshot. If there is no row, InnoDB locks the gap, so no concurrent reservation can appear until commit.
     */
    public Optional<ReservationStatus> lockStatus(String orderId) {
        return jdbc.sql("SELECT status FROM reservation WHERE order_id = :id FOR UPDATE")
                .param("id", orderId)
                .query(String.class).optional()
                .map(ReservationStatus::valueOf);
    }

    public void updateStatus(String orderId, ReservationStatus status) {
        jdbc.sql("UPDATE reservation SET status = :status, updated_at = NOW(3) WHERE order_id = :id")
                .param("id", orderId).param("status", status.name()).update();
    }

    public List<LineItem> items(String orderId) {
        return jdbc.sql("SELECT sku, quantity FROM reservation_item WHERE order_id = :id ORDER BY sku")
                .param("id", orderId)
                .query((rs, row) -> new LineItem(rs.getString("sku"), rs.getInt("quantity")))
                .list();
    }

    public Optional<Reservation> find(String orderId) {
        return jdbc.sql("SELECT * FROM reservation WHERE order_id = :id")
                .param("id", orderId)
                .query((rs, row) -> new Reservation(rs.getString("order_id"),
                        ReservationStatus.valueOf(rs.getString("status")), List.of(),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()))
                .optional()
                .map(r -> new Reservation(r.orderId(), r.status(), items(orderId), r.createdAt(), r.updatedAt()));
    }
}
