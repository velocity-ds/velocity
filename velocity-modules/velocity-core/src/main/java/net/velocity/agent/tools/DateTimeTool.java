package net.velocity.agent.tools;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import net.velocity.ApplicationTime;
import net.velocity.agent.ToolRegistry;
import net.velocity.inference.SystemPrompts;

/** Reads the current instant once so all returned fields describe the same time. */
public final class DateTimeTool {

    private DateTimeTool() {
    }

    public static ToolRegistry.Definition definition(Clock clock) {
        return new ToolRegistry.Definition(
                "date_time",
                SystemPrompts.DATE_TIME_DESCRIPTION,
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "timezone",
                                Map.of(
                                        "type",
                                        "string",
                                        "minLength",
                                        1,
                                        "description",
                                        SystemPrompts.DATE_TIME_TIMEZONE_DESCRIPTION)),
                        "additionalProperties",
                        false),
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "date_time",
                                Map.of("type", "string"),
                                "date",
                                Map.of("type", "string"),
                                "time",
                                Map.of("type", "string"),
                                "timezone",
                                Map.of("type", "string"),
                                "day_of_week",
                                Map.of("type", "string"),
                                "unix_timestamp",
                                Map.of("type", "integer")),
                        "required",
                        List.of("date_time", "date", "time", "timezone", "day_of_week", "unix_timestamp"),
                        "additionalProperties",
                        false),
                "system.read",
                true,
                false,
                false,
                0,
                (context, input) -> {
                    var zone = ZoneId
                            .of((String) input.getOrDefault("timezone", ApplicationTime.zone().getId()));
                    var now = clock.instant().atZone(zone);
                    return Map.of(
                            "date_time",
                            now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                            "date",
                            now.toLocalDate().toString(),
                            "time",
                            now.format(DateTimeFormatter.ISO_LOCAL_TIME),
                            "timezone",
                            zone.getId(),
                            "day_of_week",
                            now.getDayOfWeek().name(),
                            "unix_timestamp",
                            now.toEpochSecond());
                });
    }
}
