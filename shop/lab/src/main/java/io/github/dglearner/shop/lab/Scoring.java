package io.github.dglearner.shop.lab;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores what Ops-Agent reported against the ground truth (docs/shop-design.md §15). An episode's window runs from
 * {@link #LEAD} before it started to {@link #TAIL} after it ended, since effects outlast a fault (retries, recovery).
 * A fault is detected if an alert falls in its window, and correctly split if the incidents opened in it are as many
 * as expected. During a flash sale any alert is a false alarm, and so is every alert outside all fault windows.
 */
final class Scoring {

    static final Duration LEAD = Duration.ofSeconds(30);
    static final Duration TAIL = Duration.ofMinutes(5);

    /** An alert as Ops-Agent's API returned it. */
    record Alert(String alertId, String ruleId, String service, String summary, Instant createdAt) {
    }

    /** An incident as Ops-Agent's API returned it. */
    record Incident(String incidentId, String title, List<String> subjects, int alertCount, Instant openedAt) {
    }

    record EpisodeScore(Episode episode, String verdict, boolean detected, Double firstAlertAfterSeconds,
            List<Alert> alerts, List<Incident> incidents) {
    }

    record Report(List<EpisodeScore> episodes, List<Alert> falseAlarms, Map<String, Object> summary) {
    }

    static Report score(List<Episode> episodes, List<Alert> alerts, List<Incident> incidents, Instant now) {
        List<EpisodeScore> scores = new ArrayList<>();
        for (Episode episode : episodes.stream().sorted(Comparator.comparing(Episode::startedAt)).toList()) {
            Instant from = episode.startedAt().minus(LEAD);
            Instant to = (episode.endedAt() != null ? episode.endedAt() : now).plus(TAIL);
            List<Alert> inWindow = alerts.stream().filter(a -> within(a.createdAt(), from, to))
                    .sorted(Comparator.comparing(Alert::createdAt)).toList();
            List<Incident> opened = incidents.stream().filter(i -> within(i.openedAt(), from, to)).toList();
            boolean settled = episode.endedAt() != null && now.isAfter(to);
            Double delay = inWindow.isEmpty() ? null
                    : Duration.between(episode.startedAt(), inWindow.getFirst().createdAt()).toMillis() / 1000.0;
            scores.add(new EpisodeScore(episode, verdict(episode, inWindow, opened, settled), !inWindow.isEmpty(),
                    delay, inWindow, opened));
        }

        List<Alert> falseAlarms = alerts.stream()
                .filter(a -> episodes.stream().noneMatch(e -> e.kind().equals("fault") && within(a.createdAt(),
                        e.startedAt().minus(LEAD), (e.endedAt() != null ? e.endedAt() : now).plus(TAIL))))
                .toList();

        List<EpisodeScore> faults = scores.stream().filter(s -> s.episode().kind().equals("fault")).toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("faults", faults.size());
        summary.put("detected", faults.stream().filter(EpisodeScore::detected).count());
        summary.put("correct", faults.stream().filter(s -> s.verdict().equals("correct")).count());
        summary.put("mean_first_alert_after_s", faults.stream().filter(s -> s.firstAlertAfterSeconds() != null)
                .mapToDouble(EpisodeScore::firstAlertAfterSeconds).average().stream().boxed().findFirst()
                .map(v -> Math.round(v * 10) / 10.0).orElse(null));
        summary.put("false_alarms", falseAlarms.size());
        return new Report(scores, falseAlarms, summary);
    }

    /**
     * {@code correct}, {@code missed}, {@code wrong_split} (detected, but not into the expected number of incidents),
     * {@code false_alarm} (a flash sale that alerted), {@code not_injected} or {@code pending} (its window is open).
     */
    private static String verdict(Episode episode, List<Alert> alerts, List<Incident> incidents, boolean settled) {
        if (!episode.actionsOk()) {
            return "not_injected";
        }
        if (episode.kind().equals("flash_sale")) {
            return !alerts.isEmpty() ? "false_alarm" : settled ? "correct" : "pending";
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
