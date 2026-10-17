package net.velocity.agent.codeexecution;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Only operator configuration chooses the backend; resource ceilings are fixed in this MVP. */
@ConfigurationProperties("agent.code-execution")
public record CodeExecutionProperties(
        boolean enabled,
        String image,
        String installationId,
        String dockerExecutable) {

    public CodeExecutionProperties {
        image = image == null ? "" : image.strip();
        installationId = installationId == null ? "" : installationId.strip();
        dockerExecutable = dockerExecutable == null ? "docker" : dockerExecutable.strip();
        if (!image.isEmpty() && !image.matches("(?:[a-zA-Z0-9./:_-]+@)?sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "Code execution requires an immutable image digest or local image ID");
        }
        if (!installationId.isEmpty() && !installationId.matches("[a-z0-9][a-z0-9-]{7,63}")) {
            throw new IllegalArgumentException(
                    "Code execution installation ID must be 8–64 lowercase letters, digits or hyphens");
        }
        if (dockerExecutable.isBlank() || dockerExecutable.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid Docker executable");
        }
    }

    public boolean configured() {
        return !image.isEmpty() && !installationId.isEmpty();
    }
}
