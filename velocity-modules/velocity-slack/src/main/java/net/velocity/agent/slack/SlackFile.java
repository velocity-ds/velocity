package net.velocity.agent.slack;

/**
 * A file entry of a Slack message event, as untrusted metadata. The private URL is downloaded only once the
 * message is eligible for a task and is never logged or shown; {@link #toString()} omits it.
 */
public record SlackFile(
        String id,
        String name,
        String title,
        String filetype,
        String mimetype,
        Long size,
        String url,
        String mode) {

    @Override
    public String toString() {
        return "SlackFile[id=" + id + ", filetype=" + filetype + ", size=" + size + ", mode=" + mode + "]";
    }
}
