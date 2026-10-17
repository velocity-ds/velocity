package net.velocity.agent.codeexecution;

import java.util.List;
import java.util.Optional;

public interface CodeArtifactRepository {

    CodeArtifact save(CodeArtifact metadata, byte[] bytes);

    Optional<CodeArtifact> find(String artifactId);

    byte[] bytes(String artifactId);

    List<CodeArtifact> listExecution(String executionId, int limit);

    void deleteExecution(String executionId);
}
