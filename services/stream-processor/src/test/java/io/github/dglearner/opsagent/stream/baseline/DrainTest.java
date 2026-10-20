package io.github.dglearner.opsagent.stream.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DrainTest {

    private final AtomicInteger next = new AtomicInteger();
    private final Drain drain = new Drain(0.5, 100, tokens -> "t" + next.incrementAndGet());

    @Test
    void evidentValuesAreMaskedBeforeAnythingIsLearned() {
        assertThat(Drain.tokens("Order 4f1c2b3a-0000-4000-8000-0000000000ab charged 12.50 in 230ms from 10.0.0.7:5432"))
                .containsExactly("Order", "<*>", "charged", "<*>", "in", "<*>", "from", "<*>");
        assertThat(Drain.tokens("Charge outcome unknown: HTTP 503 SERVICE_UNAVAILABLE"))
                .containsExactly("Charge", "outcome", "unknown:", "HTTP", "<*>", "SERVICE_UNAVAILABLE");
        assertThat(Drain.tokens("trace deadbeef12 failed")).containsExactly("trace", "<*>", "failed");
    }

    @Test
    void messagesThatDifferInAFewWordsShareATemplateThatKeepsItsFirstId() {
        Drain.Template first = drain.add("Charge declined for user alice at checkout");
        Drain.Template second = drain.add("Charge declined for user bob at checkout");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.text()).isEqualTo("Charge declined for user <*> at checkout");
        assertThat(drain.templates()).hasSize(1);
    }

    @Test
    void theFirstTwoWordsChooseTheBranchSoTheyAreNeverMerged() {
        // Drain's fixed depth: words in the path are taken as they are, unless they hold a digit.
        drain.add("User alice logged in");
        drain.add("User bob logged in");

        assertThat(drain.templates()).hasSize(2);
    }

    @Test
    void differentMessagesGetTheirOwnTemplates() {
        String a = drain.add("Payment 12 succeeded").id();
        String b = drain.add("Failed to obtain JDBC Connection for order 9").id();
        String c = drain.add("Database unavailable: Communications link failure").id();
        String again = drain.add("Payment 13 succeeded").id();

        assertThat(List.of(a, b, c)).doesNotHaveDuplicates();
        assertThat(again).isEqualTo(a);
    }

    @Test
    void aRestoredModelMatchesAsBeforeAndNamesNewTemplatesApart() {
        Drain.Template known = drain.add("Reserved 1 of SKU-1001 for order 77");
        Drain restored = Drain.restore(drain.templates(), 0.5, 100, tokens -> "r" + next.incrementAndGet());

        assertThat(restored.add("Reserved 2 of SKU-1002 for order 78").id()).isEqualTo(known.id());
        assertThat(restored.add("Stock released for order 78").id()).startsWith("r");
    }

    @Test
    void aFullModelCountsNothingNew() {
        Drain small = new Drain(0.5, 1, tokens -> "only");
        small.add("first kind of line");

        assertThat(small.add("something else entirely, with more words")).isNull();
        assertThat(small.add("first kind of line").id()).isEqualTo("only");
    }
}
