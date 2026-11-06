package io.github.dglearner.shop.order.flashsale;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * {@code /actuator/flashsales} on the management port (docs/shop-design.md §16.5).
 * <ul>
 *   <li>{@code POST {"sku":"SKU-2001","stock":100,"seconds":600}} opens a sale now. The units must also be available
 *       in inventory (its {@code /actuator/stockprep}): the sale decides who may buy, inventory holds the stock.</li>
 *   <li>{@code GET /actuator/flashsales/{id}} reconciles Redis with MySQL. In Redis, {@code units_left + holders}
 *       must equal the stock at every moment. Holders are buyers whose order is queued, placed, or cancelled with its
 *       unit not given back yet, so {@code queued = holders - live orders - pending returns} is what the queue still
 *       holds; it is exact only while nothing is buying, e.g. after a run has settled.</li>
 * </ul>
 */
@Component
@Endpoint(id = "flashsales")
public class FlashSaleEndpoint {

    private final FlashSaleService service;
    private final FlashSaleRepository sales;
    private final FlashSaleStore store;

    public FlashSaleEndpoint(FlashSaleService service, FlashSaleRepository sales, FlashSaleStore store) {
        this.service = service;
        this.sales = sales;
        this.store = store;
    }

    @WriteOperation
    public WebEndpointResponse<Object> create(String sku, Integer stock, Integer seconds) {
        if (stock == null || stock < 1 || seconds == null || seconds < 1) {
            throw new InvalidEndpointRequestException("stock and seconds must be 1 or more",
                    "stock and seconds must be 1 or more");
        }
        try {
            return new WebEndpointResponse<>(service.create(sku, stock, Duration.ofSeconds(seconds)), 201);
        } catch (DataIntegrityViolationException e) {
            return new WebEndpointResponse<>(Map.of("error", "unknown SKU " + sku), 404);
        }
    }

    @ReadOperation
    public WebEndpointResponse<Object> reconcile(@Selector String saleId) {
        var sale = sales.find(saleId);
        if (sale.isEmpty()) {
            return new WebEndpointResponse<>(Map.of("error", "no flash sale " + saleId), 404);
        }
        int stock = sale.get().stock();
        Map<String, Long> orders = sales.orderCounts(saleId);
        long live = orders.entrySet().stream().filter(e -> !e.getKey().equals("CANCELLED"))
                .mapToLong(Map.Entry::getValue).sum();
        long paid = orders.getOrDefault("PAID", 0L);
        long returnsPending = sales.pendingReturns(saleId);
        List<String> problems = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sale", sale.get());
        report.put("orders", orders);
        report.put("returns_pending", returnsPending);
        try {
            long left = store.unitsLeft(saleId);
            long holders = store.holders(saleId);
            long queued = holders - live - returnsPending;
            report.put("redis", Map.of("units_left", left, "holders", holders));
            report.put("queued", queued);
            if (left < 0) {
                problems.add("the sale is not in Redis");
            } else if (left + holders != stock) {
                problems.add("Redis: units left " + left + " + holders " + holders + " != stock " + stock);
            }
            if (queued < 0) {
                problems.add("Redis has " + holders + " holders but MySQL " + (live + returnsPending)
                        + " live orders and pending returns");
            }
        } catch (DataAccessException e) {
            report.put("redis", Map.of("error", String.valueOf(e.getMostSpecificCause().getMessage())));
            problems.add("Redis cannot be read");
        }
        if (paid > stock) {
            problems.add("oversold: " + paid + " paid orders for " + stock + " units");
        }
        report.put("consistent", problems.isEmpty());
        report.put("problems", problems);
        return new WebEndpointResponse<>(report, 200);
    }
}
