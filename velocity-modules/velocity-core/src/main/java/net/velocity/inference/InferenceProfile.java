package net.velocity.inference;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import net.velocity.inference.objects.InferenceCapability;

/** An immutable, non-secret inference binding and its request admission policy. */
public final class InferenceProfile {

    private final String id;
    private final String version;
    private final String providerId;
    private final String model;
    private final long contextCapacity;
    private final long inputAllowance;
    private final long maxOutputTokens;
    private final long safetyMargin;
    private final Set<InferenceCapability> capabilities;
    private final InferenceService service;

    /** A zero context capacity means that no model capacity is declared. */
    public InferenceProfile(
            String id,
            String providerId,
            String model,
            long contextCapacity,
            long inputAllowance,
            long maxOutputTokens,
            long safetyMargin,
            InferenceService service) {
        this.id = required(id, "id");
        this.providerId = required(providerId, "providerId");
        this.model = required(model, "model");
        if (contextCapacity < 0 || inputAllowance <= 0 || maxOutputTokens <= 0 || safetyMargin < 0) {
            throw new IllegalArgumentException(
                    "Inference profile capacity and output policy must be positive");
        }
        if (!InferenceCapacity.fits(contextCapacity, inputAllowance, maxOutputTokens, safetyMargin)) {
            throw new IllegalArgumentException(
                    "Inference profile input, output, and safety allowances exceed context capacity");
        }
        this.contextCapacity = contextCapacity;
        this.inputAllowance = inputAllowance;
        this.maxOutputTokens = maxOutputTokens;
        this.safetyMargin = safetyMargin;
        this.service = Objects.requireNonNull(service, "service");
        if (!service.available() || !providerId.equals(service.providerId())
                || maxOutputTokens != service.outputTokenAllowance()) {
            throw new IllegalArgumentException(
                    "Inference profile service does not match its provider and output policy");
        }
        this.capabilities = Set.copyOf(service.capabilities());
        this.version = identityVersion();
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Inference profile " + name + " is required");
        }
        return value;
    }

    private String identityVersion() {
        String identity = String.join(
                "\n",
                id,
                providerId,
                model,
                Long.toString(contextCapacity),
                Long.toString(inputAllowance),
                Long.toString(maxOutputTokens),
                Long.toString(safetyMargin),
                capabilities.stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public String id() {
        return id;
    }

    public String version() {
        return version;
    }

    public String providerId() {
        return providerId;
    }

    public String model() {
        return model;
    }

    public long contextCapacity() {
        return contextCapacity;
    }

    public long inputAllowance() {
        return inputAllowance;
    }

    public long maxOutputTokens() {
        return maxOutputTokens;
    }

    public long safetyMargin() {
        return safetyMargin;
    }

    public InferenceService service() {
        return service;
    }
}
