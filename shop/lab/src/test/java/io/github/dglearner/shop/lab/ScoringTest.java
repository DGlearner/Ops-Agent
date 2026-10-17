package io.github.dglearner.shop.lab;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

class ScoringTest {

    private static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");
    private static final Instant NOW = T0.plus(Duration.ofHours(1));
    private static final BiFunction<Instant, Instant, Integer> NO_FAILURES = (from, to) -> 0;

    @Test
    void aFaultIsCorrectWhenAlertedAndSplitIntoTheExpectedIncidents() {
        Episode independent = fault("independent-faults", 2, T0, T0.plusSeconds(60));
        List<Scoring.Alert> alerts = List.of(alert(T0.plusSeconds(5)), alert(T0.plusSeconds(16)));
        List<Scoring.Incident> incidents = List.of(incident(T0.plusSeconds(5), T0.plusSeconds(5)),
                incident(T0.plusSeconds(16), T0.plusSeconds(16)));

        Scoring.EpisodeScore score = Scoring.score(List.of(independent), alerts, incidents, NO_FAILURES, NOW)
                .episodes().getFirst();

        assertThat(score.verdict()).isEqualTo("correct");
        assertThat(score.firstAlertAfterSeconds()).isEqualTo(5.0);
    }

    @Test
    void windowsEndAtTheNextEpisodeSoAlertsAreScoredOnce() {
        Episode first = fault("independent-faults", 2, T0, T0.plusSeconds(60));
        Episode second = fault("mysql-down", 1, T0.plusSeconds(270), T0.plusSeconds(300));
        List<Scoring.Alert> alerts = List.of(alert(T0.plusSeconds(5)), alert(T0.plusSeconds(275)));
        List<Scoring.Incident> incidents = List.of(incident(T0.plusSeconds(5), T0.plusSeconds(5)),
                incident(T0.plusSeconds(275), T0.plusSeconds(275)));

        Scoring.Report report = Scoring.score(List.of(first, second), alerts, incidents, NO_FAILURES, NOW);

        assertThat(report.episodes()).extracting(Scoring.EpisodeScore::verdict).containsExactly("wrong_split",
                "correct");
        assertThat(report.episodes().getFirst().windowTo()).isEqualTo(T0.plusSeconds(240));
    }

    @Test
    void anIncidentOpenedBeforeTheEpisodeCountsIfTheEpisodesAlertsJoinedIt() {
        Episode fault = fault("inventory-errors", 1, T0.plusSeconds(600), T0.plusSeconds(660));
        List<Scoring.Alert> alerts = List.of(alert(T0.plusSeconds(610)));
        List<Scoring.Incident> incidents = List.of(incident(T0, T0.plusSeconds(610)));

        assertThat(Scoring.score(List.of(fault), alerts, incidents, NO_FAILURES, NOW).episodes().getFirst()
                .verdict()).isEqualTo("correct");
    }

    @Test
    void noAlertIsAMissAndAFailedInjectionIsNotScored() {
        Episode missed = fault("payment-slow", 1, T0, T0.plusSeconds(60));
        Episode failed = new Episode("f", "fault", "mysql-down", "mysql", "d", "e", 1, T0.plusSeconds(1200),
                T0.plusSeconds(1230), false);
        Episode running = fault("payment-errors", 1, NOW.minusSeconds(10), null);

        assertThat(Scoring.score(List.of(missed, failed, running), List.of(), List.of(), NO_FAILURES, NOW).episodes())
                .extracting(Scoring.EpisodeScore::verdict).containsExactly("missed", "not_injected", "pending");
    }

    @Test
    void aFlashSaleThatAlertedIsAnOverloadIfCustomersSawFailuresElseAFalseAlarm() {
        Episode sale = new Episode("s", "flash_sale", "flash-sale", "SKU-2001", "d", "e", 0, T0, T0.plusSeconds(30),
                true);
        List<Scoring.Alert> alerts = List.of(alert(T0.plusSeconds(3)));

        assertThat(Scoring.score(List.of(sale), alerts, List.of(), (f, t) -> 17, NOW).episodes().getFirst().verdict())
                .isEqualTo("overload");
        assertThat(Scoring.score(List.of(sale), alerts, List.of(), NO_FAILURES, NOW).episodes().getFirst().verdict())
                .isEqualTo("false_alarm");
        assertThat(Scoring.score(List.of(sale), List.of(), List.of(), (f, t) -> 17, NOW).episodes().getFirst()
                .verdict()).isEqualTo("missed_overload");
    }

    @Test
    void anAlertOutsideEveryFaultIsAFalseAlarmOnlyIfNoCustomerOrderFailedAroundIt() {
        Instant quiet = T0.plusSeconds(100);
        Instant trouble = T0.plusSeconds(2000);
        BiFunction<Instant, Instant, Integer> failures = (from, to) ->
                !trouble.isBefore(from) && !trouble.isAfter(to) ? 3 : 0;

        Scoring.Report report = Scoring.score(List.of(), List.of(alert(quiet), alert(trouble)), List.of(), failures,
                NOW);

        assertThat(report.falseAlarms()).extracting(Scoring.Alert::createdAt).containsExactly(quiet);
        assertThat(report.troubleAlerts()).extracting(Scoring.Alert::createdAt).containsExactly(trouble);
    }

    private static Episode fault(String name, int expected, Instant start, Instant end) {
        return new Episode(name + start, "fault", name, "t", "d", "e", expected, start, end, true);
    }

    private static Scoring.Alert alert(Instant at) {
        return new Scoring.Alert("a" + at, "rule", "order-service", "s", at);
    }

    private static Scoring.Incident incident(Instant openedAt, Instant lastAlertAt) {
        return new Scoring.Incident("i" + openedAt, "t", List.of("x"), 1, openedAt, lastAlertAt);
    }
}
