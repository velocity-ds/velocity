package net.velocity.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP connector settings. Defaults live only in {@code application.properties}; server entries are declared
 * as an indexed list ({@code mcp.servers[0].name=...}) in {@code .env} or the process environment. An enabled
 * feature with no server, a malformed server entry or a duplicate server name fails startup, so a
 * misconfigured installation never registers tools that cannot work. Whether a configured server is actually
 * reachable is decided at startup discovery and reported in the status section, never by failing the boot.
 */
@ConfigurationProperties("mcp")
public record McpProperties(
        boolean enabled,
        Duration connectTimeout,
        Duration requestTimeout,
        int maxResultChars,
        boolean confirmWrites,
        List<Server> servers) {

    /** Server names become part of every registered tool name, so they are short and permanent. */
    public static final Pattern SERVER_NAME = Pattern.compile("[a-z][a-z0-9_]{0,23}");

    public static final int MIN_RESULT_CHARS = 1_000;

    public static final int MAX_RESULT_CHARS = 1_000_000;

    public static final int MAX_SERVERS = 32;

    public static final int MAX_ALLOWED_TOOLS = 256;

    /** Kernel tool categories a server may be pinned to; reads are the ones ending in {@code .read}. */
    public static final Set<String> CATEGORIES = Set.of(
            "system.read",
            "repository.read",
            "network.read",
            "repository.write",
            "network.write",
            "system.execute");

    private static final Duration MIN_TIMEOUT = Duration.ofSeconds(1);

    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}");

    public McpProperties {
        servers = servers == null ? List.of() : List.copyOf(servers);
        if (enabled) {
            requireTimeout("mcp.connect-timeout", connectTimeout);
            requireTimeout("mcp.request-timeout", requestTimeout);
            if (maxResultChars < MIN_RESULT_CHARS || maxResultChars > MAX_RESULT_CHARS) {
                throw new IllegalArgumentException(
                        "mcp.max-result-chars must be between " + MIN_RESULT_CHARS + " and "
                                + MAX_RESULT_CHARS);
            }
            if (servers.isEmpty()) {
                throw new IllegalArgumentException(
                        "mcp.enabled requires at least one mcp.servers[n] entry; set MCP_ENABLED=false otherwise");
            }
            if (servers.size() > MAX_SERVERS) {
                throw new IllegalArgumentException("At most " + MAX_SERVERS + " MCP servers are supported");
            }
            var names = new HashSet<String>();
            for (Server server : servers) {
                server.validate();
                if (!names.add(server.name())) {
                    throw new IllegalArgumentException("Duplicate MCP server name: " + server.name());
                }
            }
        }
    }

    private static void requireTimeout(String setting, Duration value) {
        if (value == null || value.compareTo(MIN_TIMEOUT) < 0 || value.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException(setting + " must be between 1s and 10m");
        }
    }

    /**
     * One configured server. {@code transport} is {@code stdio} (a child process started with {@code command}
     * and {@code args}) or {@code http} (an MCP Streamable HTTP endpoint at {@code url}). Optional per-server
     * values fall back to the installation-wide settings when absent.
     */
    public record Server(
            String name,
            String transport,
            String command,
            List<String> args,
            String directory,
            Map<String, String> environment,
            String url,
            Map<String, String> headers,
            List<String> tools,
            String category,
            Duration requestTimeout,
            Integer maxResultChars) {

        public Server {
            name = strip(name);
            transport = strip(transport).toLowerCase(Locale.ROOT);
            command = strip(command);
            args = args == null ? List.of() : List.copyOf(args);
            directory = strip(directory);
            environment = environment == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(environment));
            url = strip(url);
            headers = headers == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(headers));
            tools = tools == null ? List.of() : tools.stream().map(McpProperties::strip).toList();
            category = strip(category);
        }

        public boolean stdio() {
            return "stdio".equals(transport);
        }

        /** Validation runs only while the feature is enabled, so a disabled feature never blocks startup. */
        void validate() {
            if (!SERVER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException(
                        "mcp.servers[].name must match " + SERVER_NAME.pattern() + ": '" + name + "'");
            }
            String label = "mcp.servers[" + name + "]";
            switch (transport) {
                case "stdio" -> validateStdio(label);
                case "http" -> validateHttp(label);
                default -> throw new IllegalArgumentException(label + ".transport must be stdio or http");
            }
            if (tools.size() > MAX_ALLOWED_TOOLS) {
                throw new IllegalArgumentException(
                        label + ".tools lists more than " + MAX_ALLOWED_TOOLS + " names");
            }
            var allowed = new HashSet<String>();
            for (String tool : tools) {
                if (tool.isEmpty() || tool.length() > 128 || !allowed.add(tool)) {
                    throw new IllegalArgumentException(
                            label + ".tools holds an empty, oversized or repeated name");
                }
            }
            if (!category.isEmpty() && !CATEGORIES.contains(category)) {
                throw new IllegalArgumentException(
                        label + ".category must be one of "
                                + String.join(", ", CATEGORIES.stream().sorted().toList()));
            }
            if (requestTimeout != null) {
                requireTimeout(label + ".request-timeout", requestTimeout);
            }
            if (maxResultChars != null
                    && (maxResultChars < MIN_RESULT_CHARS || maxResultChars > MAX_RESULT_CHARS)) {
                throw new IllegalArgumentException(
                        label + ".max-result-chars must be between " + MIN_RESULT_CHARS + " and "
                                + MAX_RESULT_CHARS);
            }
        }

        private void validateStdio(String label) {
            if (command.isEmpty()) {
                throw new IllegalArgumentException(label + ".command is required for the stdio transport");
            }
            if (!url.isEmpty() || !headers.isEmpty()) {
                throw new IllegalArgumentException(label + " uses stdio and must not set url or headers");
            }
            if (args.stream().anyMatch(argument -> argument == null || argument.length() > 4096)) {
                throw new IllegalArgumentException(label + ".args entries must be at most 4096 characters");
            }
            environment.forEach((key, value) -> {
                if (key == null || key.isBlank() || key.length() > 256 || key.contains("=") || value == null
                        || value.length() > 32_768) {
                    throw new IllegalArgumentException(label + ".environment holds an invalid entry");
                }
            });
        }

        private void validateHttp(String label) {
            if (!command.isEmpty() || !args.isEmpty() || !directory.isEmpty() || !environment.isEmpty()) {
                throw new IllegalArgumentException(
                        label + " uses http and must not set command, args, directory or environment");
            }
            URI uri = endpoint();
            if (uri.getUserInfo() != null) {
                throw new IllegalArgumentException(label + ".url must not carry credentials");
            }
            headers.forEach((key, value) -> {
                if (key == null || !HEADER_NAME.matcher(key).matches() || value == null
                        || value.length() > 8192 || value.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException(label + ".headers holds an invalid entry");
                }
            });
        }

        /** The validated HTTP endpoint: {@code https}, or plain {@code http} to a loopback host only. */
        URI endpoint() {
            String label = "mcp.servers[" + name + "].url";
            URI uri;
            try {
                uri = new URI(url);
            } catch (Exception invalid) {
                throw new IllegalArgumentException(label + " is not a valid absolute URL");
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.isEmpty() || uri.getRawFragment() != null) {
                throw new IllegalArgumentException(
                        label + " must be an absolute URL with a host and no fragment");
            }
            boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]")
                    || host.equals("::1");
            if (!(scheme.equals("https") || scheme.equals("http") && loopback)) {
                throw new IllegalArgumentException(label + " must use https, or http to a loopback host");
            }
            return uri;
        }
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }
}
