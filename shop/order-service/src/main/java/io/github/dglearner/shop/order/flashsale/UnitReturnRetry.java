package io.github.dglearner.shop.order.flashsale;

import io.github.dglearner.shop.common.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retries unit returns that Redis did not take, alongside the other background recovery (§7.7, §16.4). */
@Component
@ConditionalOnBooleanProperty(name = "shop.order.recovery.enabled", matchIfMissing = true)
public class UnitReturnRetry {

    private static final Logger log = LoggerFactory.getLogger(UnitReturnRetry.class);
    private static final int BATCH = 50;

    private final UnitReturnRepository returns;
    private final UnitReturns unitReturns;

    public UnitReturnRetry(UnitReturnRepository returns, UnitReturns unitReturns) {
        this.returns = returns;
        this.unitReturns = unitReturns;
    }

    @Scheduled(fixedDelayString = "${shop.order.recovery.interval}", initialDelayString = "${shop.order.recovery.interval}")
    public void run() {
        try {
            for (UnitReturnRepository.UnitReturn unit : returns.findDue(BATCH)) {
                if (returns.claim(unit.orderId())) {
                    unitReturns.execute(unit);
                }
            }
        } catch (DataAccessException e) {
            log.atWarn().addKeyValue(LogEvents.ACTION, "recovery_step_failed").addKeyValue("step", "flash sale returns")
                    .addKeyValue("error_type", e.getClass().getSimpleName())
                    .log("Recovery step 'flash sale returns' failed: {}", e.getMessage());
        }
    }
}
