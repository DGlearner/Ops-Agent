package io.github.dglearner.shop.order.flashsale;

import io.github.dglearner.shop.common.LogEvents;
import io.github.dglearner.shop.order.order.OrderCancelled;
import io.github.dglearner.shop.order.redis.RedisFailureLog;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Gives a cancelled flash sale order's unit back to the sale in Redis (docs/shop-design.md §16.4), so a declined card
 * or a failed reservation does not leave the sale short: another buyer can take the unit. The return is recorded in
 * the cancelling transaction (an outbox row), tried right after the commit, and retried until Redis has it.
 */
@Component
public class UnitReturns {

    private static final Logger log = LoggerFactory.getLogger(UnitReturns.class);

    private final UnitReturnRepository returns;
    private final FlashSaleStore store;
    private final RedisFailureLog redisFailures;
    private final MeterRegistry registry;

    public UnitReturns(UnitReturnRepository returns, FlashSaleStore store, RedisFailureLog redisFailures,
            MeterRegistry registry) {
        this.returns = returns;
        this.store = store;
        this.redisFailures = redisFailures;
        this.registry = registry;
    }

    /** Runs inside the transaction that cancels the order. */
    @EventListener
    public void record(OrderCancelled event) {
        returns.insertIfFlashSale(event.orderId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void returnNow(OrderCancelled event) {
        try {
            returns.find(event.orderId()).ifPresent(this::execute);
        } catch (DataAccessException e) {
            // The row is committed; the retry job finds it once MySQL answers again.
        }
    }

    /** MySQL failures propagate to the caller; the row stays and is retried. */
    void execute(UnitReturnRepository.UnitReturn unit) {
        boolean returned;
        try {
            returned = store.giveBack(unit.saleId(), unit.userId(), unit.orderId());
        } catch (DataAccessException e) {
            redisFailures.failed("give_back", e);
            registry.counter("shop.flash_sale.returns", "result", "retry").increment();
            returns.scheduleRetry(unit.orderId());
            return;
        }
        // Deleted after Redis took the unit: if this delete is lost, the next attempt finds the unit already back.
        returns.delete(unit.orderId());
        registry.counter("shop.flash_sale.returns", "result", returned ? "returned" : "already").increment();
        log.atInfo().addKeyValue(LogEvents.ACTION, "flash_sale_unit_returned")
                .addKeyValue("order_id", unit.orderId()).addKeyValue("flash_sale_id", unit.saleId())
                .log(returned ? "Unit given back to the flash sale" : "Unit was already back in the flash sale");
    }
}
