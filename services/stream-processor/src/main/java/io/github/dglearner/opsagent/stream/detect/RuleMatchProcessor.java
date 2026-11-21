package io.github.dglearner.opsagent.stream.detect;

import io.github.dglearner.opsagent.contracts.NormalizedEvent;
import io.github.dglearner.opsagent.contracts.RuleDefinition;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.ValueAndTimestamp;

/**
 * Evaluates every rule from the global rules store against each event and forwards one {@link RuleMatch} per matching
 * rule, re-keyed by fingerprint. Global stores are readable from any processor, so rule changes apply to the next
 * event without restarting.
 */
public class RuleMatchProcessor implements Processor<String, NormalizedEvent, String, RuleMatch> {

    private final String rulesStoreName;
    private final MeterRegistry meters;
    private ProcessorContext<String, RuleMatch> context;
    private ReadOnlyKeyValueStore<String, ValueAndTimestamp<RuleDefinition>> rules;

    public RuleMatchProcessor(String rulesStoreName) {
        this(rulesStoreName, null);
    }

    public RuleMatchProcessor(String rulesStoreName, MeterRegistry meters) {
        this.rulesStoreName = rulesStoreName;
        this.meters = meters;
    }

    @Override
    public void init(ProcessorContext<String, RuleMatch> context) {
        this.context = context;
        this.rules = context.getStateStore(rulesStoreName);
    }

    @Override
    public void process(Record<String, NormalizedEvent> record) {
        if (record.value() == null) {
            return;
        }
        boolean matched = false;
        try (KeyValueIterator<String, ValueAndTimestamp<RuleDefinition>> all = rules.all()) {
            while (all.hasNext()) {
                KeyValue<String, ValueAndTimestamp<RuleDefinition>> entry = all.next();
                if (entry.value == null || entry.value.value() == null) {
                    continue;
                }
                var match = RuleMatcher.match(entry.value.value(), record.value());
                if (match.isPresent()) {
                    matched = true;
                    context.forward(record.withKey(match.get().fingerprint()).withValue(match.get()));
                }
            }
        }
        if (meters != null) {
            meters.counter("ops.stream.rule.events", "result", matched ? "matched" : "unmatched").increment();
        }
    }
}
