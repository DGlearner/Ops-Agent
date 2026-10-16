package io.github.dglearner.shop.payment;

import io.github.dglearner.shop.common.ApiException;
import io.github.dglearner.shop.common.ShopHeaders;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    /** 201 when this request charged (successfully or not), 200 when it repeats an earlier identical request. */
    @PostMapping
    public ResponseEntity<Payment> charge(@Valid @RequestBody ChargeRequest request) {
        PaymentService.Outcome outcome = service.charge(request);
        if (outcome.created()) {
            return ResponseEntity.created(URI.create("/api/v1/payments/" + request.orderId())).body(outcome.payment());
        }
        return ResponseEntity.ok().header(ShopHeaders.IDEMPOTENT_REPLAYED, "true").body(outcome.payment());
    }

    /** Looks up the payment of an order, e.g. to find out how a timed-out charge ended. */
    @GetMapping("/{orderId}")
    public Payment get(@PathVariable String orderId) {
        return service.find(orderId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND",
                "No payment for order " + orderId).with("order_id", orderId));
    }
}
