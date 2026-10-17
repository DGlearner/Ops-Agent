package io.github.dglearner.shop.lab;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Scores what Ops-Agent reported against the ground truth (docs/shop-design.md §15).
 *
 * <p>An episode's window runs from {@link #LEAD} before it started to {@link #TAIL} after it ended, since effects
 * outlast a fault (retries, recovery), but no further than the start of the next episode, so windows do not overlap
 * and every alert is scored once. A fault is detected if an alert falls in its window, and correctly split if the
 * incidents active in it are as many as expected.
 *
 * <p>Whether an alert without an injected fault behind it is a false alarm depends on what the customers saw: the
 * lab's own orders. If some of them failed around the alert, the shop really was in trouble (a flash sale beyond
 * what it can take, say) and the alert is right; if none did, it is a false alarm.
 */
final class Scoring {

    static final Duration LEAD = Duration.ofSeconds(30);
    static final Duration TAIL = Duration.ofMinutes(5);
    /** Customer failures this long before an alert, up to {@link #AFTER} after it, explain it. */
    static final Duration BEFORE = Duration.ofMinutes(2);
    static final Duration AFTER = Duration.ofSeconds(30);

    /** An alert as Ops-Agent's API returned it. */
    record Alert(String alertId, String ruleId, String service, String summary, Instant createdAt) {
    }

    /** An incident as Ops-Agent's API returned it. */
    record Incident(String incidentId, String title, List<String> subjects, int alertCount, Instant openedAt,
            Instant lastAlertAt) {
    }

    record EpisodeScore(Episode episode, String verdict, boolean detected, Double firstAlertAfterSeconds,
            int customerFailures, Instant windowFrom, Instant windowTo, List<Alert> alerts, List<Incident> incidents) {
    }

    /** {@code unexplainedAlerts} are the false alarms; {@code troubleAlerts} had failing customer orders behind them. */
    record Report(List<EpisodeScore> episodes, List<Alert> falseAlarms, List<Alert> troubleAlerts,
            Map<String, Object> summary) {
    }

    static Report score(List<Episode> episodes, List<Alert> alerts, List<Incident> incidents,
            BiFunction<Instant, Instant, Integer> customerFailures, Instant now) {
        List<Episode> ordered = episodes.stream().sorted(Comparator.comparing(Episode::startedAt)).toList();
        List<EpisodeScore> scores = new ArrayList<>();
        List<Instant[]> faultWindows = new ArrayList<>();
        for (int k = 0; k < ordered.size(); k++) {
            Episode episode = ordered.get(k);
            Instant from = episode.startedAt().minus(LEAD);
            Instant to = (episode.endedAt() != null ? episode.endedAt() : now).plus(TAIL);
            if (k + 1 < ordered.size() && ordered.get(k + 1).startedAt().minus(LEAD).isBefore(to)) {
                to = ordered.get(k + 1).startedAt().minus(LEAD);
            }
            Instant windowFrom = from;
            Instant windowTo = to;
            List<Alert> inWindow = alerts.stream().filter(a -> within(a.createdAt(), windowFrom, windowTo))
                    .sorted(Comparator.comparing(Alert::createdAt)).toList();
            List<Incident> active = incidents.stream()
                    .filter(i -> !i.lastAlertAt().isBefore(windowFrom) && !i.openedAt().isAfter(windowTo))
                    .filter(i -> inWindow.stream().anyMatch(a -> within(a.createdAt(), i.openedAt(), i.lastAlertAt())))
                    .toList();
            int failures = customerFailures.apply(episode.startedAt(), episode.endedAt() != null ? episode.endedAt() : now);
            boolean settled = !now.isBefore(windowTo) && episode.endedAt() != null;
            Double delay = inWindow.isEmpty() ? null
                    : Duration.between(episode.startedAt(), inWindow.getFirst().createdAt()).toMillis() / 1000.0;
            scores.add(new EpisodeScore(episode, verdict(episode, inWindow, active, failures, settled),
                    !inWindow.isEmpty(), delay, failures, windowFrom, windowTo, inWindow, active));
            if (episode.kind().equals("fault")) {
                faultWindows.add(new Instant[] {windowFrom, windowTo});
            }
        }

        List<Alert> falseAlarms = new ArrayList<>();
        List<Alert> trouble = new ArrayList<>();
        for (Alert alert : alerts) {
            if (faultWindows.stream().anyMatch(w -> within(alert.createdAt(), w[0], w[1]))) {
                continue;
            }
            int failures = customerFailures.apply(alert.createdAt().minus(BEFORE), alert.createdAt().plus(AFTER));
            (failures > 0 ? trouble : falseAlarms).add(alert);
        }

        List<EpisodeScore> faults = scores.stream().filter(s -> s.episode().kind().equals("fault")).toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("faults", faults.size());
        summary.put("detected", faults.stream().filter(EpisodeScore::detected).count());
        summary.put("correct", faults.stream().filter(s -> s.verdict().equals("correct")).count());
        summary.put("mean_first_alert_after_s", faults.stream().filter(s -> s.firstAlertAfterSeconds() != null)
                .mapToDouble(EpisodeScore::firstAlertAfterSeconds).average().stream().boxed().findFirst()
                .map(v -> Math.round(v * 10) / 10.0).orElse(null));
        summary.put("false_alarms", falseAlarms.size());
        summary.put("alerts_on_customer_trouble", trouble.size());
        return new Report(scores, falseAlarms, trouble, summary);
    }

    /**
     * For a fault: {@code correct}, {@code missed}, {@code wrong_split} (detected, but not into the expected number of
     * incidents), {@code not_injected}. For a flash sale: {@code correct} (no trouble, no alert), {@code overload}
     * (customers' orders failed and Ops-Agent alerted), {@code missed_overload}, {@code false_alarm}. {@code pending}
     * while the window is still open.
     */
    private static String verdict(Episode episode, List<Alert> alerts, List<Incident> incidents, int failures,
            boolean settled) {
        if (!episode.actionsOk()) {
            return "not_injected";
        }
        if (episode.kind().equals("flash_sale")) {
            if (!alerts.isEmpty()) {
                return failures > 0 ? "overload" : "false_alarm";
            }
            return !settled ? "pending" : failures > 0 ? "missed_overload" : "correct";
        }
        if (alerts.isEmpty()) {
            return settled ? "missed" : "pending";
        }
        return incidents.size() == episode.expectedIncidents() ? "correct" : "wrong_split";
    }

    private static boolean within(Instant t, Instant from, Instant to) {
        return !t.isBefore(from) && !t.isAfter(to);
    }

    private Scoring() {
    }
}
