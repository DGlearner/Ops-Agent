package io.github.dglearner.shop.payment;

import io.github.dglearner.shop.common.ApiException;
import io.github.dglearner.shop.common.Ids;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Charges an order at most once (docs/shop-design.md §7.4). */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** The payment after a charge request, and whether this request created it ({@code false} for a replay). */
    public record Outcome(Payment payment, boolean created) {
    }

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final MeterRegistry registry;

    public PaymentService(PaymentRepository payments, PaymentGateway gateway, MeterRegistry registry) {
        this.payments = payments;
        this.gateway = gateway;
        this.registry = registry;
    }

    public Outcome charge(ChargeRequest request) {
        String paymentId = Ids.newId();
        try {
            payments.insertProcessing(paymentId, request);
        } catch (DuplicateKeyException e) {
            return replay(request);
        }
        // The provider call happens outside any transaction: no database connection is held while waiting.
        PaymentGateway.Decision decision = gateway.charge(request.paymentToken(), request.amountCents());
        payments.complete(request.orderId(), decision.status(), decision.declineReason());
        Payment payment = payments.find(request.orderId()).orElseThrow().payment();

        String reason = decision.declineReason() == null ? "none" : decision.declineReason().toLowerCase(Locale.ROOT);
        registry.counter("shop.payments", "status", payment.status().name().toLowerCase(Locale.ROOT), "reason", reason)
                .increment();
        log.atInfo().addKeyValue("order_id", payment.orderId()).addKeyValue("payment_id", payment.paymentId())
                .addKeyValue("amount_cents", payment.amountCents()).addKeyValue("status", payment.status())
                .addKeyValue("decline_reason", payment.declineReason())
                .log(payment.status() == PaymentStatus.SUCCEEDED ? "Payment succeeded" : "Payment declined");
        return new Outcome(payment, true);
    }

    public Optional<Payment> find(String orderId) {
        return payments.find(orderId).map(PaymentRepository.Stored::payment);
    }

    /**
     * Makes sure an order can no longer be charged unless it already was: with no payment yet, writes a VOIDED
     * placeholder that refuses any later charge for the order. Returns the order's payment either way, so the
     * caller learns how the charge ended (docs/shop-design.md §7.4).
     */
    public Outcome voidUnlessCharged(String orderId) {
        try {
            payments.insertVoided(Ids.newId(), orderId);
        } catch (DuplicateKeyException e) {
            return new Outcome(payments.find(orderId).orElseThrow().payment(), false);
        }
        Payment voided = payments.find(orderId).orElseThrow().payment();
        registry.counter("shop.payments", "status", "voided", "reason", "none").increment();
        log.atInfo().addKeyValue("order_id", orderId).addKeyValue("payment_id", voided.paymentId())
                .log("Payment voided before any charge arrived");
        return new Outcome(voided, true);
    }

    /** Settles a charge that was interrupted while PROCESSING, by asking the provider how it ended. */
    public void resolveInterrupted(PaymentRepository.Stored stored) {
        Payment payment = stored.payment();
        PaymentGateway.Decision decision = gateway.lookup(stored.paymentToken());
        if (payments.complete(payment.orderId(), decision.status(), decision.declineReason())) {
            registry.counter("shop.payments", "status", decision.status().name().toLowerCase(Locale.ROOT),
                    "reason", decision.declineReason() == null ? "none"
                            : decision.declineReason().toLowerCase(Locale.ROOT)).increment();
            log.atWarn().addKeyValue("order_id", payment.orderId()).addKeyValue("payment_id", payment.paymentId())
                    .addKeyValue("status", decision.status())
                    .log("Interrupted charge resolved from the provider's record");
        }
    }

    private Outcome replay(ChargeRequest request) {
        PaymentRepository.Stored stored = payments.find(request.orderId())
                .orElseThrow(() -> new IllegalStateException("Duplicate key without a row for " + request.orderId()));
        Payment existing = stored.payment();
        if (existing.status() == PaymentStatus.VOIDED) {
            // Reconciliation gave up on this charge and cancelled the order: it must not go through now.
            registry.counter("shop.payments", "status", "refused_after_void", "reason", "none").increment();
            log.atWarn().addKeyValue("order_id", request.orderId()).log("Late charge refused: payment was voided");
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_VOIDED",
                    "The payment for order " + request.orderId() + " was voided").with("order_id", request.orderId());
        }
        boolean same = existing.userId().equals(request.userId())
                && Objects.equals(existing.amountCents(), request.amountCents())
                && existing.currency().equals(request.currency())
                && Objects.equals(stored.paymentToken(), request.paymentToken());
        if (!same) {
            log.atWarn().addKeyValue("order_id", request.orderId())
                    .log("Charge request differs from the existing payment for this order");
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "PAYMENT_MISMATCH",
                    "Order " + request.orderId() + " already has a payment with different details")
                    .with("order_id", request.orderId());
        }
        if (existing.status() == PaymentStatus.PROCESSING) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_IN_PROGRESS",
                    "The payment for order " + request.orderId() + " is still being processed")
                    .with("order_id", request.orderId()).with("payment_id", existing.paymentId());
        }
        registry.counter("shop.idempotent.replays", "operation", "charge").increment();
        log.atInfo().addKeyValue("order_id", existing.orderId()).addKeyValue("payment_id", existing.paymentId())
                .log("Charge request replayed");
        return new Outcome(existing, false);
    }
}
