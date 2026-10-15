package io.github.dglearner.opsagent.stream.parse;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Timestamp formats found in the supported logs. Unparseable values give null, never an exception. */
final class Timestamps {

    private static final DateTimeFormatter SYSLOG_MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter NGINX =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

    /**
     * Syslog lines carry neither year nor zone. UTC is assumed, and the year is the one that puts the line closest
     * before the receive time: a line from "Dec 31" received on January 1st belongs to the previous year.
     */
    static Instant syslog(String month, String day, String time, Instant receivedAt) {
        try {
            MonthDay monthDay = MonthDay.parse(month + " " + Integer.parseInt(day), SYSLOG_MONTH_DAY);
            int year = receivedAt.atZone(ZoneOffset.UTC).getYear();
            Instant candidate = at(monthDay, year, time);
            if (candidate.isAfter(receivedAt.plus(Duration.ofDays(1)))) {
                candidate = at(monthDay, year - 1, time);
            }
            return candidate;
        } catch (DateTimeParseException | NumberFormatException e) {
            return null;
        }
    }

    static Instant iso(String text) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static Instant nginx(String text) {
        try {
            return OffsetDateTime.parse(text, NGINX).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Instant at(MonthDay monthDay, int year, String time) {
        return LocalDateTime.parse(monthDay.atYear(year) + "T" + time).toInstant(ZoneOffset.UTC);
    }

    private Timestamps() {
    }
}
