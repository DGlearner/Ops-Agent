package io.github.dglearner.shop.order.flashsale;

import java.time.Instant;

/** A flash sale: {@code stock} units of {@code sku}, one per buyer, sold from {@code startsAt} until {@code endsAt}. */
public record FlashSale(String saleId, String sku, int stock, Instant startsAt, Instant endsAt) {

    boolean openAt(Instant now) {
        return !now.isBefore(startsAt) && now.isBefore(endsAt);
    }
}
