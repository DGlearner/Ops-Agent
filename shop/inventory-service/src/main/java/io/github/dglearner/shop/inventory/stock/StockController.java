package io.github.dglearner.shop.inventory.stock;

import io.github.dglearner.shop.common.ApiException;
import jakarta.validation.Valid;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stock")
public class StockController {

    private static final Logger log = LoggerFactory.getLogger(StockController.class);

    private final StockRepository stock;

    public StockController(StockRepository stock) {
        this.stock = stock;
    }

    @GetMapping
    public List<Stock> list() {
        return stock.findAll();
    }

    @GetMapping("/{sku}")
    public Stock get(@PathVariable String sku) {
        return stock.find(sku).orElseThrow(() -> unknownSku(sku));
    }

    @PostMapping("/{sku}/restock")
    public Stock restock(@PathVariable String sku, @Valid @RequestBody RestockRequest request) {
        if (!stock.restock(sku, request.quantity())) {
            throw unknownSku(sku);
        }
        log.atInfo().addKeyValue("sku", sku).addKeyValue("quantity", request.quantity()).log("Stock restocked");
        return get(sku);
    }

    private static ApiException unknownSku(String sku) {
        return new ApiException(HttpStatus.NOT_FOUND, "UNKNOWN_SKU", "No stock record for " + sku).with("sku", sku);
    }
}
