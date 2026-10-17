package net.velocity.agent.skills;

import java.util.Optional;

public interface SkillRepository {

    Optional<SkillStore> find(String ownerAccountId);

    /** expectedRevision zero inserts; a conflict never replaces existing state. */
    boolean compareAndSet(SkillStore replacement, long expectedRevision);
}
