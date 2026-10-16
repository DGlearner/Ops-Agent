package io.github.dglearner.shop.payment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Simulated provider latency, picked uniformly between min and max for each charge. */
@ConfigurationProperties("shop.payment")
public record PaymentProperties(Duration gatewayLatencyMin, Duration gatewayLatencyMax) {

    public PaymentProperties {
        gatewayLatencyMin = gatewayLatencyMin == null ? Duration.ZERO : gatewayLatencyMin;
        gatewayLatencyMax = gatewayLatencyMax == null ? gatewayLatencyMin : gatewayLatencyMax;
        if (gatewayLatencyMax.compareTo(gatewayLatencyMin) < 0) {
            throw new IllegalArgumentException("shop.payment.gateway-latency-max is below the minimum");
        }
    }
}
