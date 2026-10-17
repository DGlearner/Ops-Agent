package io.github.dglearner.shop.lab;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScoringTest {

    private static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");
    private static final Instant NOW = T0.plus(Duration.ofHours(1));

    @Test
    void aFaultIsCorrectWhenAlertedAndSplitIntoTheExpectedIncidents() {
        Episode independent = fault("independent-faults", 2, T0, T0.plusSeconds(60));
        List<Scoring.Alert> alerts = List.of(alert(T0.plusSeconds(5)), alert(T0.plusSeconds(16)));
        List<Scoring.Incident> incidents = List.of(incident(T0.plusSeconds(5)), incident(T0.plusSeconds(16)));

        Scoring.EpisodeScore score = Scoring.score(List.of(independent), alerts, incidents, NOW).episodes().getFirst();

        assertThat(score.verdict()).isEqualTo("correct");
        assertThat(score.firstAlertAfterSeconds()).isEqualTo(5.0);
    }

    @Test
    void tooFewIncidentsIsAWrongSplitAndNoAlertIsAMiss() {
        Episode independent = fault("independent-faults", 2, T0, T0.plusSeconds(60));
        Episode later = fault("payment-slow", 1, T0.plusSeconds(1200), T0.plusSeconds(1260));

        Scoring.Report report = Scoring.score(List.of(independent, later), List.of(alert(T0.plusSeconds(5))),
                List.of(incident(T0.plusSeconds(5))), NOW);

        assertThat(report.episodes()).extracting(Scoring.EpisodeScore::verdict).containsExactly("wrong_split", "missed");
        assertThat(report.summary()).containsEntry("faults", 2).containsEntry("detected", 1L)
                .containsEntry("correct", 0L);
    }

    @Test
    void alertsDuringAFlashSaleOrOutsideEveryFaultAreFalseAlarms() {
        Episode sale = new Episode("s", "flash_sale", "flash-sale", "SKU-2001", "d", "e", 0, T0, T0.plusSeconds(30),
                true);
        Episode fault = fault("mysql-down", 1, T0.plusSeconds(1800), T0.plusSeconds(1830));
        List<Scoring.Alert> alerts = List.of(alert(T0.plusSeconds(10)), alert(T0.plusSeconds(1801)));

        Scoring.Report report = Scoring.score(List.of(sale, fault), alerts, List.of(incident(T0.plusSeconds(1801))),
                NOW);

        assertThat(report.episodes()).extracting(Scoring.EpisodeScore::verdict).containsExactly("false_alarm", "correct");
        assertThat(report.falseAlarms()).extracting(Scoring.Alert::createdAt).containsExactly(T0.plusSeconds(10));
    }

    @Test
    void anEpisodeIsPendingUntilItsWindowHasPassedAndUnscoredIfInjectionFailed() {
        Episode running = fault("payment-errors", 1, NOW.minusSeconds(10), null);
        Episode failed = new Episode("f", "fault", "mysql-down", "mysql", "d", "e", 1, T0, T0.plusSeconds(30), false);

        assertThat(Scoring.score(List.of(running, failed), List.of(), List.of(), NOW).episodes())
                .extracting(Scoring.EpisodeScore::verdict).containsExactly("not_injected", "pending");
    }

    private static Episode fault(String name, int expected, Instant start, Instant end) {
        return new Episode(name + start, "fault", name, "t", "d", "e", expected, start, end, true);
    }

    private static Scoring.Alert alert(Instant at) {
        return new Scoring.Alert("a" + at, "rule", "order-service", "s", at);
    }

    private static Scoring.Incident incident(Instant at) {
        return new Scoring.Incident("i" + at, "t", List.of("x"), 1, at);
    }
}
