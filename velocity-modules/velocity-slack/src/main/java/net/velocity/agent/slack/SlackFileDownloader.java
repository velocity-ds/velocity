package net.velocity.agent.slack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import net.velocity.agent.config.UploadProperties;
import net.velocity.agent.state.UploadedFile;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches the files attached to an eligible Slack message with the bot token, bounded by the same
 * {@code agent.uploads.*} limits as console uploads. Only HTTPS Slack file hosts receive the token, no
 * redirect is followed, and neither URLs nor the token are logged. Downloaded bytes are untrusted data.
 */
public final class SlackFileDownloader {

    private static final Set<String> RESERVED = Set.of(
            "CON",
            "PRN",
            "AUX",
            "NUL",
            "COM1",
            "COM2",
            "COM3",
            "COM4",
            "COM5",
            "COM6",
            "COM7",
            "COM8",
            "COM9",
            "LPT1",
            "LPT2",
            "LPT3",
            "LPT4",
            "LPT5",
            "LPT6",
            "LPT7",
            "LPT8",
            "LPT9");
    private static final int MAX_FILENAME = 100;
    private final OkHttpClient http;
    private final String botToken;
    private final UploadProperties limits;

    /** Shares the SDK client's pools; the longer call budget covers files up to the per-file limit. */
    public SlackFileDownloader(OkHttpClient sdkClient, String botToken, UploadProperties limits) {
        this.http = Objects.requireNonNull(sdkClient).newBuilder().callTimeout(Duration.ofSeconds(120))
                .readTimeout(Duration.ofSeconds(30)).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).build();
        this.botToken = Objects.requireNonNull(botToken);
        this.limits = Objects.requireNonNull(limits);
    }

    /**
     * Checks the declared count and sizes before any request, then downloads every file in order. Nothing is
     * returned when any file is rejected or fails.
     */
    public List<UploadedFile> download(List<SlackFile> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        if (!limits.enabled()) {
            throw new SlackFileException(
                    "uploads_disabled",
                    "File attachments are disabled for this installation (agent.uploads.enabled).");
        }
        if (files.size() > limits.maxFiles()) {
            throw new SlackFileException(
                    "too_many_files",
                    "Attach at most " + limits.maxFiles() + " files to one message.");
        }
        List<String> names = filenames(files);
        long total = 0;
        for (int index = 0; index < files.size(); index++) {
            SlackFile file = files.get(index);
            String name = names.get(index);
            if (file.size() != null && file.size() > limits.maxFileBytes()) {
                throw new SlackFileException(
                        "file_too_large",
                        name + " is larger than the per-file limit of " + limits.maxFileBytes() + " bytes.");
            }
            total += file.size() == null ? 0 : Math.max(0, file.size());
            if (total > limits.maxTotalBytes()) {
                throw new SlackFileException(
                        "total_too_large",
                        "The attached files exceed the total limit of " + limits.maxTotalBytes() + " bytes.");
            }
            if (url(file) == null) {
                throw new SlackFileException(
                        "unavailable",
                        name + " cannot be downloaded from Slack (an external, hidden or deleted file).");
            }
        }
        var result = new ArrayList<UploadedFile>();
        long downloaded = 0;
        for (int index = 0; index < files.size(); index++) {
            SlackFile file = files.get(index);
            String name = names.get(index);
            byte[] bytes = fetch(url(file), name);
            downloaded += bytes.length;
            if (downloaded > limits.maxTotalBytes()) {
                throw new SlackFileException(
                        "total_too_large",
                        "The attached files exceed the total limit of " + limits.maxTotalBytes() + " bytes.");
            }
            result.add(new UploadedFile(name, file.mimetype(), bytes));
        }
        return List.copyOf(result);
    }

    private HttpUrl url(SlackFile file) {
        if (file.mode() != null
                && ("tombstone".equals(file.mode()) || "hidden_by_limit".equals(file.mode())
                        || "external".equals(file.mode()))) {
            return null;
        }
        if (file.url() == null) {
            return null;
        }
        HttpUrl url = HttpUrl.parse(file.url());
        if (url == null || !"https".equals(url.scheme()) || !slackHost(url.host())) {
            return null;
        }
        return url;
    }

    private static boolean slackHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals("slack.com") || lower.endsWith(".slack.com");
    }

    private byte[] fetch(HttpUrl url, String name) {
        var request = new Request.Builder().url(url).header("Authorization", "Bearer " + botToken).get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            int code = response.code();
            if (code == 401 || code == 403 || code >= 300 && code < 400) {
                // Slack answers an unauthorized file request with a redirect to its sign-in page.
                throw new SlackFileException(
                        "download_denied",
                        "Slack refused the download of " + name
                                + ". Check that the app has the files:read scope and access to this conversation, reinstall if the scope was added, then retry.");
            }
            if (code != 200 || response.body() == null) {
                throw new SlackFileException(
                        "download_failed",
                        "Slack did not return " + name + " (HTTP " + code + "). Please retry later.");
            }
            long declared = response.body().contentLength();
            if (declared > limits.maxFileBytes()) {
                throw new SlackFileException(
                        "file_too_large",
                        name + " is larger than the per-file limit of " + limits.maxFileBytes() + " bytes.");
            }
            byte[] bytes = readBounded(response.body().byteStream(), limits.maxFileBytes(), name);
            if (bytes.length == 0) {
                throw new SlackFileException("empty_file", name + " is empty.");
            }
            return bytes;
        } catch (IOException failure) {
            // Transport exceptions may carry the request; do not expose them as causes or messages.
            throw new SlackFileException(
                    "transport_error",
                    "The download of " + name + " from Slack did not complete. Please retry.");
        }
    }

    private static byte[] readBounded(InputStream input, long max, String name) throws IOException {
        var buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[65_536];
        long total = 0;
        int read;
        while ((read = input.read(chunk)) >= 0) {
            total += read;
            if (total > max) {
                throw new SlackFileException(
                        "file_too_large",
                        name + " is larger than the per-file limit of " + max + " bytes.");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /** Storage-safe names: ASCII letters, digits, dots, dashes and underscores, unique within the message. */
    static List<String> filenames(List<SlackFile> files) {
        var names = new ArrayList<String>();
        Set<String> seen = new HashSet<>();
        for (SlackFile file : files) {
            String name = filename(file);
            String candidate = name;
            int suffix = 2;
            while (!seen.add(candidate.toLowerCase(Locale.ROOT))) {
                candidate = withSuffix(name, "_" + suffix);
                suffix++;
            }
            names.add(candidate);
        }
        return List.copyOf(names);
    }

    static String filename(SlackFile file) {
        String raw = first(file.name(), file.title(), file.id(), "file");
        String base = raw;
        String extension = "";
        int dot = raw.lastIndexOf('.');
        if (dot > 0 && dot < raw.length() - 1) {
            String candidate = raw.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (candidate.matches("[a-z0-9]{1,10}")) {
                base = raw.substring(0, dot);
                extension = candidate;
            }
        }
        if (extension.isEmpty() && file.filetype() != null
                && file.filetype().toLowerCase(Locale.ROOT).matches("[a-z0-9]{1,10}")) {
            extension = file.filetype().toLowerCase(Locale.ROOT);
        }
        base = base.replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("\\.{2,}", ".").replaceAll("_{2,}", "_")
                .replaceAll("^[^A-Za-z0-9]+", "").replaceAll("[._-]+$", "");
        if (base.isEmpty()) {
            base = "file_" + first(file.id(), "attachment").replaceAll("[^A-Za-z0-9]", "");
        }
        if (RESERVED.contains(base.split("\\.", 2)[0].toUpperCase(Locale.ROOT))) {
            base = "slack_" + base;
        }
        String tail = extension.isEmpty() ? "" : "." + extension;
        if (base.length() + tail.length() > MAX_FILENAME) {
            base = base.substring(0, MAX_FILENAME - tail.length()).replaceAll("[._-]+$", "");
        }
        return base + tail;
    }

    private static String withSuffix(String name, String suffix) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String tail = dot > 0 ? name.substring(dot) : "";
        if (base.length() + suffix.length() + tail.length() > MAX_FILENAME) {
            base = base.substring(0, MAX_FILENAME - suffix.length() - tail.length());
        }
        return base + suffix + tail;
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }
}
