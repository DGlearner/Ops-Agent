package io.github.dglearner.opsagent.stream.topology;

import io.github.dglearner.opsagent.contracts.Alert;
import io.github.dglearner.opsagent.contracts.JsonSerde;
import io.github.dglearner.opsagent.contracts.NormalizedEvent;
import io.github.dglearner.opsagent.contracts.RawLogRecord;
import io.github.dglearner.opsagent.contracts.RuleDefinition;
import io.github.dglearner.opsagent.contracts.Topics;
import io.github.dglearner.opsagent.contracts.UnparsedLog;
import io.github.dglearner.opsagent.stream.config.StreamProperties;
import io.github.dglearner.opsagent.stream.detect.AlertFactory;
import io.github.dglearner.opsagent.stream.detect.AlertSuppressor;
import io.github.dglearner.opsagent.stream.detect.DetectionTimestampExtractor;
import io.github.dglearner.opsagent.stream.detect.DuplicateMatchFilter;
import io.github.dglearner.opsagent.stream.detect.RuleMatch;
import io.github.dglearner.opsagent.stream.detect.RuleMatchProcessor;
import io.github.dglearner.opsagent.stream.detect.WindowAggregate;
import io.github.dglearner.opsagent.stream.parse.ParseOutcome;
import io.github.dglearner.opsagent.stream.parse.ParserRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Branched;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Repartitioned;
import org.apache.kafka.streams.kstream.SlidingWindows;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.WindowStore;

/**
 * The stream-processor topology.
 *
 * <pre>
 * logs.raw ── parse + redact ──┬── logs.normalized
 *                              └── logs.unparsed
 * logs.normalized ── match rules (global table rules.changelog) ── per window tier:
 *     repartition by fingerprint ── drop duplicate events ── sliding-window count ── threshold ── suppression
 *     ── alerts
 * </pre>
 *
 * Window sizes are fixed per sub-topology, so each supported tier (1, 5, 15 minutes) has its own branch and rules are
 * routed by their window. Processor and store names are explicit so that internal topic names stay stable across
 * releases.
 */
public class OpsTopology {

    public static final String RULES_STORE = "rules";

    static final Serde<String> STRING = Serdes.String();
    static final Serde<RawLogRecord> RAW = JsonSerde.of(RawLogRecord.class);
    static final Serde<NormalizedEvent> NORMALIZED = JsonSerde.of(NormalizedEvent.class);
    static final Serde<UnparsedLog> UNPARSED = JsonSerde.of(UnparsedLog.class);
    static final Serde<RuleDefinition> RULE = JsonSerde.of(RuleDefinition.class);
    static final Serde<RuleMatch> MATCH = JsonSerde.of(RuleMatch.class);
    static final Serde<WindowAggregate> AGGREGATE = JsonSerde.of(WindowAggregate.class);
    static final Serde<Alert> ALERT = JsonSerde.of(Alert.class);

    private static final List<Duration> WINDOW_TIERS = RuleDefinition.SUPPORTED_WINDOWS.stream().sorted().toList();

    private final ParserRegistry parsers;
    private final StreamProperties properties;
    private final Clock clock;

    public OpsTopology(ParserRegistry parsers, StreamProperties properties, Clock clock) {
        this.parsers = parsers;
        this.properties = properties;
        this.clock = clock;
    }

    public void addTo(StreamsBuilder builder) {
        addParsing(builder);
        addDetection(builder);
    }

    private void addParsing(StreamsBuilder builder) {
        Map<String, KStream<String, ParseOutcome>> outcomes = builder
                .stream(Topics.LOGS_RAW, Consumed.with(STRING, RAW).withName("read-raw"))
                .mapValues(parsers::parse, Named.as("parse"))
                .split(Named.as("parse-"))
                .branch((key, outcome) -> outcome instanceof ParseOutcome.Parsed, Branched.as("ok"))
                .defaultBranch(Branched.as("unparsed"));

        outcomes.get("parse-ok")
                .map((key, outcome) -> {
                    NormalizedEvent event = ((ParseOutcome.Parsed) outcome).event();
                    return KeyValue.pair(event.serviceOrHost(), event);
                }, Named.as("key-by-service"))
                .to(Topics.LOGS_NORMALIZED, Produced.with(STRING, NORMALIZED).withName("write-normalized"));

        outcomes.get("parse-unparsed")
                .map((key, outcome) -> {
                    UnparsedLog log = ((ParseOutcome.Unparsed) outcome).log();
                    return KeyValue.pair(ParserRegistry.wireName(log.raw().sourceType()), log);
                }, Named.as("key-by-source-type"))
                .to(Topics.LOGS_UNPARSED, Produced.with(STRING, UNPARSED).withName("write-unparsed"));
    }

    private void addDetection(StreamsBuilder builder) {
        builder.globalTable(Topics.RULES_CHANGELOG,
                Consumed.with(STRING, RULE).withName("read-rules"),
                Materialized.<String, RuleDefinition>as(Stores.inMemoryKeyValueStore(RULES_STORE))
                        .withKeySerde(STRING).withValueSerde(RULE));

        KStream<String, RuleMatch> matches = builder
                .stream(Topics.LOGS_NORMALIZED, Consumed.with(STRING, NORMALIZED)
                        .withTimestampExtractor(new DetectionTimestampExtractor(properties.detectionTime()))
                        .withName("read-normalized"))
                .process(() -> new RuleMatchProcessor(RULES_STORE), Named.as("match-rules"));

        for (Duration window : WINDOW_TIERS) {
            String tier = AlertFactory.humanizeWindow(window);
            String dedupStore = "match-dedup-" + tier;
            Duration dedupRetention = window.plus(properties.grace());
            builder.addStateStore(Stores.windowStoreBuilder(
                    Stores.persistentWindowStore(dedupStore, dedupRetention, dedupRetention, false),
                    STRING, Serdes.Long()));
            String suppressionStore = "alert-suppression-" + tier;
            builder.addStateStore(Stores.keyValueStoreBuilder(
                    Stores.persistentKeyValueStore(suppressionStore), STRING, Serdes.Long()));

            // Named like the repartition topic groupByKey used to create, so the internal topic name is unchanged.
            matches.filter((fingerprint, match) -> window.equals(match.window()), Named.as("tier-" + tier))
                    .repartition(Repartitioned.<String, RuleMatch>as("detect-" + tier)
                            .withKeySerde(STRING).withValueSerde(MATCH))
                    .processValues(() -> new DuplicateMatchFilter(dedupStore, dedupRetention),
                            Named.as("dedup-" + tier), dedupStore)
                    .groupByKey(Grouped.with(STRING, MATCH))
                    .windowedBy(SlidingWindows.ofTimeDifferenceAndGrace(window, properties.grace()))
                    .aggregate(WindowAggregate::empty,
                            (fingerprint, match, agg) -> agg.add(match, properties.maxSampleEvents()),
                            Named.as("count-" + tier),
                            Materialized.<String, WindowAggregate, WindowStore<Bytes, byte[]>>as("windows-" + tier)
                                    .withKeySerde(STRING).withValueSerde(AGGREGATE))
                    .toStream(Named.as("window-updates-" + tier))
                    .process(() -> new AlertSuppressor(suppressionStore, clock), Named.as("suppress-" + tier),
                            suppressionStore)
                    .to(Topics.ALERTS, Produced.with(STRING, ALERT).withName("write-alerts-" + tier));
        }
    }
}
