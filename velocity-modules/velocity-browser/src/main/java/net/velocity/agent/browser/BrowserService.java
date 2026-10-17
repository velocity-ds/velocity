package net.velocity.agent.browser;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;

import net.velocity.agent.AuthorizationPolicy;
import net.velocity.agent.CallProvenance;
import net.velocity.agent.CancellationHandle;
import net.velocity.agent.ToolRegistry;
import net.velocity.agent.persistence.ExecutionRepository;
import net.velocity.agent.persistence.NotFoundException;
import net.velocity.agent.secrets.CredentialService;
import net.velocity.agent.security.BrowserDestinationPolicy;
import net.velocity.agent.security.BrowserRejectedException;
import net.velocity.agent.state.ExecutionLimits;
import net.velocity.agent.state.TrustedScope;

/** Execution-owned adapter for Browser Use actions and trusted rendered page reads. */
public final class BrowserService implements AutoCloseable {

    public static final String VERSION = BrowserUseSession.VERSION;
    public static final int MAX_SOURCE_CHARS = 500_000;
    /** Native advertised tools; browser_read_page is implemented by Java over a private read. */
    public static final Set<String> SUPPORTED_TOOLS = Set.of(
            "browser_navigate",
            "browser_state",
            "browser_click",
            "browser_type",
            "browser_scroll",
            "browser_navigate_back",
            "browser_press_key",
            "browser_select_option",
            "browser_tabs",
            "browser_wait_for",
            "browser_close");
    private static final JsonMapper JSON = new JsonMapper();
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\\"\\x60]+");

    public interface Session extends AutoCloseable {

        Map<String, Object> call(String tool, Map<String, Object> input);

        default Map<String, Object> call(String tool, Map<String, Object> input, long remainingMillis) {
            return call(tool, input);
        }

        boolean alive();

        default BrowserNetworkGuard networkGuard() {
            return null;
        }

        @Override
        void close();
    }

    @FunctionalInterface
    public interface SessionOperation<T> {

        T run(SessionView session) throws Exception;
    }

    public record ElementBinding(
            int backendNodeId,
            String frameId,
            String targetId,
            boolean mainFrame,
            String tag,
            String type) {}

    public record CapturedSource(
            String pageUrl,
            String title,
            String text,
            String coverage,
            Instant observedAt,
            String linkUrl,
            String linkTitle,
            Map<String, String> provenance) {

        public CapturedSource {
            provenance = Map.copyOf(provenance);
        }
    }

    private final BrowserDestinationPolicy policy;
    private final Clock clock;
    private final ExecutionRepository repository;
    private final BiFunction<Integer, ToolRegistry.Context, Session> factory;
    private final BrowserCredentialOperations credentials;
    private final ConcurrentMap<String, OwnedSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService owner;
    private final ScheduledExecutorService maintenance;
    private final ThreadLocal<AtomicInteger> commandDispatch = new ThreadLocal<>();
    private final ThreadLocal<ToolRegistry.Context> commandContext = new ThreadLocal<>();
    private volatile boolean closing;

    public BrowserService(
            BrowserProperties properties,
            BrowserDestinationPolicy policy,
            Clock clock,
            ExecutionRepository repository,
            CredentialService credentials) {
        this(
                policy,
                clock,
                repository,
                (limit, context) -> new BrowserUseSession(properties, policy, limit, context),
                credentials);
    }

    BrowserService(
            BrowserDestinationPolicy policy,
            Clock clock,
            ExecutionRepository repository,
            IntFunction<Session> factory,
            CredentialService credentials) {
        this(policy, clock, repository, (limit, context) -> factory.apply(limit), credentials);
    }

    BrowserService(
            BrowserDestinationPolicy policy,
            Clock clock,
            ExecutionRepository repository,
            BiFunction<Integer, ToolRegistry.Context, Session> factory,
            CredentialService credentials) {
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
        this.repository = repository;
        this.factory = Objects.requireNonNull(factory);
        this.credentials = new BrowserCredentialOperations(this, repository, credentials);
        owner = Executors
                .newSingleThreadExecutor(
                        Thread.ofPlatform().daemon().name("velocity-browser-owner").factory());
        maintenance = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("velocity-browser-expiry").factory());
        maintenance.scheduleWithFixedDelay(this::reap, 1, 1, TimeUnit.SECONDS);
    }

    public void setCredentialAuthorization(AuthorizationPolicy authorization, ToolRegistry registry) {
        credentials.setAuthorization(authorization, registry);
    }

    public Map<String, Object> findCredential(ToolRegistry.Context context, Map<String, Object> input)
            throws Exception {
        return credentials.find(context, input);
    }

    public Map<String, Object> fillCredential(ToolRegistry.Context context, Map<String, Object> input)
            throws Exception {
        return credentials.fill(context, input);
    }

    public Map<String, Object> submitLogin(ToolRegistry.Context context, Map<String, Object> input)
            throws Exception {
        return credentials.submit(context, input);
    }

    public Map<String, Object> call(ToolRegistry.Context context, String tool, Map<String, Object> input)
            throws Exception {
        BrowserTools.validateInput(tool, input);
        return command(context, () -> {
            if (input.get("url") instanceof String url) {
                policy.requireAllowed(url);
            }
            OwnedSession entry = tool.equals("browser_navigate") && !input.containsKey("session_id")
                    ? create(context)
                    : owned(context, input);
            if (Set.of("browser_click", "browser_type", "browser_select_option").contains(tool)
                    && !Objects.equals(entry.snapshotId, input.get("state_id"))) {
                throw rejected("stale_state", "Read a fresh browser_state before interacting.");
            }
            var args = new LinkedHashMap<>(input);
            args.remove("session_id");
            args.remove("generation");
            args.remove("state_id");
            var view = new SessionView(entry);
            try {
                if (tool.equals("browser_read_page")) {
                    return readPage(context, entry, input);
                }
                Map<String, Object> result;
                requireLiveCommand(context);
                result = view.callRaw(tool, args);
                if (refreshAfter(tool, args) && "ok".equals(result.get("status"))) {
                    result = refreshAfterAction(context, view, result);
                }
                return identified(context, entry, result);
            } catch (ToolRegistry.UncertainToolException uncertain) {
                remove(entry);
                throw uncertain;
            } catch (CancellationException cancelled) {
                remove(entry);
                throw cancelled;
            } finally {
                if (tool.equals("browser_close")) {
                    remove(entry);
                }
            }
        });
    }

    private static boolean refreshAfter(String tool, Map<String, Object> args) {
        return !tool.equals("browser_state") && !tool.equals("browser_close")
                && !(tool.equals("browser_tabs") && "list".equals(args.get("action")));
    }

    private Map<String, Object> refreshAfterAction(
            ToolRegistry.Context context,
            SessionView view,
            Map<String, Object> action) {
        requireLiveCommand(context);
        requireCurrentChildCall(context);
        var completed = new LinkedHashMap<>(action);
        completed.put("action_outcome", "completed");
        try {
            var state = view.callRaw("browser_state", Map.of());
            requireLiveCommand(context);
            if ("ok".equals(state.get("status")) && !Boolean.TRUE.equals(state.get("is_error"))) {
                completed.putAll(state);
                return completed;
            }
        } catch (BrowserRejectedException | ToolRegistry.UncertainToolException unavailable) {
            // The action was confirmed before this separate read. Never replay it because the read failed.
        }
        invalidateSnapshot(view.entry);
        completed.put("code", "state_unavailable");
        completed.put("message", BrowserPrompts.BROWSER_ACTION_STATE_UNAVAILABLE);
        completed.put("recovery", List.of("browser_state", "report_blocker"));
        return completed;
    }

    /** PNG pixels of the owned current page. Bytes go to the requester; only metadata is model-visible. */
    public record Screenshot(
            byte[] bytes,
            String pageUrl,
            String title,
            String sessionId,
            String generation) {

        public Screenshot {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public static final int MAX_SCREENSHOT_BYTES = 10 * 1024 * 1024;

    /**
     * Captures the current page of an owned session as a PNG within {@code maxBytes}. The capture is a read:
     * retained state stays valid. Pages whose origin received a saved credential are refused because pixels
     * cannot be redacted.
     */
    public Screenshot screenshot(
            ToolRegistry.Context context,
            Map<String, Object> binding,
            boolean fullPage,
            int maxBytes) throws Exception {
        return withSession(context, binding, view -> {
            var entry = view.entry;
            int limit = Math.min(Math.max(1, maxBytes), MAX_SCREENSHOT_BYTES);
            requireLiveCommand(context);
            Map<String, Object> raw;
            try {
                raw = nativeCall(
                        entry,
                        "velocity_screenshot",
                        Map.of("full_page", fullPage, "max_bytes", limit));
            } catch (BrowserRejectedException | ToolRegistry.UncertainToolException rejected) {
                throw rejected;
            } catch (CancellationException cancelled) {
                remove(entry);
                throw cancelled;
            } catch (RuntimeException failed) {
                remove(entry);
                throw rejected("screenshot_unavailable", "No screenshot was captured; the session was lost.");
            }
            if (raw == null || !"ok".equals(raw.get("status"))
                    || !Set.of("status", "url", "title", "image_format", "image_base64").equals(raw.keySet())
                    || !"png".equals(raw.get("image_format")) || !(raw.get("url") instanceof String url)
                    || url.length() > 8192 || !(raw.get("title") instanceof String title)
                    || title.length() > 4096 || !(raw.get("image_base64") instanceof String encoded)
                    || encoded.isEmpty()) {
                throw rejected("screenshot_unavailable", "No valid screenshot was returned.");
            }
            byte[] bytes;
            try {
                bytes = java.util.Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException invalid) {
                throw rejected("screenshot_unavailable", "The screenshot payload was not valid.");
            }
            if (bytes.length == 0 || bytes.length > limit || !png(bytes)) {
                throw rejected(
                        "screenshot_unavailable",
                        "The screenshot is not a PNG within the configured byte limit.");
            }
            policy.requireAllowed(url);
            if (entry.credentialProtected && entry.credentialOrigins.contains(originOf(url))) {
                throw rejected(
                        "credential_capture_denied",
                        "Pages that received a saved credential cannot be captured.");
            }
            entry.redactor.rememberUrlTokens(url);
            String safeTitle = clean(entry, entry.credentialRedactor.cleanKnownValues(title));
            context.cancellation().check();
            requireCurrentChildCall(context);
            return new Screenshot(
                    bytes,
                    publicUrl(entry, url),
                    prefix(safeTitle, Math.min(safeTitle.length(), 4096)),
                    entry.id,
                    entry.generation);
        });
    }

    private static boolean png(byte[] bytes) {
        return bytes.length > 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G';
    }

    /** One normalization for recording and checking credential origins, so a format drift cannot fail open. */
    private static String originOf(String url) {
        try {
            var uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            return scheme + "://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (IllegalArgumentException invalid) {
            return url;
        }
    }

    /** Trusted credential/capture orchestration stays inside the same serialization and settlement. */
    public <T> T withSession(
            ToolRegistry.Context context,
            Map<String, Object> binding,
            SessionOperation<T> operation) throws Exception {
        return command(context, () -> operation.run(new SessionView(owned(context, binding))));
    }

    public final class SessionView {

        private final OwnedSession entry;

        private SessionView(OwnedSession entry) {
            this.entry = entry;
        }

        public String id() {
            return entry.id;
        }

        public String generation() {
            return entry.generation;
        }

        public CapturedSource lastSnapshot() {
            return entry.snapshot;
        }

        public String lastSnapshotId() {
            return entry.snapshotId;
        }

        public BrowserNetworkGuard networkGuard() {
            return entry.session.networkGuard();
        }

        public ElementBinding element(int index) {
            var element = entry.elements.get(index);
            if (element == null) {
                throw rejected("stale_target", "Use an element from the latest complete state.");
            }
            return element;
        }

        public int elementIndex(ElementBinding binding) {
            var matches = entry.elements.entrySet().stream().filter(item -> item.getValue().equals(binding))
                    .toList();
            if (matches.size() != 1) {
                throw rejected("stale_target", "The element fingerprint is no longer current.");
            }
            return matches.getFirst().getKey();
        }

        public void rememberSecret(String secret) {
            entry.pageRead = null;
            entry.credentialProtected = true;
            entry.redactor.rememberRequired(secret);
            entry.credentialRedactor.rememberRequired(secret);
        }

        public void fillPrivate(String origin, List<Map<String, Object>> fields) {
            policy.requireAllowed(origin);
            entry.credentialOrigins.add(originOf(origin));
            invalidateSnapshot(entry);
            try {
                var raw = nativeCall(
                        entry,
                        "browser_fill_private",
                        Map.of("origin", origin, "fields", fields));
                if (raw == null || !"ok".equals(raw.get("status"))) {
                    throw uncertain("private_fill_failed");
                }
            } catch (ToolRegistry.UncertainToolException uncertain) {
                remove(entry);
                throw uncertain;
            } catch (RuntimeException failed) {
                remove(entry);
                throw uncertain("private_fill_failed");
            }
        }

        public Map<String, Object> callRaw(String tool, Map<String, Object> args) {
            if (!SUPPORTED_TOOLS.contains(tool)) {
                throw rejected("unsupported_tool", "This browser tool is unavailable.");
            }
            if (args.containsKey("index") && !entry.elements.containsKey(integer(args.get("index")))) {
                throw rejected("stale_target", "Use an index from the current complete state.");
            }
            if (args.get("url") instanceof String url) {
                policy.requireAllowed(url);
                entry.redactor.rememberUrlTokens(url);
            }
            boolean read = tool.equals("browser_state") || tool.equals("browser_wait_for")
                    || tool.equals("browser_tabs") && "list".equals(args.get("action"));
            invalidateSnapshot(entry);
            try {
                var raw = nativeCall(entry, tool, args);
                if (raw == null || !"ok".equals(raw.get("status"))) {
                    if (!read) {
                        throw uncertain("native_error");
                    }
                    return error("native_error");
                }
                if (tool.equals("browser_state")) {
                    return retainState(entry, raw);
                }
                var result = output("ok");
                if (raw.containsKey("tabs")) {
                    var tabs = tabs(entry, raw.get("tabs"));
                    result.put("tabs", tabs.items());
                    result.put("truncated", Boolean.TRUE.equals(raw.get("truncated")) || tabs.truncated());
                }
                return result;
            } catch (BrowserRejectedException rejected) {
                throw rejected;
            } catch (ToolRegistry.UncertainToolException uncertain) {
                remove(entry);
                throw uncertain;
            } catch (RuntimeException failed) {
                remove(entry);
                if (!read) {
                    throw uncertain("native_failure");
                }
                return error("state_unavailable");
            }
        }
    }

    public Map<String, Object> capture(
            ToolRegistry.Context context,
            Map<String, Object> input,
            Function<CapturedSource, Map<String, Object>> persist) throws Exception {
        return withSession(context, input, view -> {
            var entry = view.entry;
            if (entry.credentialProtected) {
                throw rejected(
                        "credential_capture_denied",
                        "Saved credential browser sessions cannot become research evidence.");
            }
            if (entry.snapshot == null || !entry.snapshotId.equals(input.get("state_id"))) {
                throw rejected("stale_state", "Capture requires the current Browser Use state.");
            }
            String linkUrl = null, linkTitle = null;
            if (input.containsKey("link_index")) {
                var link = entry.links.get(integer(input.get("link_index")));
                if (link == null) {
                    throw rejected(
                            "stale_link",
                            "Use an observed main-frame link from the current complete state.");
                }
                linkUrl = URI.create(entry.snapshot.pageUrl()).resolve(link.url).toString();
                linkTitle = clean(entry, link.title);
                policy.requireAllowed(linkUrl);
            }
            context.cancellation().check();
            requireCurrentChildCall(context);
            var capture = entry.snapshot;
            return persist.apply(
                    new CapturedSource(
                            capture.pageUrl(),
                            capture.title(),
                            capture.text(),
                            capture.coverage(),
                            capture.observedAt(),
                            linkUrl,
                            linkTitle,
                            capture.provenance()));
        });
    }

    private Map<String, Object> readPage(
            ToolRegistry.Context context,
            OwnedSession entry,
            Map<String, Object> input) {
        requireLiveCommand(context);
        requireCurrentChildCall(context);
        PageRead reading;
        if (input.get("source_id") instanceof String sourceId) {
            reading = entry.pageRead;
            if (reading == null || !reading.id().equals(sourceId)) {
                throw rejected(
                        "stale_source",
                        "Read the page again; the supplied source is no longer current in this session.");
            }
        } else {
            invalidateSnapshot(entry);
            var arguments = new LinkedHashMap<String, Object>();
            arguments.put("max_chars", MAX_SOURCE_CHARS);
            if (input.containsKey("section")) {
                arguments.put("section", input.get("section"));
            }
            CapturedSource source;
            try {
                source = readSource(entry, arguments, null, MAX_SOURCE_CHARS);
            } catch (BrowserRejectedException rejected) {
                throw rejected;
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (RuntimeException unavailable) {
                remove(entry);
                return error("page_unavailable");
            }
            requireLiveCommand(context);
            requireCurrentChildCall(context);
            source = new CapturedSource(
                    publicUrl(entry, source.pageUrl()),
                    source.title(),
                    source.text(),
                    source.coverage(),
                    source.observedAt(),
                    null,
                    null,
                    source.provenance());
            reading = new PageRead(UUID.randomUUID().toString(), source);
            entry.pageRead = reading;
        }
        int offset = input.containsKey("offset") ? ((Number) input.get("offset")).intValue() : 0;
        int limit = input.containsKey("limit") ? integer(input.get("limit")) : 12_000;
        String source = reading.source().text();
        if (offset > source.length() || offset > 0 && offset < source.length()
                && Character.isHighSurrogate(source.charAt(offset - 1))
                && Character.isLowSurrogate(source.charAt(offset))) {
            throw rejected("invalid_offset", "Use a returned next_offset within this source.");
        }
        var result = output("ok");
        result.put("session_id", entry.id);
        result.put("generation", entry.generation);
        result.put("source_id", reading.id());
        result.put("url", reading.source().pageUrl());
        result.put("title", reading.source().title());
        result.put("coverage", reading.source().provenance().get("read_status"));
        result.put("coverage_note", reading.source().coverage());
        result.put("observed_at", reading.source().observedAt().toString());
        result.put("offset", offset);
        result.put("total_chars", source.length());
        int low = 0, high = Math.min(limit, source.length() - offset), allowance = outputLimit(context);
        pageSlice(result, source, offset, 0);
        if (encodedLength(result) > allowance) {
            return error("result_capacity");
        }
        while (low < high) {
            int middle = (low + high + 1) / 2;
            pageSlice(result, source, offset, middle);
            if (encodedLength(result) <= allowance) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        pageSlice(result, source, offset, low);
        if (offset < source.length() && ((String) result.get("text")).isEmpty()) {
            return error("result_capacity");
        }
        requireLiveCommand(context);
        requireCurrentChildCall(context);
        return result;
    }

    /** Cursor calculation follows the final redacted UTF-16 slice, including JSON-budget reductions. */
    private static void pageSlice(Map<String, Object> result, String source, int offset, int count) {
        int end = offset + count;
        if (end > offset && end < source.length() && Character.isHighSurrogate(source.charAt(end - 1))
                && Character.isLowSurrogate(source.charAt(end))) {
            end--;
        }
        result.put("text", source.substring(offset, end));
        result.put("has_more", end < source.length());
        result.put("next_offset", end < source.length() ? end : -1);
    }

    private CapturedSource readSource(
            OwnedSession entry,
            Map<String, Object> arguments,
            String requestedUrl,
            int maxChars) {
        // Fixed trusted extraction; section is a plain-text label, never a selector or script.
        var raw = nativeCall(entry, "velocity_read_page", arguments);
        if (raw == null
                || !Set.of("status", "read_status", "url", "title", "text", "coverage").equals(raw.keySet())
                || !"ok".equals(raw.get("status")) || !(raw.get("read_status") instanceof String status)
                || !Set.of("ok", "partial", "truncated", "missing", "blocked").contains(status)
                || !(raw.get("url") instanceof String url) || url.length() > 8192
                || !(raw.get("title") instanceof String title) || title.length() > 4096
                || !(raw.get("text") instanceof String text) || (long) text.length() > 2L * maxChars
                || !(raw.get("coverage") instanceof String coverage) || coverage.length() > 2000) {
            throw rejected("page_unavailable", "No valid rendered page text was returned.");
        }
        policy.requireAllowed(url);
        if (requestedUrl != null && !BrowserDestinationPolicy.sameSource(requestedUrl, url)) {
            throw rejected("page_source_mismatch", "The rendered page does not match the selected source.");
        }
        entry.redactor.rememberUrlTokens(url);
        // Metadata can reveal a token repeated earlier in source text; learn every URL before caching any field.
        for (String field : List.of(text, title, coverage)) {
            URL.matcher(field).results().forEach(match -> entry.redactor.rememberUrlTokens(match.group()));
        }
        text = clean(entry, entry.credentialRedactor.cleanKnownValues(text));
        title = clean(entry, entry.credentialRedactor.cleanKnownValues(title));
        coverage = clean(entry, coverage);
        if (status.equals("missing") || status.equals("blocked")) {
            text = "";
        } else if (text.isBlank()) {
            status = "missing";
        } else if (text.length() > maxChars) {
            text = prefix(text, maxChars);
            status = "truncated";
            coverage = "The rendered page text reached the configured text limit.";
        }
        var provenance = Map.of(
                "engine",
                "browser_use",
                "engine_version",
                VERSION,
                "session_id",
                entry.id,
                "generation",
                entry.generation,
                "read_status",
                status,
                "extraction_method",
                "rendered_page_dom");
        return new CapturedSource(
                url,
                prefix(title, Math.min(title.length(), 4096)),
                text,
                prefix(coverage, Math.min(coverage.length(), 2000)),
                clock.instant(),
                null,
                null,
                provenance);
    }

    private Map<String, Object> retainState(OwnedSession entry, Map<String, Object> raw) {
        if (!Set.of("status", "url", "title", "text", "indices", "elements", "tabs", "truncated")
                .containsAll(raw.keySet())
                || raw.containsKey("truncated") && !(raw.get("truncated") instanceof Boolean)
                || !(raw.get("url") instanceof String url) || url.length() > 8192
                || !(raw.get("title") instanceof String title) || !(raw.get("text") instanceof String text)
                || !(raw.get("indices") instanceof List<?> indices)
                || !(raw.get("elements") instanceof List<?> elements)) {
            throw new IllegalStateException("Unsupported native browser state");
        }
        policy.requireAllowed(url);
        entry.redactor.rememberUrlTokens(url);
        String safe = clean(entry, entry.credentialRedactor.cleanKnownValues(text)),
                safeTitle = clean(entry, title);
        boolean truncated = Boolean.TRUE.equals(raw.get("truncated"))
                || safe.length() > entry.observationLimit || safeTitle.length() > 4096;
        var tabObservation = raw.containsKey("tabs") ? tabs(entry, raw.get("tabs")) : null;
        if (tabObservation != null) {
            truncated |= tabObservation.truncated();
        }
        var valid = new java.util.HashSet<Integer>();
        if (indices.size() > 10000 || elements.size() > 10000) {
            throw new IllegalStateException("Unsupported native browser state");
        }
        for (Object value : indices) {
            if (!valid.add(integer(value))) {
                throw new IllegalStateException("Duplicate browser index");
            }
        }
        var retained = new LinkedHashMap<Integer, ElementBinding>();
        var links = new LinkedHashMap<Integer, Link>();
        for (Object value : elements) {
            if (!(value instanceof Map<?, ?> item) || !Set.of(
                    "index",
                    "backend_node_id",
                    "frame_id",
                    "target_id",
                    "main_frame",
                    "tag",
                    "type",
                    "href",
                    "label").containsAll(item.keySet())) {
                throw new IllegalStateException("Invalid browser element");
            }
            int index = integer(item.get("index"));
            if (!valid.contains(index) || !(item.get("main_frame") instanceof Boolean main)) {
                throw new IllegalStateException("Invalid browser element");
            }
            var binding = new ElementBinding(
                    backendNodeId(item.get("backend_node_id")),
                    string(item.get("frame_id")),
                    string(item.get("target_id")),
                    main,
                    string(item.get("tag")),
                    nativeText(item.get("type"), 512));
            if (retained.put(index, binding) != null) {
                throw new IllegalStateException("Duplicate browser element");
            }
            String href = item.containsKey("href") ? nativeText(item.get("href"), 8192) : "";
            String label = item.containsKey("label") ? nativeText(item.get("label"), 512) : "";
            if (main && !href.isBlank()) {
                links.put(index, new Link(href, label));
            }
        }
        if (!retained.keySet().equals(valid)) {
            throw new IllegalStateException("Incomplete native element metadata");
        }
        entry.snapshotId = UUID.randomUUID().toString();
        entry.elements = truncated ? Map.of() : Map.copyOf(retained);
        entry.links = truncated ? Map.of() : Map.copyOf(links);
        entry.snapshot = new CapturedSource(
                url,
                safeTitle,
                safe,
                "Partial native Browser Use DOM observation",
                clock.instant(),
                null,
                null,
                Map.of(
                        "engine",
                        "browser_use",
                        "engine_version",
                        VERSION,
                        "session_id",
                        entry.id,
                        "generation",
                        entry.generation,
                        "state_id",
                        entry.snapshotId,
                        "snapshot_truncated",
                        Boolean.toString(truncated)));
        var result = output("ok");
        result.put("state_id", entry.snapshotId);
        result.put("url", publicUrl(entry, url));
        result.put("title", prefix(safeTitle, Math.min(4096, safeTitle.length())));
        result.put("text", prefix(safe, Math.min(entry.observationLimit, safe.length())));
        result.put("truncated", truncated);
        if (tabObservation != null) {
            result.put("tabs", tabObservation.items());
        }
        return result;
    }

    private static int integer(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Invalid native index");
        }
        int index = new BigDecimal(number.toString()).intValueExact();
        if (index < 1 || index > 1_000_000) {
            throw new IllegalStateException("Invalid native index");
        }
        return index;
    }

    private static int backendNodeId(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Invalid native node identity");
        }
        int identity = new BigDecimal(number.toString()).intValueExact();
        if (identity < 1) {
            throw new IllegalStateException("Invalid native node identity");
        }
        return identity;
    }

    private static String string(Object value) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 512) {
            throw new IllegalStateException("Invalid native metadata");
        }
        return text;
    }

    private static String nativeText(Object value, int maximum) {
        if (!(value instanceof String text) || text.length() > maximum) {
            throw new IllegalStateException("Invalid native metadata");
        }
        return text;
    }

    private static TabObservation tabs(OwnedSession entry, Object value) {
        if (!(value instanceof List<?> list) || list.size() > 10000) {
            throw new IllegalStateException("Invalid native tabs");
        }
        var result = new ArrayList<Map<String, Object>>();
        boolean truncated = false;
        for (Object tab : list) {
            if (!(tab instanceof Map<?, ?> item)
                    || !Set.of("page_id", "url", "title").equals(item.keySet())) {
                throw new IllegalStateException("Invalid native tab");
            }
            String title = clean(entry, nativeText(item.get("title"), 4096));
            truncated |= title.length() > 4096;
            result.add(
                    Map.of(
                            "page_id",
                            string(item.get("page_id")),
                            "url",
                            publicUrl(entry, nativeText(item.get("url"), 8192)),
                            "title",
                            prefix(title, Math.min(4096, title.length()))));
        }
        return new TabObservation(List.copyOf(result), truncated);
    }

    private static String clean(OwnedSession entry, String text) {
        String safe = URL.matcher(text).replaceAll(match -> {
            entry.redactor.rememberUrlTokens(match.group());
            return java.util.regex.Matcher.quoteReplacement(publicUrl(entry, match.group()));
        });
        return entry.redactor.clean(safe);
    }

    private static String publicUrl(OwnedSession entry, String url) {
        String safe = BrowserDestinationPolicy.safeUrl(url), decoded = safe;
        for (int level = 0; level < 16; level++) {
            if (entry.redactor.containsSecret(decoded)) {
                return "[URL withheld]";
            }
            try {
                String next = java.net.URLDecoder
                        .decode(decoded.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            } catch (IllegalArgumentException invalid) {
                break;
            }
        }
        return entry.redactor.cleanKnownValues(safe);
    }

    private static LinkedHashMap<String, Object> output(String status) {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", status);
        result.put("is_error", status.equals("error"));
        result.put("content_trust", "untrusted_data");
        result.put("truncated", false);
        return result;
    }

    private static Map<String, Object> error(String reason) {
        var result = output("error");
        result.put("code", reason);
        return result;
    }

    private static void invalidateSnapshot(OwnedSession entry) {
        entry.snapshot = null;
        entry.snapshotId = null;
        entry.elements = Map.of();
        entry.links = Map.of();
        entry.pageRead = null;
    }

    private OwnedSession create(ToolRegistry.Context context) {
        return create(context, true, outputLimit(context));
    }

    private OwnedSession create(ToolRegistry.Context context, boolean durable, int observationLimit) {
        var existing = sessions
                .values().stream().filter(
                        entry -> entry.executionId.equals(context.executionId())
                                && Objects.equals(entry.childRunId, context.childRunId()))
                .findFirst().orElse(null);
        if (existing != null) {
            if (existing.scope.equals(context.scope()) && existing.session.alive()) {
                throw bindingRejected("session_exists", existing);
            }
            throw rejected(
                    "session_exists",
                    "Use the current browser session metadata before opening another.");
        }
        var session = factory.apply(observationLimit, context);
        OwnedSession entry = null;
        try {
            requireLiveCommand(context);
            entry = new OwnedSession(context, session, durable, observationLimit);
            sessions.put(entry.id, entry);
            requireLiveCommand(context);
            return entry;
        } catch (RuntimeException cancelled) {
            if (entry != null) {
                sessions.remove(entry.id, entry);
            }
            try {
                session.close();
            } catch (RuntimeException ignored) {
            }
            throw cancelled;
        }
    }

    private OwnedSession owned(ToolRegistry.Context context, Map<String, Object> input) {
        OwnedSession entry = input.get("session_id") instanceof String id
                ? sessions.get(id)
                : sessions.values().stream()
                        .filter(
                                value -> value.executionId.equals(context.executionId())
                                        && Objects.equals(value.childRunId, context.childRunId()))
                        .findFirst().orElse(null);
        if (entry == null || !entry.executionId.equals(context.executionId())
                || !entry.scope.equals(context.scope())
                || !Objects.equals(entry.childRunId, context.childRunId())) {
            throw rejected(
                    "session_lost",
                    "No current browser generation belongs to this execution and trusted scope.");
        }
        if (!entry.session.alive()) {
            remove(entry);
            throw rejected("session_lost", "The browser process closed; start a fresh session.");
        }
        if (!entry.generation.equals(input.get("generation"))) {
            throw bindingRejected("session_binding_mismatch", entry);
        }
        return entry;
    }

    private BrowserRejectedException bindingRejected(String code, OwnedSession entry) {
        invalidateSnapshot(entry);
        return new BrowserRejectedException(
                code,
                "No browser action ran. The task still owns this live session. Copy the returned session_id and generation exactly into a fresh browser_state call before another interaction.",
                entry.id,
                entry.generation);
    }

    private Map<String, Object> identified(
            ToolRegistry.Context context,
            OwnedSession entry,
            Map<String, Object> result) {
        var identified = new LinkedHashMap<String, Object>();
        identified.put("session_id", entry.id);
        identified.put("generation", entry.generation);
        result.forEach((key, value) -> {
            if (!key.equals("text")) {
                identified.put(key, value);
            }
        });
        if (result.containsKey("text")) {
            identified.put("text", result.get("text"));
        }
        int allowance = outputLimit(context);
        if (encodedLength(identified) <= allowance) {
            return identified;
        }
        identified.put("truncated", true);
        entry.elements = Map.of();
        entry.links = Map.of();
        String text = result.get("text") instanceof String value ? value : null;
        if (text != null) {
            identified.put("text", "");
        }
        for (String optional : List.of("title", "url", "tabs")) {
            if (encodedLength(identified) <= allowance) {
                break;
            }
            identified.remove(optional);
        }
        if (encodedLength(identified) > allowance) {
            invalidateSnapshot(entry);
            var capacity = error("result_capacity");
            capacity.put("truncated", true);
            return capacity;
        }
        if (text != null) {
            int low = 0, high = text.length();
            while (low < high) {
                int candidate = (low + high + 1) / 2;
                identified.put("text", prefix(text, candidate));
                if (encodedLength(identified) <= allowance) {
                    low = candidate;
                } else {
                    high = candidate - 1;
                }
            }
            identified.put("text", prefix(text, low));
        }
        return identified;
    }

    private static String prefix(String value, int length) {
        if (length > 0 && length < value.length() && Character.isHighSurrogate(value.charAt(length - 1))
                && Character.isLowSurrogate(value.charAt(length))) {
            length--;
        }
        return value.substring(0, length);
    }

    private static int encodedLength(Map<String, Object> output) {
        try {
            return JSON.writeValueAsString(output).length();
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Browser result could not be encoded", null);
        }
    }

    public void requireCurrentChildCall(ToolRegistry.Context context) {
        if (context.childRunId() == null) {
            return;
        }
        if (repository == null) {
            throw rejected("invalid_owner", "Child browser work requires durable provenance.");
        }
        try {
            CallProvenance.current(repository, context);
        } catch (CallProvenance.Rejected invalid) {
            throw rejected(invalid.status(), invalid.getMessage());
        }
    }

    private <T> T command(ToolRegistry.Context context, Callable<T> operation) throws Exception {
        return command(context, operation, true);
    }

    private <T> T command(ToolRegistry.Context context, Callable<T> operation, boolean durable)
            throws Exception {
        Objects.requireNonNull(context);
        Objects.requireNonNull(context.scope());
        Objects.requireNonNull(context.cancellation());
        if (context.executionId() == null || context.executionId().isBlank()
                || context.scope().ownerAccountId() == null) {
            throw rejected("invalid_owner", "A trusted execution identity is required.");
        }
        if (closing) {
            throw rejected("session_lost", "The browser service is shutting down.");
        }
        var dispatch = new AtomicInteger();
        try {
            return context.cancellation().await(owner, () -> {
                if (!dispatch.compareAndSet(0, 1)) {
                    throw rejected(
                            "queue_timeout",
                            "No browser operation started before the queue deadline.");
                }
                commandDispatch.set(dispatch);
                commandContext.set(context);
                try {
                    expire();
                    context.cancellation().check();
                    if (durable) {
                        requireExecution(context);
                    }
                    requireCurrentChildCall(context);
                    return operation.call();
                } finally {
                    commandDispatch.remove();
                    commandContext.remove();
                }
            }, Long.MAX_VALUE);
        } catch (RejectedExecutionException unavailable) {
            throw rejected("session_lost", "The browser service is shutting down.");
        } catch (TimeoutException timeout) {
            if (dispatch.compareAndSet(0, 2)) {
                throw rejected("queue_timeout", "No browser operation started before the queue deadline.");
            }
            dispatch.compareAndSet(1, 3);
            releaseContext(context);
            throw uncertain("deadline_exceeded");
        } catch (CancellationException cancelled) {
            dispatch.compareAndSet(1, 3);
            releaseContext(context);
            throw cancelled;
        } catch (InterruptedException interrupted) {
            dispatch.compareAndSet(1, 3);
            releaseContext(context);
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private void requireLiveCommand(ToolRegistry.Context context) {
        context.cancellation().check();
        var dispatch = commandDispatch.get();
        if (closing || dispatch == null || dispatch.get() != 1) {
            throw new CancellationException();
        }
    }

    private Map<String, Object> nativeCall(OwnedSession entry, String tool, Map<String, Object> arguments) {
        var context = commandContext.get();
        if (context == null) {
            throw new CancellationException();
        }
        requireLiveCommand(context);
        long remaining = context.remainingOperationMillis();
        if (remaining <= 0) {
            throw new CancellationException();
        }
        return entry.session.call(tool, arguments, remaining);
    }

    private void releaseContext(ToolRegistry.Context context) {
        sessions.values().stream().filter(
                entry -> entry.executionId.equals(context.executionId())
                        && entry.scope.equals(context.scope())
                        && Objects.equals(entry.childRunId, context.childRunId()))
                .toList().forEach(this::remove);
    }

    private int outputLimit(ToolRegistry.Context context) {
        if (repository == null) {
            return ExecutionLimits.defaults().outputPreviewChars();
        }
        var execution = repository.get(context.executionId());
        return context.childRunId() == null
                ? execution.limits.outputPreviewChars()
                : execution.childRuns.get(context.childRunId()).limits.outputPreviewChars();
    }

    public void releaseExecution(String executionId) {
        sessions.values().stream().filter(entry -> entry.executionId.equals(executionId)).toList()
                .forEach(this::remove);
    }

    private void requireExecution(ToolRegistry.Context context) {
        if (repository == null) {
            return;
        }
        var execution = repository.get(context.executionId());
        if (!context.scope().equals(execution.scope)) {
            throw rejected("invalid_owner", "The trusted execution scope does not own this browser request.");
        }
        if (execution.cancellationRequested || execution.status.terminal()) {
            throw rejected("inactive_execution", "The task is no longer active.");
        }
    }

    public void reap() {
        if (!closing) {
            expire();
        }
    }

    private void expire() {
        for (var entry : sessions.values()) {
            if (entry.durable && terminal(entry) || !entry.session.alive()) {
                remove(entry);
            }
        }
    }

    private boolean terminal(OwnedSession entry) {
        if (repository == null) {
            return false;
        }
        try {
            var state = repository.get(entry.executionId);
            if (state.cancellationRequested || state.status.terminal()) {
                return true;
            }
            if (entry.childRunId == null) {
                return false;
            }
            var child = state.childRuns.get(entry.childRunId);
            return child == null || child.status.terminal() || child.goalGeneration != state.goalGeneration;
        } catch (NotFoundException missing) {
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void remove(OwnedSession entry) {
        sessions.remove(entry.id, entry);
        invalidateSnapshot(entry);
        try {
            entry.session.close();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void close() {
        if (closing) {
            return;
        }
        closing = true;
        maintenance.shutdownNow();
        sessions.values().forEach(this::remove);
        owner.shutdownNow();
        try {
            owner.awaitTermination(2000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static BrowserRejectedException rejected(String code, String message) {
        return new BrowserRejectedException(code, message);
    }

    private static ToolRegistry.UncertainToolException uncertain(String reason) {
        return new ToolRegistry.UncertainToolException(
                Map.of("capability", "browser_use", "reason", reason, "external_effect", "unknown"));
    }

    private record Link(String url, String title) {}

    private record TabObservation(List<Map<String, Object>> items, boolean truncated) {}

    private record PageRead(String id, CapturedSource source) {}

    private final class OwnedSession {

        final String id = UUID.randomUUID().toString(), generation = UUID.randomUUID().toString(),
                executionId, childRunId;
        final TrustedScope scope;
        final Session session;
        final boolean durable;
        final int observationLimit;
        final BrowserContentRedactor redactor = new BrowserContentRedactor(),
                credentialRedactor = new BrowserContentRedactor();
        volatile Map<Integer, ElementBinding> elements = Map.of();
        volatile Map<Integer, Link> links = Map.of();
        volatile CapturedSource snapshot;
        volatile String snapshotId;
        volatile PageRead pageRead;
        volatile boolean credentialProtected;
        /** Origins that received a saved credential pair; their pages are never captured as pixels. */
        final Set<String> credentialOrigins = ConcurrentHashMap.newKeySet();

        OwnedSession(ToolRegistry.Context context, Session session, boolean durable, int observationLimit) {
            executionId = context.executionId();
            childRunId = context.childRunId();
            scope = context.scope();
            this.session = session;
            this.durable = durable;
            this.observationLimit = observationLimit;
        }
    }
}
