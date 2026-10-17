package net.velocity.agent.research;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.velocity.agent.CancellationHandle;
import net.velocity.inference.InferenceDeadlineScope;

/** Local Poppler text extraction and rasterization with no shell, source URLs, inherited credentials, or startup process. */
public final class PopplerPdfPageRenderer implements PdfPageRenderer {

    private static final int MAX_LOG_BYTES = 65536;
    private static final Pattern PAGES = Pattern.compile("(?m)^Pages:[ \\t]+([0-9]+)[ \\t]*\\r?$");
    private static final Pattern ENCRYPTED = Pattern
            .compile("(?m)^Encrypted:[ \\t]+(yes|no)(?:[ \\t]+[^\\r\\n]*)?\\r?$");
    private static final byte[] PNG = new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private final FileProcessingProperties properties;
    private final ProcessLauncher launcher;
    private final Path temporaryParent;

    public PopplerPdfPageRenderer(FileProcessingProperties properties) {
        this(properties, PopplerPdfPageRenderer::launch, null);
    }

    PopplerPdfPageRenderer(
            FileProcessingProperties properties,
            ProcessLauncher launcher,
            Path temporaryParent) {
        this.properties = Objects.requireNonNull(properties);
        this.launcher = Objects.requireNonNull(launcher);
        this.temporaryParent = temporaryParent;
    }

    @Override
    public PdfPageRenderer.Document open(byte[] bytes, CancellationHandle cancellation, long deadlineNanos)
            throws Exception {
        Objects.requireNonNull(bytes);
        Objects.requireNonNull(cancellation);
        long now = System.nanoTime();
        long available = deadlineNanos == Long.MAX_VALUE ? Long.MAX_VALUE : deadlineNanos - now;
        if (available <= 0) {
            throw problem("partial", "PDF processing deadline reached before rendering");
        }
        long effectiveDeadline = now + Math.min(
                available,
                InferenceDeadlineScope.remainingOr(properties.timeout()).toNanos());
        check(cancellation, effectiveDeadline);
        Path directory = temporaryParent == null
                ? Files.createTempDirectory("velocity-pdf-")
                : Files.createTempDirectory(temporaryParent, "velocity-pdf-");
        var document = new RenderedDocument(
                directory.toAbsolutePath().normalize(),
                cancellation,
                effectiveDeadline);
        boolean opened = false;
        try {
            Files.write(document.input, bytes);
            ProcessResult result = run(
                    List.of(properties.pdfinfoCommand(), "-enc", "UTF-8", document.input.toString()),
                    document.directory,
                    null,
                    cancellation,
                    effectiveDeadline);
            if (result.exitCode() != 0) {
                if (result.log().toLowerCase(Locale.ROOT).contains("password")) {
                    throw problem("encrypted", "Encrypted PDF requires credentials and was not rendered");
                }
                throw problem(
                        "unavailable",
                        "PDF metadata could not be read; the document may be malformed or unsupported");
            }
            String count = field(PAGES, result.log());
            String encrypted = field(ENCRYPTED, result.log());
            try {
                document.pages = Integer.parseInt(count);
            } catch (NumberFormatException invalid) {
                throw problem("unavailable", "PDF page count is unavailable or invalid");
            }
            if (document.pages < 1) {
                throw problem("unavailable", "PDF contains no readable pages");
            }
            if (!Set.of("yes", "no").contains(encrypted)) {
                throw problem("unavailable", "PDF encryption status could not be established");
            }
            document.encrypted = encrypted.equals("yes");
            opened = true;
            return document;
        } finally {
            if (!opened) {
                document.close();
            }
        }
    }

    private final class RenderedDocument implements PdfPageRenderer.Document {

        private final Path directory;
        private final Path input;
        private final CancellationHandle cancellation;
        private final long deadline;
        private int pages;
        private boolean encrypted;
        private boolean closed;

        private RenderedDocument(Path directory, CancellationHandle cancellation, long deadline) {
            this.directory = directory;
            this.input = directory.resolve("document.pdf");
            this.cancellation = cancellation;
            this.deadline = deadline;
        }

        @Override
        public int pageCount() {
            return pages;
        }

        @Override
        public boolean encrypted() {
            return encrypted;
        }

