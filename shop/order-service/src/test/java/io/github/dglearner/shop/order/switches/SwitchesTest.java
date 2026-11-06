package io.github.dglearner.shop.order.switches;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SwitchesTest {

    static class MovableClock extends Clock {
        Instant now = Instant.parse("2027-01-01T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void aSwitchTurnsItselfOffWhenItsTimeIsUp() {
        MovableClock clock = new MovableClock();
        Switches switches = new Switches(clock);
        switches.set(Switches.PAYMENT_FAST_FAIL, true, Duration.ofMinutes(5));
        assertThat(switches.isOn(Switches.PAYMENT_FAST_FAIL)).isTrue();
        assertThat(switches.isOn(Switches.INVENTORY_FAST_FAIL)).isFalse();

        clock.now = clock.now.plus(Duration.ofMinutes(5));
        assertThat(switches.isOn(Switches.PAYMENT_FAST_FAIL)).isFalse();
        assertThat(switches.all()).allMatch(s -> !s.on());
    }

    @Test
    void unknownSwitchesAndOverlongDurationsAreRefused() {
        Switches switches = new Switches();
        assertThatThrownBy(() -> switches.set("orders.off", true, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> switches.set(Switches.PAYMENT_FAST_FAIL, true, Duration.ofHours(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
