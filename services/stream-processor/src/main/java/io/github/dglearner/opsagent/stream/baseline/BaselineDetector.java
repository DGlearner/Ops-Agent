package io.github.dglearner.opsagent.stream.baseline;

import io.github.dglearner.opsagent.contracts.Alert;
import io.github.dglearner.opsagent.contracts.Ids;
import io.github.dglearner.opsagent.contracts.NormalizedEvent;
import io.github.dglearner.opsagent.contracts.Severity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

/**
 * Statistical baseline detection, without rules (docs/project-design.md §8.8). Each service's lines are sorted into
 * templates ({@link Drain}) and counted per minute. When a minute closes, each template's count is compared with its
 * own history, an exponentially weighted mean and variance of its lines per minute, and two things alert, for warning
 * and error templates only (a burst of ordinary lines is load, not a fault), one alert per service, kind and minute:
 *
 * <ul>
 *   <li>{@value #NEW_PATTERN}: a template that first appeared after the service's calibration, with at least
 *       {@code new-pattern-min} lines within its first {@code novelty};</li>
 *   <li>{@value #ERROR_SPIKE}: a known template with at least {@code spike-min} lines in a minute and
 *       {@code spike-sigmas} standard deviations above its baseline; then quiet for {@code suppress-for}.</li>
 * </ul>
 *
 * <p>Nothing alerts during a service's first {@code calibration}; what appears then is its normal. A drop in ordinary
 * lines is not looked for: without traffic figures it cannot be told from fewer customers.
 *
 * <p>The input is keyed by service, so a service's lines all come to one task, in order. Models live in memory while
 * a minute is counted and are written to the store whenever a minute closes; a minute closes when a later line of the
 * service arrives, or by stream-time punctuation for a service that went silent. Times are detection time (the record
 * timestamp), as for the rules.
 */
public class BaselineDetector implements Processor<String, NormalizedEvent, String, Alert> {

    public static final String NEW_PATTERN = "baseline-new-pattern";
    public static final String ERROR_SPIKE = "baseline-error-spike";
    static final int MAX_TEMPLATE_TEXT = 200;
    /** A gap longer than this (a service silent for hours) is not replayed minute by minute; the baseline decays. */
    private static final int MAX_CLOSED_AT_ONCE = 180;
    private static final Duration PUNCTUATION = Duration.ofSeconds(15);

    private final String storeName;
    private final BaselineProperties properties;
    private final int maxSamples;
    private final Clock clock;
    private final double alpha;
    private final Map<String, Working> models = new HashMap<>();
    private ProcessorContext<String, Alert> context;
    private KeyValueStore<String, ServiceModel> store;

    public BaselineDetector(String storeName, BaselineProperties properties, int maxSamples, Clock clock) {
        this.storeName = storeName;
        this.properties = properties;
        this.maxSamples = maxSamples;
        this.clock = clock;
        this.alpha = 2.0 / (properties.baselineMinutes() + 1);
    }

    @Override
    public void init(ProcessorContext<String, Alert> context) {
        this.context = context;
        this.store = context.getStateStore(storeName);
        context.schedule(PUNCTUATION, PunctuationType.STREAM_TIME, this::closeSilent);
    }

    @Override
    public void process(Record<String, NormalizedEvent> record) {
        NormalizedEvent event = record.value();
        if (event == null) {
            return;
        }
        String service = event.serviceOrHost();
        long minute = Math.floorDiv(record.timestamp(), 60_000L);
        Working model = models.computeIfAbsent(service, this::load);
        if (model.currentMinute < 0) {
            model.firstMinute = minute;
            model.currentMinute = minute;
        } else if (minute > model.currentMinute) {
            closeUntil(service, model, minute);
        }
        Drain.Template template = model.drain.add(event.message());
        if (template == null) {
            return;
        }
        model.count(template, event, maxSamples);
        model.stats.computeIfAbsent(template.id(), id -> new Stats(model.currentMinute));
        model.stats.get(template.id()).raise(event.severity());
    }

    /** Closes the minutes of every service whose minute ended more than the grace ago, by stream time. */
    private void closeSilent(long streamTime) {
        long closable = Math.floorDiv(streamTime - properties.grace().toMillis(), 60_000L);
        models.forEach((service, model) -> {
            if (model.currentMinute >= 0 && model.currentMinute < closable) {
                closeUntil(service, model, closable);
            }
        });
    }

    private void closeUntil(String service, Working model, long minute) {
        if (minute - model.currentMinute > MAX_CLOSED_AT_ONCE) {
            // Close the counted minute, then let the gap decay the baselines in one step.
            close(service, model);
            double decay = Math.pow(1 - alpha, minute - model.currentMinute);
            model.stats.values().forEach(s -> {
                s.mean *= decay;
                s.variance *= decay;
            });
            model.currentMinute = minute;
        }
        while (model.currentMinute < minute) {
            close(service, model);
        }
        store.put(service, model.snapshot());
    }

