package io.github.dglearner.opsagent.stream.parse;

import io.github.dglearner.opsagent.contracts.NormalizedEvent;
import io.github.dglearner.opsagent.contracts.RawLogRecord;
import io.github.dglearner.opsagent.contracts.SourceType;
import io.github.dglearner.opsagent.contracts.UnparsedLog;
import io.github.dglearner.opsagent.stream.redact.Redactor;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Picks the parser for a record's source type and builds the redacted {@link NormalizedEvent}. */
public class ParserRegistry {

    private final Map<SourceType, LogParser> parsers = new EnumMap<>(SourceType.class);
    private final Redactor redactor;
    private final MeterRegistry meters;

    public ParserRegistry(List<LogParser> parsers, Redactor redactor) {
        this(parsers, redactor, null);
    }

    public ParserRegistry(List<LogParser> parsers, Redactor redactor, MeterRegistry meters) {
        parsers.forEach(p -> this.parsers.put(p.sourceType(), p));
        this.redactor = redactor;
        this.meters = meters;
    }

    public static ParserRegistry withDefaultParsers() {
        return new ParserRegistry(List.of(new NginxParser(), new LinuxParser(), new MysqlParser(),
                new AppServiceParser(), new DockerParser()), new Redactor());
    }

    public static ParserRegistry withDefaultParsers(MeterRegistry meters) {
        return new ParserRegistry(List.of(new NginxParser(), new LinuxParser(), new MysqlParser(),
                new AppServiceParser(), new DockerParser()), new Redactor(), meters);
    }

    public ParseOutcome parse(RawLogRecord raw) {
        LogParser parser = parsers.get(raw.sourceType());
        if (parser == null) {
            count("unparsed");
            return unparsed(raw, "no parser for source type " + wireName(raw.sourceType()));
        }
        Optional<ParsedLog> parsed;
        try {
            parsed = parser.parse(raw);
        } catch (RuntimeException e) {
            // A parser bug must not stop the stream; the line is kept in logs.unparsed for inspection.
            count("error");
            return unparsed(raw, "parser error: " + e);
        }
        if (parsed.isEmpty()) {
            count("unparsed");
            return unparsed(raw, "no " + wireName(raw.sourceType()) + " pattern matched");
        }
        ParsedLog p = parsed.get();
        count("parsed");
        return new ParseOutcome.Parsed(new NormalizedEvent(
                raw.eventId(),
                raw.sourceId(),
                raw.sourceType(),
                raw.sourceName(),
                raw.host(),
                raw.service() != null ? raw.service() : p.service(),
                p.component(),
                p.eventType(),
                p.severity(),
                p.timestamp() != null ? p.timestamp() : raw.receivedAt(),
                raw.receivedAt(),
                redactor.redact(p.message() != null ? p.message() : raw.message()),
                redactor.redactFields(p.fields()),
                p.tags()));
    }

    private void count(String result) {
        if (meters != null) {
            meters.counter("ops.stream.parse", "result", result).increment();
            meters.counter("ops.stream.redaction", "result", result.equals("parsed") ? "parsed_fields" : "unparsed_message")
                    .increment();
        }
    }

    private ParseOutcome unparsed(RawLogRecord raw, String reason) {
        RawLogRecord redacted = new RawLogRecord(raw.eventId(), raw.requestId(), raw.sourceId(), raw.sourceType(),
                raw.host(), raw.service(), raw.sourceName(), redactor.redact(raw.message()), raw.receivedAt());
        return new ParseOutcome.Unparsed(new UnparsedLog(redacted, reason));
    }

    public static String wireName(SourceType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }
}