        @Override
        public synchronized byte[] render(int page) throws Exception {
            if (closed) {
                throw problem("unavailable", "PDF rendering document has closed");
            }
            check(cancellation, deadline);
            if (encrypted) {
                throw problem("encrypted", "Encrypted PDF is not rendered");
            }
            if (page < 1 || page > pages) {
                throw problem("unavailable", "Requested PDF page does not exist");
            }
            if (page > properties.maxUnits()) {
                throw problem("partial", "PDF page rendering limit reached");
            }
            Path prefix = directory.resolve("page-" + page);
            Path image = directory.resolve("page-" + page + ".png");
            try {
                ProcessResult result = run(
                        List.of(
                                properties.renderCommand(),
                                "-f",
                                Integer.toString(page),
                                "-l",
                                Integer.toString(page),
                                "-singlefile",
                                "-png",
                                "-hide-annotations",
                                "-scale-to",
                                Integer.toString(properties.pageDimension()),
                                input.toString(),
                                prefix.toString()),
                        directory,
                        image,
                        cancellation,
                        deadline);
                if (result.exitCode() != 0) {
                    throw problem("unavailable", "PDF page could not be rendered");
                }
                if (!Files.isRegularFile(image, LinkOption.NOFOLLOW_LINKS)) {
                    throw problem("unavailable", "PDF renderer did not produce a page image");
                }
                if (Files.size(image) > properties.maxPageImageBytes()) {
                    throw problem("partial", "PDF page image exceeds its byte limit");
                }
                byte[] bytes;
                try (InputStream stream = Files.newInputStream(image)) {
                    bytes = stream.readNBytes(properties.maxPageImageBytes() + 1);
                }
                if (bytes.length > properties.maxPageImageBytes()) {
                    throw problem("partial", "PDF page image exceeds its byte limit");
                }
                validatePng(bytes);
                check(cancellation, deadline);
                return bytes;
            } finally {
                Files.deleteIfExists(image);
            }
        }

