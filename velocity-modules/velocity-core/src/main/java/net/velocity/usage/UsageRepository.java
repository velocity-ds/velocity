package net.velocity.usage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface UsageRepository {

    void insert(UsageRecord record);

    Optional<UsageRecord> find(String requestId);

    boolean replace(UsageRecord record, long expectedRevision);

    List<UsageReports.Breakdown> aggregate(Filter filter);

    Set<String> sourceRequestKeys(String ownerAccountId, String rootExecutionId);

    record Filter(
            String ownerAccountId,
            Instant from,
            Instant toExclusive,
            String rootExecutionId,
            String provider,
            String model,
            String activity) {}
}
