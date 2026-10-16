package io.github.dglearner.shop.payment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Simulated provider latency, picked uniformly between min and max for each charge, and how old a PROCESSING charge
 * must be before it is treated as interrupted.
 */
@ConfigurationProperties("shop.payment")
public record PaymentProperties(Duration gatewayLatencyMin, Duration gatewayLatencyMax, Duration staleAfter) {

    public PaymentProperties {
        staleAfter = staleAfter == null ? Duration.ofSeconds(30) : staleAfter;
        gatewayLatencyMin = gatewayLatencyMin == null ? Duration.ZERO : gatewayLatencyMin;
        gatewayLatencyMax = gatewayLatencyMax == null ? gatewayLatencyMin : gatewayLatencyMax;
        if (gatewayLatencyMax.compareTo(gatewayLatencyMin) < 0) {
            throw new IllegalArgumentException("shop.payment.gateway-latency-max is below the minimum");
        }
    }
}
