package io.github.dglearner.shop.inventory.reservation;

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
@RequestMapping("/api/v1/reservations")
public class ReservationController {

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    /** 201 when this request reserved the stock, 200 when it repeats an earlier identical request. */
    @PostMapping
    public ResponseEntity<Reservation> reserve(@Valid @RequestBody ReserveRequest request) {
        ReservationService.Outcome outcome = service.reserve(request.orderId(), request.items());
        if (outcome.changed()) {
            return ResponseEntity.created(URI.create("/api/v1/reservations/" + request.orderId()))
                    .body(outcome.reservation());
        }
        return replayed(outcome);
    }

    @GetMapping("/{orderId}")
    public Reservation get(@PathVariable String orderId) {
        return service.find(orderId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "RESERVATION_NOT_FOUND", "No reservation for order " + orderId).with("order_id", orderId));
    }

    @PostMapping("/{orderId}/confirm")
    public ResponseEntity<Reservation> confirm(@PathVariable String orderId) {
        return respond(service.confirm(orderId));
    }

    @PostMapping("/{orderId}/release")
    public ResponseEntity<Reservation> release(@PathVariable String orderId) {
        return respond(service.release(orderId));
    }

    private static ResponseEntity<Reservation> respond(ReservationService.Outcome outcome) {
        return outcome.changed() ? ResponseEntity.ok(outcome.reservation()) : replayed(outcome);
    }

    private static ResponseEntity<Reservation> replayed(ReservationService.Outcome outcome) {
        return ResponseEntity.ok().header(ShopHeaders.IDEMPOTENT_REPLAYED, "true").body(outcome.reservation());
    }
}
