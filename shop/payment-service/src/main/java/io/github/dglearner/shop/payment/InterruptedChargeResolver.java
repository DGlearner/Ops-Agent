package io.github.dglearner.shop.payment;

import io.github.dglearner.shop.common.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Finds charges stuck in PROCESSING, e.g. after a crash between calling the provider and saving its answer. */
@Component
public class InterruptedChargeResolver {

    private static final Logger log = LoggerFactory.getLogger(InterruptedChargeResolver.class);

    private final PaymentRepository payments;
    private final PaymentService service;
    private final PaymentProperties properties;

    public InterruptedChargeResolver(PaymentRepository payments, PaymentService service,
            PaymentProperties properties) {
        this.payments = payments;
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${shop.payment.resolve-interval:5s}", initialDelayString = "${shop.payment.resolve-interval:5s}")
    public void resolve() {
        try {
            payments.findStaleProcessing(properties.staleAfter(), 50).forEach(service::resolveInterrupted);
        } catch (RuntimeException e) {
            log.atWarn().addKeyValue(LogEvents.ACTION, "recovery_step_failed")
                    .addKeyValue("step", "interrupted charges")
                    .addKeyValue("error_type", e.getClass().getSimpleName())
                    .log("Resolving interrupted charges failed: {}", e.getMessage());
        }
    }
}
