package io.github.dglearner.shop.common;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Id generation for orders and payments. */
public final class Ids {

    /**
     * A version 7 UUID (RFC 9562) as a string: 48-bit Unix milliseconds followed by random bits. Ids created later
     * sort later, so inserts into an InnoDB primary key stay sequential.
     */
    public static String newId() {
        long millis = System.currentTimeMillis();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long msb = (millis << 16) | 0x7000L | (random.nextLong() & 0x0FFFL);
        long lsb = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb).toString();
    }

    private Ids() {
    }
}
