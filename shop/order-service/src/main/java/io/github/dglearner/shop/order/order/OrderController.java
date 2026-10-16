package io.github.dglearner.shop.order.order;

import io.github.dglearner.shop.common.ApiException;
import io.github.dglearner.shop.common.ShopHeaders;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Status codes are listed in docs/shop-design.md §5.1. */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Order> create(
            @RequestHeader(ShopHeaders.IDEMPOTENCY_KEY) @Size(min = 1, max = 64) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        OrderService.Result result = service.create(idempotencyKey, request);
        Order order = result.order();
        if (result.replayed()) {
            return ResponseEntity.ok().header(ShopHeaders.IDEMPOTENT_REPLAYED, "true").body(order);
        }
        if (order.status() == OrderStatus.CANCELLED && order.failureReason().dependencyFailure()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, order.failureReason().name(),
                    "The order was cancelled because a dependency failed; place it again with a new Idempotency-Key")
                    .with("order_id", order.orderId()).with("status", order.status())
                    .with("failure_reason", order.failureReason());
        }
        URI location = URI.create("/api/v1/orders/" + order.orderId());
        if (order.status() == OrderStatus.PAYMENT_UNKNOWN) {
            return ResponseEntity.accepted().location(location).body(order);
        }
        return ResponseEntity.created(location).body(order);
    }

    @GetMapping("/{orderId}")
    public Order get(@PathVariable String orderId) {
        return service.find(orderId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND",
                "No order " + orderId).with("order_id", orderId));
    }
}
