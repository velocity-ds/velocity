package net.velocity;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Objects;

/**
 * The installation's time zone: the zone of the application clock, of timestamps shown to people, of schedule
 * and monitoring defaults, and of every prompt that names a zone. {@link net.velocity.config.ApplicationTimeInitializer}
 * configures it once from {@code app.time-zone} while the environment is prepared, before logging, JSON mapping or
 * any bean reads it; afterwards it is read as a constant. An unset property means the JVM's default zone.
 */
public final class ApplicationTime {

    public static final String PROPERTY = "app.time-zone";

    private static volatile ZoneId zone = ZoneId.systemDefault();

    private ApplicationTime() {
    }

    public static ZoneId zone() {
        return zone;
    }

    /** Sets the application zone. Called once during environment preparation, before any bean is created. */
    public static void configure(ZoneId value) {
        zone = Objects.requireNonNull(value, "Application time zone");
    }

    /** Parses a configured zone ID: blank means the JVM default, and an unknown ID fails startup explicitly. */
    public static ZoneId parse(String configured) {
        if (configured == null || configured.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(configured.trim());
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException(
                    PROPERTY + " must be an IANA zone ID such as UTC or Europe/Berlin, not '" + configured
                            + "'",
                    invalid);
        }
    }
}
