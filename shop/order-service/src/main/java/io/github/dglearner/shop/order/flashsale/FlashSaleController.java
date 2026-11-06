package io.github.dglearner.shop.order.flashsale;

import io.github.dglearner.shop.common.ApiException;
import io.github.dglearner.shop.common.ShopHeaders;
import io.github.dglearner.shop.order.order.CreateOrderRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Buying in a flash sale (docs/shop-design.md §16.2). 202 with a QUEUED order when the sale accepts the buyer, 200
 * with the same order when that buyer asks again (no Idempotency-Key needed: one order per buyer), 409 SOLD_OUT, and
 * 503 when Redis cannot be reached: the sale never falls back to the database, which it exists to protect.
 */
@RestController
@RequestMapping("/api/v1/flash-sales/{saleId}")
public class FlashSaleController {

    /** {@code payment_token} defaults to {@code tok_ok}, as for regular orders. */
    record BuyRequest(@NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_.-]+") String userId,
            @Size(max = 32) @Pattern(regexp = "tok_[a-z_]+") String paymentToken) {
    }

    private final FlashSaleService service;

    public FlashSaleController(FlashSaleService service) {
        this.service = service;
    }

    @PostMapping("/orders")
    public ResponseEntity<FlashSaleOrderView> buy(@PathVariable String saleId, @Valid @RequestBody BuyRequest request) {
        String token = request.paymentToken() == null ? CreateOrderRequest.DEFAULT_PAYMENT_TOKEN
                : request.paymentToken();
        return switch (service.buy(saleId, request.userId(), token)) {
            case FlashSaleService.Purchase.Accepted accepted -> ResponseEntity.accepted()
                    .location(URI.create("/api/v1/flash-sales/" + saleId + "/orders/" + accepted.order().orderId()))
                    .body(accepted.order());
            case FlashSaleService.Purchase.Repeated repeated ->
                    ResponseEntity.ok().header(ShopHeaders.IDEMPOTENT_REPLAYED, "true").body(repeated.order());
            case FlashSaleService.Purchase.SoldOut soldOut ->
                    throw new ApiException(HttpStatus.CONFLICT, "SOLD_OUT", "The flash sale is sold out")
                            .with("sale_id", saleId);
        };
    }

    @GetMapping("/orders/{orderId}")
    public FlashSaleOrderView order(@PathVariable String saleId, @PathVariable String orderId) {
        return service.order(saleId, orderId);
    }

    @GetMapping
    public Map<String, Object> sale(@PathVariable String saleId) {
        return service.status(saleId);
    }
}