    /**
     * Evaluates the minute being counted, updates the baselines, and moves to the next minute. A minute that alerted,
     * or a new warning's first minutes, do not go into the baseline: otherwise one incident raises the baseline so high
     * that the same fault next time is no longer a spike. For the same reason a template that appears after the
     * calibration starts from a baseline of zero, while one learned in the calibration starts from its first minute.
     */
    private void close(String service, Working model) {
        long minute = model.currentMinute;
        long calibration = properties.calibration().toMinutes();
        boolean calibrating = minute - model.firstMinute < calibration;
        long noveltyMinutes = properties.novelty().toMinutes();
        List<Finding> patterns = new ArrayList<>();
        List<Finding> spikes = new ArrayList<>();
        for (Map.Entry<String, Stats> entry : model.stats.entrySet()) {
            String id = entry.getKey();
            Stats s = entry.getValue();
            ServiceModel.Bucket bucket = model.counts.get(id);
            long count = bucket == null ? 0 : bucket.count();
            // WARN lines are parsed as low, ERROR as medium: both count.
            boolean serious = s.severity.isAtLeast(Severity.LOW);
            boolean appearedAfterCalibration = s.firstSeen - model.firstMinute >= calibration;
            boolean novel = minute - s.firstSeen < noveltyMinutes;
            if (novel) {
                s.novel += count;
            }
            boolean anomalous = false;
            if (!calibrating && serious) {
                if (novel && appearedAfterCalibration) {
                    anomalous = true;
                    if (bucket != null && !s.patternAlerted && s.novel >= properties.newPatternMin()) {
                        s.patternAlerted = true;
                        patterns.add(new Finding(id, s, bucket, s.novel));
                    }
                } else if (!novel && bucket != null) {
                    double threshold = Math.max(properties.spikeMin(),
                            s.mean + properties.spikeSigmas() * Math.sqrt(s.variance));
                    if (count >= threshold) {
                        anomalous = true;
                        if (minute >= s.spikeUntil) {
                            s.spikeUntil = minute + properties.suppressFor().toMinutes();
                            spikes.add(new Finding(id, s, bucket, count));
                        }
                    }
                }
            }
            if (!anomalous) {
                if (minute == s.firstSeen && !appearedAfterCalibration) {
                    // Starting from zero, the average would take an hour to reach a steady rate (0.8 for 1 a minute).
                    s.mean = count;
                    s.variance = 0;
                } else {
                    double delta = count - s.mean;
                    s.mean += alpha * delta;
                    s.variance = (1 - alpha) * (s.variance + alpha * delta * delta);
                }
            }
            if (count > 0) {
                s.lastSeen = minute;
            }
        }
        if (!patterns.isEmpty()) {
            emit(service, model, NEW_PATTERN, patterns, minute, properties.newPatternMin());
        }
        if (!spikes.isEmpty()) {
            emit(service, model, ERROR_SPIKE, spikes, minute, properties.spikeMin());
        }
        model.counts.clear();
        model.currentMinute = minute + 1;
    }

    /** A template that alerted this minute; {@code lines} is what the alert reports for it. */
    private record Finding(String id, Stats stats, ServiceModel.Bucket bucket, long lines) {
    }

    /**
     * One alert per service, kind and minute, for all the templates that alerted: a database going away brings five
     * new warnings at once in one service, and five alerts about it would only bury the next one. The most frequent
     * template comes first and gives the alert its samples first.
     */
    private void emit(String service, Working model, String ruleId, List<Finding> findings, long minute,
            int threshold) {
        findings.sort(Comparator.comparingLong(Finding::lines).reversed());
        Finding top = findings.getFirst();
        Severity severity = findings.stream().map(f -> f.stats().severity).reduce(Severity.INFO, Severity::max);
        String fingerprint = ruleId + "|service=" + service;
        Instant start = Instant.ofEpochSecond(minute * 60);
        Map<String, String> groupValues = new LinkedHashMap<>();
        groupValues.put("service", service);
        groupValues.put("template", model.text(top.id()));
        groupValues.put("templates", Integer.toString(findings.size()));
        String listed = findings.stream().limit(3).map(f -> ruleId.equals(NEW_PATTERN)
                ? "\"%s\" %d".formatted(model.text(f.id()), f.lines())
                : "\"%s\" %d in 1m, baseline %.1f".formatted(model.text(f.id()), f.lines(), f.stats().mean))
                .collect(Collectors.joining(", "));
        String more = findings.size() > 3 ? " and " + (findings.size() - 3) + " more" : "";
        boolean several = findings.size() > 1;
        String summary = ruleId.equals(NEW_PATTERN)
                ? "New %s pattern%s in %s, lines in %s first %d min: %s%s".formatted(level(severity),
                        several ? "s" : "", service, several ? "their" : "its", properties.novelty().toMinutes(), listed,
                        more)
                : "%s spike%s in %s: %s%s".formatted(capitalized(level(severity)), several ? "s" : "", service, listed,
                        more);
        List<Alert.Sample> samples = new ArrayList<>();
        for (int round = 0; samples.size() < maxSamples; round++) {
            boolean added = false;
            for (Finding f : findings) {
                if (round < f.bucket().samples().size() && samples.size() < maxSamples) {
                    samples.add(f.bucket().samples().get(round));
                    added = true;
                }
            }
            if (!added) {
                break;
            }
        }
        long count = findings.stream().mapToLong(f -> f.bucket().count()).sum();
        Instant firstAt = findings.stream().map(f -> f.bucket().firstAt()).min(Comparator.naturalOrder()).orElse(start);
        Instant lastAt = findings.stream().map(f -> f.bucket().lastAt()).max(Comparator.naturalOrder()).orElse(start);
        Alert alert = new Alert(Ids.nameBased(fingerprint + "|" + start), ruleId, 1, fingerprint, severity, service,
                service, top.bucket().host(), groupValues, start, start.plusSeconds(60), count, threshold, firstAt,
                lastAt, samples.stream().map(Alert.Sample::eventId).toList(), summary, clock.instant(), samples);
        context.forward(new Record<>(service, alert, start.plusSeconds(60).toEpochMilli()));
    }

