package net.velocity.agent.scheduling;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import net.velocity.ApplicationTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduleTimingTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void exactlyOneModeIsRequired() {
        assertThatThrownBy(() -> ScheduleTiming.resolve(null, null, null, "UTC", CLOCK))
                .hasMessage("Specify exactly one of at, delaySeconds, or cron");
        assertThatThrownBy(() -> ScheduleTiming.resolve("2026-03-01T13:00:00Z", 60L, null, "UTC", CLOCK))
                .hasMessage("Specify exactly one of at, delaySeconds, or cron");
        assertThatThrownBy(() -> ScheduleTiming.resolve(null, 60L, null, "UTC", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void delaysResolveToAbsoluteUtcInstantsAtAdmission() {
        var timing = ScheduleTiming.resolve(null, 90L, null, "Europe/Berlin", CLOCK);
        assertThat(timing.mode()).isEqualTo("at");
        assertThat(timing.at()).isEqualTo("2026-03-01T12:01:30Z");
        assertThat(timing.delaySeconds()).isNull();
        assertThat(timing.timezone()).isEqualTo("Europe/Berlin");
        assertThat(timing.recurring()).isFalse();
        assertThat(timing.first(CLOCK)).isEqualTo(Instant.parse("2026-03-01T12:01:30Z"));
        assertThatThrownBy(() -> ScheduleTiming.resolve(null, 0L, null, "UTC", CLOCK))
                .hasMessage("delaySeconds must be positive");
        assertThatThrownBy(() -> ScheduleTiming.resolve(null, Long.MAX_VALUE, null, "UTC", CLOCK))
                .hasMessage("Invalid or out-of-range schedule timestamp");
    }

    @Test
    void absoluteTimestampsAcceptUtcValidOffsetsAndUnambiguousLocalTimes() {
        assertThat(ScheduleTiming.resolve("2026-03-01T13:00:00Z", null, null, "Europe/Berlin", CLOCK).at())
                .isEqualTo("2026-03-01T13:00:00Z");
        assertThat(
                ScheduleTiming.resolve("2026-03-01T14:00:00+01:00", null, null, "Europe/Berlin", CLOCK).at())
                .isEqualTo("2026-03-01T13:00:00Z");
        assertThat(ScheduleTiming.resolve("2026-03-01T14:00:00", null, null, "Europe/Berlin", CLOCK).at())
                .isEqualTo("2026-03-01T13:00:00Z");
        assertThat(
                ScheduleTiming.resolve("2026-03-01T14:00:00+00:00", null, null, "Europe/Berlin", CLOCK).at())
                .isEqualTo("2026-03-01T14:00:00Z");
        assertThatThrownBy(
                () -> ScheduleTiming.resolve("2026-03-01T14:00:00+05:00", null, null, "Europe/Berlin", CLOCK))
                .hasMessageContaining("offset is not valid for the selected timezone");
        assertThatThrownBy(() -> ScheduleTiming.resolve("2026-03-01T11:00:00Z", null, null, "UTC", CLOCK))
                .hasMessage("Scheduled timestamp must be in the future");
        assertThatThrownBy(() -> ScheduleTiming.resolve("tomorrow", null, null, "UTC", CLOCK))
                .hasMessage("Invalid or out-of-range schedule timestamp");
        assertThatThrownBy(() -> ScheduleTiming.resolve(" ", null, null, "UTC", CLOCK))
                .hasMessage("at must be an ISO-8601 timestamp");
    }

    @Test
    void localTimesInsideDaylightSavingTransitionsNeedClarification() {
        assertThatThrownBy(
                () -> ScheduleTiming.resolve("2026-03-08T02:30:00", null, null, "America/New_York", CLOCK))
                .hasMessageContaining("does not exist because of daylight saving");
        assertThatThrownBy(
                () -> ScheduleTiming.resolve("2026-11-01T01:30:00", null, null, "America/New_York", CLOCK))
                .hasMessageContaining("ambiguous because of daylight saving");
    }

    @Test
    void cronRequiresSixFieldsAndYieldsSuccessiveOccurrences() {
        var timing = ScheduleTiming.resolve(null, null, "  0 0 9 * * *  ", "UTC", CLOCK);
        assertThat(timing.mode()).isEqualTo("cron");
        assertThat(timing.recurring()).isTrue();
        assertThat(timing.cron()).isEqualTo("0 0 9 * * *");
        assertThat(timing.first(CLOCK)).isEqualTo(Instant.parse("2026-03-02T09:00:00Z"));
        assertThat(timing.nextAfter(Instant.parse("2026-03-02T09:00:00Z")))
                .isEqualTo(Instant.parse("2026-03-03T09:00:00Z"));
        assertThatThrownBy(() -> ScheduleTiming.resolve(null, null, "0 9 * * *", "UTC", CLOCK))
                .hasMessageContaining("six fields");
        assertThatThrownBy(() -> ScheduleTiming.resolve(null, null, "0 0 25 * * *", "UTC", CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduleTiming("cron", null, null, "  ", "UTC"))
                .hasMessage("Recurring timing requires only cron");
    }

    @Test
    void cronSkipsNonexistentLocalTimesAndFiresRepeatedOnesOnce() {
        var gap = new ScheduleTiming("cron", null, null, "0 30 2 * * *", "America/New_York");
        assertThat(gap.nextAfter(Instant.parse("2026-03-06T12:00:00Z")))
                .isEqualTo(Instant.parse("2026-03-07T07:30:00Z"));
        // 02:30 on 2026-03-08 does not exist; the whole gap is skipped to the next day.
        assertThat(gap.nextAfter(Instant.parse("2026-03-07T12:00:00Z")))
                .isEqualTo(Instant.parse("2026-03-09T06:30:00Z"));

        var overlap = new ScheduleTiming("cron", null, null, "0 30 1 * * *", "America/New_York");
        // 01:30 on 2026-11-01 occurs twice; only the earlier offset fires.
        assertThat(overlap.nextAfter(Instant.parse("2026-11-01T04:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
        assertThat(overlap.nextAfter(Instant.parse("2026-11-01T05:30:00Z")))
                .isEqualTo(Instant.parse("2026-11-02T06:30:00Z"));
        assertThat(overlap.nextAfter(Instant.parse("2026-11-01T06:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-02T06:30:00Z"));
    }

    @Test
    void oneTimeInstantsStayAvailableWhenOverdue() {
        var timing = new ScheduleTiming("at", "2026-03-01T11:00:00Z", null, null, "UTC");
        assertThat(timing.first(CLOCK)).isEqualTo(Instant.parse("2026-03-01T11:00:00Z"));
        assertThat(timing.nextAfter(NOW)).isNull();
        assertThat(timing.nextAfter(Instant.parse("2026-03-01T10:00:00Z")))
                .isEqualTo(Instant.parse("2026-03-01T11:00:00Z"));
    }

    @Test
    void timingRecordsAreNormalizedAndValidated() {
        var timing = new ScheduleTiming("at", "2026-03-01T11:00:00.000Z", null, null, "UTC");
        assertThat(timing.at()).isEqualTo("2026-03-01T11:00:00Z");
        assertThat(new ScheduleTiming("at", "2026-03-01T11:00:00Z", null, null, null).timezone())
                .isEqualTo(ApplicationTime.zone().getId());
        assertThatThrownBy(() -> new ScheduleTiming("at", null, null, null, "UTC"))
                .hasMessage("Absolute timing requires only at");
        assertThatThrownBy(() -> new ScheduleTiming("at", "2026-03-01T11:00:00Z", 5L, null, "UTC"))
                .hasMessage("Absolute timing requires only at");
        assertThatThrownBy(
                () -> new ScheduleTiming("cron", "2026-03-01T11:00:00Z", null, "0 0 9 * * *", "UTC"))
                .hasMessage("Recurring timing requires only cron");
        assertThatThrownBy(() -> new ScheduleTiming("weekly", null, null, null, "UTC"))
                .hasMessage("Unknown schedule timing mode");
        assertThatThrownBy(() -> new ScheduleTiming("at", "2026-03-01T11:00:00Z", null, null, "Mars/Olympus"))
                .hasMessage("timezone must be an IANA timezone such as America/New_York");
    }
}
