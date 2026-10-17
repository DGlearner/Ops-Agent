package io.github.dglearner.shop.inventory.stock;

import io.github.dglearner.shop.common.LogEvents;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.stereotype.Component;

/**
 * {@code /actuator/stockprep} on the management port prepares a SKU for an experiment (docs/shop-design.md §11.2):
 * {@code POST {"sku":"SKU-2001","available":100}} makes exactly that many units available for sale. What was sold
 * stays sold (the total becomes sold + available), so the ledger stays balanced. Refused with 409 while any unit of
 * the SKU is reserved: an order is still in flight, and stock must not change under it.
 */
@Component
@Endpoint(id = "stockprep")
public class StockPrepEndpoint {

    private static final Logger log = LoggerFactory.getLogger(StockPrepEndpoint.class);

    private final StockRepository stock;

    public StockPrepEndpoint(StockRepository stock) {
        this.stock = stock;
    }

    @WriteOperation
    public WebEndpointResponse<Object> prepare(String sku, Integer available) {
        if (available == null || available < 0) {
            throw new InvalidEndpointRequestException("available must be 0 or more", "available must be 0 or more");
        }
        if (stock.prepare(sku, available)) {
            Stock prepared = stock.find(sku).orElseThrow();
            log.atInfo().addKeyValue(LogEvents.ACTION, "stock_prepared")
                    .addKeyValue("sku", sku).addKeyValue("available", available)
                    .addKeyValue("sold", prepared.sold()).log("Stock prepared for an experiment");
            return new WebEndpointResponse<>(prepared, 200);
        }
        return stock.find(sku)
                .<WebEndpointResponse<Object>>map(current -> new WebEndpointResponse<>(Map.of(
                        "error", "units of " + sku + " are reserved by orders in flight; retry once they settle",
                        "stock", current), 409))
                .orElseGet(() -> new WebEndpointResponse<>(Map.of("error", "unknown SKU " + sku), 404));
    }
}
