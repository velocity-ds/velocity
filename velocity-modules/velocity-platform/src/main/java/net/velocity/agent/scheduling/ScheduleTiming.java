package net.velocity.agent.scheduling;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;

import org.springframework.scheduling.support.CronExpression;

import net.velocity.ApplicationTime;

/**
 * Normalized timing: absolute instants are UTC; relative delays are resolved only at admission.
 * Recurrence uses Spring's six fields (second, minute, hour, day of month, month, day of week).
 * Cron is evaluated in local civil time: nonexistent times are skipped, and an overlapping local
 * time occurs once using its earlier offset. Due times are not exact-second delivery guarantees.
 */
public record ScheduleTiming(String mode, String at, Long delaySeconds, String cron, String timezone) {

    public ScheduleTiming {
        ZoneId zone = zone(timezone);
        timezone = zone.getId();
        if ("at".equals(mode)) {
            if (at == null || delaySeconds != null || cron != null) {
                throw new IllegalArgumentException("Absolute timing requires only at");
            }
            at = Instant.parse(at).toString();
        } else if ("cron".equals(mode)) {
            if (at != null || delaySeconds != null || cron == null || cron.isBlank()) {
                throw new IllegalArgumentException("Recurring timing requires only cron");
            }
            cron = cron.trim();
            if (cron.split("\\s+").length != 6) {
                throw new IllegalArgumentException(
                        "Cron requires six fields: second minute hour day-of-month month day-of-week");
            }
            CronExpression.parse(cron);
        } else {
            throw new IllegalArgumentException("Unknown schedule timing mode");
        }
    }

    /** Exactly one input mode is required. Ambiguous local timestamps need user clarification. */
    public static ScheduleTiming resolve(
            String at,
            Long delaySeconds,
            String cron,
            String timezone,
            Clock clock) {
        Objects.requireNonNull(clock, "Clock");
        int modes = (at == null ? 0 : 1) + (delaySeconds == null ? 0 : 1) + (cron == null ? 0 : 1);
        if (modes != 1) {
            throw new IllegalArgumentException("Specify exactly one of at, delaySeconds, or cron");
        }
        ZoneId zone = zone(timezone);
        Instant now = clock.instant();
        if (cron != null) {
            var timing = new ScheduleTiming("cron", null, null, cron, zone.getId());
            if (timing.nextAfter(now) == null) {
                throw new IllegalArgumentException("Cron has no future occurrence");
            }
            return timing;
        }
        Instant due;
        try {
            if (delaySeconds != null) {
                if (delaySeconds <= 0) {
                    throw new IllegalArgumentException("delaySeconds must be positive");
                }
                due = now.plusSeconds(delaySeconds);
            } else {
                due = absolute(at, zone);
            }
        } catch (DateTimeException | ArithmeticException failure) {
            throw new IllegalArgumentException("Invalid or out-of-range schedule timestamp", failure);
        }
        if (!due.isAfter(now)) {
            throw new IllegalArgumentException("Scheduled timestamp must be in the future");
        }
        return new ScheduleTiming("at", due.toString(), null, null, zone.getId());
    }

    public boolean recurring() {
        return "cron".equals(mode);
    }

    /** One-time instants remain available when overdue so recovery can dispatch them once. */
    public Instant first(Clock clock) {
        return recurring() ? nextAfter(clock.instant()) : Instant.parse(at);
    }

    public Instant nextAfter(Instant instant) {
        Objects.requireNonNull(instant, "Reference instant");
        if (!recurring()) {
            Instant due = Instant.parse(at);
            return due.isAfter(instant) ? due : null;
        }
        ZoneId zone = zone(timezone);
        var rules = zone.getRules();
        var expression = CronExpression.parse(cron);
        LocalDateTime cursor = LocalDateTime.ofInstant(instant, zone);
        while (true) {
            LocalDateTime next = expression.next(cursor);
            if (next == null) {
                return null;
            }
            var offsets = rules.getValidOffsets(next);
            if (offsets.isEmpty()) {
                // Skip the entire gap rather than iterating potentially thousands of seconds.
                cursor = rules.getTransition(next).getDateTimeAfter().minusNanos(1);
                continue;
            }
            Instant candidate = next.toInstant(offsets.getFirst());
            if (candidate.isAfter(instant)) {
                return candidate;
            }
            // During the repeated hour, the earlier-offset occurrence has already elapsed.
            cursor = offsets.size() > 1
                    ? rules.getTransition(next).getDateTimeBefore().minusNanos(1)
                    : next;
        }
    }

    private static Instant absolute(String value, ZoneId zone) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("at must be an ISO-8601 timestamp");
        }
        String text = value.trim();
        // An explicit UTC timestamp is an unambiguous execution instant in any display timezone.
        if (text.endsWith("Z") || text.endsWith("z")) {
            return Instant.parse(text);
        }
        try {
            OffsetDateTime offset = OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            if (ZoneOffset.UTC.equals(offset.getOffset())) {
                return offset.toInstant();
            }
            if (!zone.getRules().getValidOffsets(offset.toLocalDateTime()).contains(offset.getOffset())) {
                throw new IllegalArgumentException(
                        "Timestamp offset is not valid for the selected timezone; resolve the date and timezone before scheduling");
            }
            return offset.toInstant();
        } catch (DateTimeParseException ignored) {
            LocalDateTime local = LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            var offsets = zone.getRules().getValidOffsets(local);
            if (offsets.isEmpty()) {
                throw new IllegalArgumentException(
                        "Local time does not exist because of daylight saving; ask the user for another time");
            }
            if (offsets.size() != 1) {
                throw new IllegalArgumentException(
                        "Local time is ambiguous because of daylight saving; ask the user to select an explicit offset");
            }
            return local.toInstant(offsets.getFirst());
        }
    }

    private static ZoneId zone(String value) {
        if (value == null) {
            return ApplicationTime.zone();
        }
        if (!ZoneId.getAvailableZoneIds().contains(value)) {
            throw new IllegalArgumentException("timezone must be an IANA timezone such as America/New_York");
        }
        return ZoneId.of(value);
    }
}
