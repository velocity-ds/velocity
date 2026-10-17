package net.velocity.config;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import net.velocity.ApplicationTime;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

@Configuration(proxyBeanMethods = false)
public class TimeZoneConfiguration {

    @Bean
    JsonMapperBuilderCustomizer applicationTimeZone() {
        var time = new SimpleModule("application-time-zone");
        // Instant has no local zone, so setting the mapper's time zone alone is insufficient.
        time.addSerializer(Instant.class, new ValueSerializer<>() {

            @Override
            public void serialize(Instant value, JsonGenerator generator, SerializationContext context) {
                generator.writeString(
                        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value.atZone(ApplicationTime.zone())));
            }
        });
        return builder -> builder.defaultTimeZone(TimeZone.getTimeZone(ApplicationTime.zone()))
                .addModule(time);
    }
}
