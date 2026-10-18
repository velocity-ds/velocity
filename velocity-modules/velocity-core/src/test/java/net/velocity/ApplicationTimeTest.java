package net.velocity;

import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationTimeTest {

    @Test
    void blankConfigurationMeansTheJvmDefaultZone() {
        assertThat(ApplicationTime.parse(null)).isEqualTo(ZoneId.systemDefault());
        assertThat(ApplicationTime.parse("   ")).isEqualTo(ZoneId.systemDefault());
    }

    @Test
    void parsesTrimmedIanaZoneIds() {
        assertThat(ApplicationTime.parse(" Europe/Berlin ")).isEqualTo(ZoneId.of("Europe/Berlin"));
        assertThat(ApplicationTime.parse("UTC")).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void unknownZoneFailsExplicitlyNamingTheProperty() {
        assertThatThrownBy(() -> ApplicationTime.parse("Mars/Olympus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ApplicationTime.PROPERTY)
                .hasMessageContaining("Mars/Olympus");
    }

    @Test
    void configureReplacesTheZoneAndRejectsNull() {
        ZoneId previous = ApplicationTime.zone();
        try {
            ApplicationTime.configure(ZoneId.of("Asia/Tokyo"));
            assertThat(ApplicationTime.zone()).isEqualTo(ZoneId.of("Asia/Tokyo"));
            assertThatThrownBy(() -> ApplicationTime.configure(null))
                    .isInstanceOf(NullPointerException.class);
        } finally {
            ApplicationTime.configure(previous);
        }
    }
}
