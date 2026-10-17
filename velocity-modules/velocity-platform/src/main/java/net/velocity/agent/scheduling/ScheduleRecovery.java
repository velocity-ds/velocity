package net.velocity.agent.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** A local bootstrap retry repairs a queue outage before the durable reconciliation job exists. */
public final class ScheduleRecovery implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ScheduleRecovery.class);
    private final BootstrapRecovery bootstrap;

    public ScheduleRecovery(ScheduleJobs jobs, boolean enabled) {
        bootstrap = new BootstrapRecovery(enabled, "schedule-recovery", () -> {
            if (!jobs.available()) {
                return false;
            }
            jobs.reconcilePeriodically();
            return true;
        },
                failure -> log.warn(
                        "Schedule reconciliation registration will retry ({})",
                        failure.getClass().getSimpleName()));
    }

    @Override
    public void start() {
        bootstrap.start();
    }

    @Override
    public void stop() {
        bootstrap.stop();
    }

    @Override
    public boolean isRunning() {
        return bootstrap.isRunning();
    }
}