        @Override
        public synchronized String text(int maxChars) throws Exception {
            if (closed) {
                throw problem("unavailable", "PDF rendering document has closed");
            }
            if (maxChars < 1) {
                throw new IllegalArgumentException("PDF text limit must be positive");
            }
            check(cancellation, deadline);
            if (encrypted) {
                throw problem("encrypted", "Encrypted PDF text is not extracted");
            }
            Path output = directory.resolve("text.txt");
            try {
                // Output goes to a file: the merged diagnostic log is bounded far below a document's text.
                ProcessResult result = run(
                        List.of(
                                properties.pdftotextCommand(),
                                "-layout",
                                "-enc",
                                "UTF-8",
                                "-f",
                                "1",
                                "-l",
                                Integer.toString(Math.min(pages, properties.maxUnits())),
                                input.toString(),
                                output.toString()),
                        directory,
                        null,
                        cancellation,
                        deadline);
                if (result.exitCode() != 0) {
                    throw problem("unavailable", "PDF text layer could not be extracted");
                }
                if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
                    throw problem("unavailable", "PDF text extractor did not produce output");
                }
                int maxBytes = (int) Math.min(Integer.MAX_VALUE - 8L, maxChars * 4L);
                byte[] bytes;
                try (InputStream stream = Files.newInputStream(output)) {
                    bytes = stream.readNBytes(maxBytes);
                }
                check(cancellation, deadline);
                String text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
                if (text.length() > maxChars) {
                    int end = maxChars;
                    if (Character.isHighSurrogate(text.charAt(end - 1))) {
                        end--;
                    }
                    text = text.substring(0, end);
                }
                return text;
            } finally {
                Files.deleteIfExists(output);
            }
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            // run() always settles the process and log-drain thread before this directory can be removed.
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(directory)) {
                        throw new IOException("Unexpected temporary path");
                    }
                    Files.deleteIfExists(path);
                }
            } catch (IOException failure) {
                throw problem(
                        "unavailable",
                        "PDF temporary files could not be removed after process settlement");
            }
        }
    }

    private ProcessResult run(
            List<String> command,
            Path directory,
            Path image,
            CancellationHandle cancellation,
            long deadline) throws Exception {
        check(cancellation, deadline);
        Process process;
        try {
            process = launcher.start(List.copyOf(command), directory);
        } catch (IOException unavailable) {
            throw problem(
                    "unavailable",
                    "PDF tools are unavailable; configure the pdfinfo, pdftoppm and pdftotext executable paths");
        }
        BoundedLog log = new BoundedLog(process.getInputStream());
        Thread reader = Thread.ofVirtual().name("velocity-pdf-log").start(log);
        try {
            while (true) {
                check(cancellation, deadline);
                if (log.overflow.get()) {
                    throw problem("partial", "PDF renderer diagnostic output exceeds its limit");
                }
                if (image != null && Files.exists(image, LinkOption.NOFOLLOW_LINKS)
                        && Files.size(image) > properties.maxPageImageBytes()) {
                    throw problem("partial", "PDF page image exceeds its byte limit");
                }
                long remainingMillis = Math
                        .max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                if (process.waitFor(Math.min(50, remainingMillis), TimeUnit.MILLISECONDS)) {
                    break;
                }
            }
            while (reader.isAlive()) {
                check(cancellation, deadline);
                reader.join(20);
            }
            check(cancellation, deadline);
            if (log.overflow.get()) {
                throw problem("partial", "PDF renderer diagnostic output exceeds its limit");
            }
            if (log.failure.get()) {
                throw problem("unavailable", "PDF renderer diagnostic stream could not be read");
            }
            return new ProcessResult(process.exitValue(), log.text());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("PDF rendering was interrupted");
        } finally {
            settle(process, reader);
        }
    }

    private static Process launch(List<String> command, Path directory) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true);
        // Keep only operating-system/runtime discovery settings. API keys, tokens, cookies and
        // application configuration are not inherited by the child process.
        Map<String, String> inherited = Map.copyOf(builder.environment());
        builder.environment().clear();
        Set<String> permitted = Set.of(
                "PATH",
                "SYSTEMROOT",
                "WINDIR",
                "SYSTEMDRIVE",
                "TEMP",
                "TMP",
                "HOME",
                "USERPROFILE",
                "LOCALAPPDATA",
                "APPDATA",
                "LD_LIBRARY_PATH",
                "DYLD_LIBRARY_PATH",
                "FONTCONFIG_PATH",
                "FONTCONFIG_FILE");
        inherited.forEach((key, value) -> {
            if (permitted.contains(key.toUpperCase(Locale.ROOT))) {
                builder.environment().put(key, value);
            }
        });
        builder.environment().put("LC_ALL", "C");
        builder.environment().put("LANG", "C");
        return builder.start();
    }

    /** Never equate interruption/request timeout with actual subprocess settlement. */
    private static void settle(Process process, Thread reader) {
        boolean interrupted = Thread.interrupted();
        List<ProcessHandle> descendants = new ArrayList<>();
        try {
            if (process.isAlive()) {
                try {
                    descendants.addAll(process.descendants().toList());
                } catch (UnsupportedOperationException ignored) {
                }
                descendants.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            while (process.isAlive()) {
                try {
                    process.waitFor(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException again) {
                    interrupted = true;
                }
            }
            for (ProcessHandle child : descendants) {
                while (child.isAlive()) {
                    child.destroyForcibly();
                    try {
                        child.onExit().get(50, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException again) {
                        interrupted = true;
                    } catch (java.util.concurrent.TimeoutException pending) {
                        /* Continue until native settlement. */ } catch (java.util.concurrent.ExecutionException unavailable) {
                        /* Recheck actual liveness. */ }
                }
            }
            try {
                process.getInputStream().close();
            } catch (IOException ignored) {
            }
            try {
                process.getErrorStream().close();
            } catch (IOException ignored) {
            }
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
            }
            while (reader.isAlive()) {
                try {
                    reader.join(50);
                } catch (InterruptedException again) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void validatePng(byte[] bytes) {
        if (bytes.length < 33) {
            throw problem("unavailable", "PDF renderer returned an invalid PNG image");
        }
        for (int i = 0; i < PNG.length; i++) {
            if (bytes[i] != PNG[i]) {
                throw problem("unavailable", "PDF renderer returned an invalid PNG image");
            }
        }
        ByteBuffer header = ByteBuffer.wrap(bytes);
        if (header.getInt(8) != 13 || header.getInt(12) != 0x49484452) {
            throw problem("unavailable", "PDF renderer returned an invalid PNG header");
        }
        int width = header.getInt(16), height = header.getInt(20);
        if (width < 1 || height < 1 || width > properties.pageDimension()
                || height > properties.pageDimension()) {
            throw problem("partial", "PDF page image dimensions exceed their limit");
        }
    }

    private static String field(Pattern pattern, String text) {
        Matcher match = pattern.matcher(text);
        if (!match.find()) {
            return "";
        }
        String value = match.group(1);
        return match.find() ? "" : value;
    }

    private static void check(CancellationHandle cancellation, long deadline) {
        cancellation.check();
        if (deadline - System.nanoTime() <= 0) {
            throw problem("partial", "PDF processing deadline reached");
        }
    }

    private static PdfRenderException problem(String status, String message) {
        return new PdfRenderException(status, message);
    }

    @FunctionalInterface
    interface ProcessLauncher {

        Process start(List<String> command, Path directory) throws IOException;
    }

    private record ProcessResult(int exitCode, String log) {}

    private static final class BoundedLog implements Runnable {

        private final InputStream input;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final AtomicBoolean overflow = new AtomicBoolean();
        private final AtomicBoolean failure = new AtomicBoolean();

        private BoundedLog(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            try (input) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    int room = MAX_LOG_BYTES - bytes.size();
                    if (read > room) {
                        overflow.set(true);
                    }
                    bytes.write(buffer, 0, Math.min(read, room));
                    if (overflow.get()) {
                        break;
                    }
                }
            } catch (IOException unreadable) {
                failure.set(true);
            }
        }

        private String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }
}
