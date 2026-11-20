package io.github.dglearner.opsagent.stream.detect;

import io.github.dglearner.opsagent.contracts.NormalizedEvent;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;

/**
 * Applies the same detection timestamp that the old {@code logs.normalized} source extractor used, after parsing
 * directly from {@code logs.raw}. The normalized output branch keeps the raw record timestamp; only detection state
 * and window processors receive the event/ingest timestamp selected by the existing setting.
 */
public class DetectionTimestampProcessor implements Processor<String, NormalizedEvent, String, NormalizedEvent> {

    private final DetectionTime mode;
    private ProcessorContext<String, NormalizedEvent> context;

    public DetectionTimestampProcessor(DetectionTime mode) {
        this.mode = mode;
    }

    @Override
    public void init(ProcessorContext<String, NormalizedEvent> context) {
        this.context = context;
    }

    @Override
    public void process(Record<String, NormalizedEvent> record) {
        NormalizedEvent event = record.value();
        if (event == null) {
            return;
        }
        var selected = mode == DetectionTime.EVENT ? event.timestamp() : event.receivedAt();
        context.forward(record.withTimestamp(selected == null ? record.timestamp() : selected.toEpochMilli()));
    }
}