    private Working load(String service) {
        ServiceModel stored = store.get(service);
        Working model = new Working(Drain.restore(stored == null ? List.of() : stored.templates(),
                properties.similarity(), properties.maxTemplates(),
                tokens -> Ids.nameBased(service + "|" + String.join(" ", tokens)).toString().substring(0, 12)));
        if (stored != null) {
            model.firstMinute = stored.firstMinute();
            model.currentMinute = stored.currentMinute();
            stored.stats().forEach((id, s) -> model.stats.put(id, Stats.of(s)));
            model.counts.putAll(stored.counts());
        }
        return model;
    }

    private static String level(Severity severity) {
        return severity.isAtLeast(Severity.MEDIUM) ? "error" : "warning";
    }

    private static String capitalized(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    /** A service's model while it is being counted. */
    private static final class Working {

        final Drain drain;
        final Map<String, Stats> stats = new LinkedHashMap<>();
        final Map<String, ServiceModel.Bucket> counts = new HashMap<>();
        long firstMinute = -1;
        long currentMinute = -1;

        Working(Drain drain) {
            this.drain = drain;
        }

        void count(Drain.Template template, NormalizedEvent e, int maxSamples) {
            ServiceModel.Bucket b = counts.get(template.id());
            List<Alert.Sample> samples = b == null ? new ArrayList<>() : new ArrayList<>(b.samples());
            if (samples.size() < maxSamples) {
                Object trace = e.fields().get("trace.id");
                String message = e.message().length() > 500 ? e.message().substring(0, 500) : e.message();
                samples.add(new Alert.Sample(e.eventId(), e.timestamp(), message, trace == null ? null : trace.toString()));
            }
            counts.put(template.id(), b == null
                    ? new ServiceModel.Bucket(1, e.host(), e.timestamp(), e.timestamp(), samples)
                    : new ServiceModel.Bucket(b.count() + 1, b.host(), b.firstAt(), e.timestamp(), samples));
        }

        String text(String templateId) {
            String text = drain.templates().stream().filter(t -> t.id().equals(templateId))
                    .map(Drain.Template::text).findFirst().orElse(templateId);
            return text.length() > MAX_TEMPLATE_TEXT ? text.substring(0, MAX_TEMPLATE_TEXT) + "…" : text;
        }

        ServiceModel snapshot() {
            Map<String, ServiceModel.Stats> s = new LinkedHashMap<>();
            stats.forEach((id, st) -> s.put(id, st.frozen()));
            return new ServiceModel(drain.templates(), s, firstMinute, currentMinute, Map.copyOf(counts));
        }
    }

    /** A template's baseline, mutable while counted. */
    private static final class Stats {

        Severity severity = Severity.INFO;
        double mean;
        double variance;
        final long firstSeen;
        long lastSeen;
        long novel;
        boolean patternAlerted;
        long spikeUntil;

        Stats(long firstSeen) {
            this.firstSeen = firstSeen;
            this.lastSeen = firstSeen;
        }

        void raise(Severity seen) {
            severity = Severity.max(severity, seen);
        }

        static Stats of(ServiceModel.Stats s) {
            Stats stats = new Stats(s.firstSeen());
            stats.severity = Severity.valueOf(s.severity().toUpperCase(Locale.ROOT));
            stats.mean = s.mean();
            stats.variance = s.variance();
            stats.lastSeen = s.lastSeen();
            stats.novel = s.novel();
            stats.patternAlerted = s.patternAlerted();
            stats.spikeUntil = s.spikeUntil();
            return stats;
        }

        ServiceModel.Stats frozen() {
            return new ServiceModel.Stats(severity.name().toLowerCase(Locale.ROOT), mean, variance, firstSeen, lastSeen,
                    novel, patternAlerted, spikeUntil);
        }
    }
}
