package io.github.dglearner.shop.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void createsUniqueVersion7UuidsThatSortByCreationTime() throws InterruptedException {
        String first = Ids.newId();
        Thread.sleep(2);
        String second = Ids.newId();

        UUID uuid = UUID.fromString(first);
        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
        assertThat(second).isGreaterThan(first);

        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(Ids.newId());
        }
        assertThat(ids).hasSize(10_000);
    }
}
