package io.github.dglearner.shop.lab;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

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
 *
 * <p>A fault is attributed correctly if, for each of its targets, the latest analysis of one of the episode's
 * incidents names that target as the most likely cause.
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

    /** An incident as Ops-Agent's API returned it, with its analyses by the agent, newest first. */
    record Incident(String incidentId, String title, String status, List<String> subjects, int alertCount,
            Instant openedAt, Instant lastAlertAt, List<Analysis> analyses) {

        Incident(String incidentId, String title, List<String> subjects, int alertCount, Instant openedAt,
                Instant lastAlertAt) {
            this(incidentId, title, "open", subjects, alertCount, openedAt, lastAlertAt, List.of());
        }

        Optional<Analysis> latestCompleted() {
            return analyses.stream().filter(a -> a.status().equals("completed")).findFirst();
        }
    }

    /**
     * An analysis by the agent. {@code status}: pending, completed, failed or timed_out. {@code rejectedEvidence}:
     * how many of its citations core-service found the incident does not have.
     */
    record Analysis(String analysisId, String status, String requestedBy, Instant requestedAt, String model,
            Boolean uncertain, String summary, List<Hypothesis> hypotheses, List<String> nextSteps,
            int rejectedEvidence, Integer toolCalls, Long durationMs, String error) {
    }

    record Hypothesis(String component, String cause, String confidence, int evidence) {
    }

    /**
     * {@code attribution} (faults with incidents only): {@code correct}, {@code partial} (some of the targets),
     * {@code not_first} (a target is among the hypotheses, not first), {@code wrong}, {@code pending} (no answer
     * yet) or {@code failed} (the agent did not answer). {@code attributedTo}: the first hypotheses.
     */
    record EpisodeScore(Episode episode, String verdict, boolean detected, Double firstAlertAfterSeconds,
            int customerFailures, Instant windowFrom, Instant windowTo, List<Alert> alerts, List<Incident> incidents,
            String attribution, List<String> attributedTo) {
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
                    !inWindow.isEmpty(), delay, failures, windowFrom, windowTo, inWindow, active,
                    attribution(episode, active), firstHypotheses(active)));
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
        summary.put("analysed", faults.stream().filter(s -> s.attribution() != null
                && !s.attribution().equals("pending") && !s.attribution().equals("failed")).count());
        summary.put("attributed", faults.stream().filter(s -> "correct".equals(s.attribution())).count());
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

    private static String attribution(Episode episode, List<Incident> incidents) {
        if (!episode.kind().equals("fault") || incidents.isEmpty()) {
            return null;
        }
        List<Analysis> analyses = incidents.stream().map(Incident::latestCompleted).flatMap(Optional::stream).toList();
        if (analyses.isEmpty()) {
            boolean waiting = incidents.stream().anyMatch(i -> i.analyses().isEmpty()
                    || i.analyses().getFirst().status().equals("pending"));
            return waiting ? "pending" : "failed";
        }
        Set<String> targets = Set.of(episode.target().split(",\\s*"));
        Set<String> first = analyses.stream().filter(a -> !a.hypotheses().isEmpty())
                .map(a -> a.hypotheses().getFirst().component()).collect(Collectors.toSet());
        long hits = targets.stream().filter(first::contains).count();
        if (hits == targets.size()) {
            return "correct";
        }
        if (hits > 0) {
            return "partial";
        }
        boolean mentioned = analyses.stream().flatMap(a -> a.hypotheses().stream())
                .anyMatch(h -> targets.contains(h.component()));
        return mentioned ? "not_first" : "wrong";
    }

    private static List<String> firstHypotheses(List<Incident> incidents) {
        return incidents.stream().map(Incident::latestCompleted).flatMap(Optional::stream)
                .filter(a -> !a.hypotheses().isEmpty()).map(a -> a.hypotheses().getFirst().component())
                .distinct().toList();
    }

    private static boolean within(Instant t, Instant from, Instant to) {
        return !t.isBefore(from) && !t.isAfter(to);
    }

    private Scoring() {
    }
}
