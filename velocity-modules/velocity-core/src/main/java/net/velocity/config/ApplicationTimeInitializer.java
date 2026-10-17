package net.velocity.config;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import net.velocity.ApplicationTime;

/**
 * Applies {@code app.time-zone} as soon as the environment, including the imported {@code .env}, is loaded: before
 * logging starts, before Jackson or the clock bean is built and before any prompt constant reads the zone. The JVM
 * default zone is set to the same value so library code that formats dates without an explicit zone agrees with
 * the application. Registered in {@code META-INF/spring.factories}.
 */
public final class ApplicationTimeInitializer implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var zone = ApplicationTime.parse(environment.getProperty(ApplicationTime.PROPERTY));
        ApplicationTime.configure(zone);
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
