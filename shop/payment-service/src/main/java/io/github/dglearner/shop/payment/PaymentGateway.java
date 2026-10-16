package io.github.dglearner.shop.payment;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * Stands in for the external payment provider. Test tokens work like a provider sandbox: {@code tok_ok} succeeds,
 * {@code tok_declined} is declined for insufficient funds, anything else is declined as an invalid token.
 */
@Component
public class PaymentGateway {

    /** What the provider answered: the outcome and, for a decline, its reason. */
    public record Decision(PaymentStatus status, String declineReason) {
    }

    private final PaymentProperties properties;
    private final Timer timer;

    public PaymentGateway(PaymentProperties properties, MeterRegistry registry) {
        this.properties = properties;
        this.timer = Timer.builder("shop.payment.gateway").description("Time spent at the payment provider")
                .register(registry);
    }

    public Decision charge(String paymentToken, long amountCents) {
        return timer.record(() -> {
            sleep(latency());
            return switch (paymentToken) {
                case "tok_ok" -> new Decision(PaymentStatus.SUCCEEDED, null);
                case "tok_declined" -> new Decision(PaymentStatus.DECLINED, "INSUFFICIENT_FUNDS");
                default -> new Decision(PaymentStatus.DECLINED, "INVALID_TOKEN");
            };
        });
    }

    private Duration latency() {
        long min = properties.gatewayLatencyMin().toMillis();
        long max = properties.gatewayLatencyMax().toMillis();
        return Duration.ofMillis(min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1));
    }

    private static void sleep(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the payment provider", e);
        }
    }
}
