package io.github.dglearner.shop.lab;

import java.time.Instant;

/**
 * One thing done to the shop, as ground truth: a fault from injection to clearing, or a flash sale. Ops-Agent never
 * sees these; they are what its output is scored against.
 *
 * @param kind              {@code fault} or {@code flash_sale}
 * @param expectedIncidents how many incidents Ops-Agent should open for it (0 for a flash sale: selling out is normal)
 * @param endedAt           null while it is still going on
 * @param actionsOk         whether every inject and clear action succeeded; if not, the fault may not have happened
 */
public record Episode(String id, String kind, String name, String target, String description, String expected,
        int expectedIncidents, Instant startedAt, Instant endedAt, boolean actionsOk) {

    Episode ended(Instant at, boolean ok) {
        return new Episode(id, kind, name, target, description, expected, expectedIncidents, startedAt, at,
                actionsOk && ok);
    }
}
