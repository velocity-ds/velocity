package net.velocity.agent.scheduling;

import java.util.List;

/** Detached snapshots; the single-process schedule service serializes revision checks and saves. */
public interface ScheduleRepository {

    List<ScheduleRecord> list();

    ScheduleRecord get(String id);

    void save(ScheduleRecord schedule);
}
