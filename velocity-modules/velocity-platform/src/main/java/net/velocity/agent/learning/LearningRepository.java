package net.velocity.agent.learning;

import java.util.List;
import java.util.Optional;

public interface LearningRepository {

    Optional<LearningState> find(String ownerAccountId);

    List<LearningState> pending();

    boolean save(LearningState state, long expectedRevision);
}
