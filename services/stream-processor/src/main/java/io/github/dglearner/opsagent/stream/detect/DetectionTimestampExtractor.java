package io.github.dglearner.opsagent.stream.detect;

import io.github.dglearner.opsagent.contracts.NormalizedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

/** Window time for {@code logs.normalized}: event time or receive time, see {@link DetectionTime}. */
public class DetectionTimestampExtractor implements TimestampExtractor {

    private final DetectionTime mode;

    public DetectionTimestampExtractor(DetectionTime mode) {
        this.mode = mode;
    }

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        if (record.value() instanceof NormalizedEvent event) {
            var time = mode == DetectionTime.EVENT ? event.timestamp() : event.receivedAt();
            if (time != null) {
                return time.toEpochMilli();
            }
        }
        return record.timestamp();
    }
}
