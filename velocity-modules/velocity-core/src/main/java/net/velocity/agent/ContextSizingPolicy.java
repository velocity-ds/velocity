package net.velocity.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

import com.fasterxml.jackson.databind.json.JsonMapper;

import net.velocity.agent.state.CallState;
import net.velocity.agent.state.ExecutionSnapshot;
import net.velocity.inference.objects.InferenceContent;
import net.velocity.inference.objects.InferenceMessage;
import net.velocity.inference.objects.InferenceToolCalls;

/** Conservative request capacity, independent of provider-reported cumulative task usage. */
public final class ContextSizingPolicy {

    public static final long OVERHEAD_TOKENS = 2048;
    public static final String ESTIMATOR = "serialized_utf8_bytes_one_token_per_byte";
    public static final String PROVIDER_ESTIMATOR = "provider_input_tokens_plus_overhead";
    private static final JsonMapper JSON = new JsonMapper();

    private ContextSizingPolicy() {
    }

    public enum Decision {
        accepted, input_allowance, remaining_budget, invalid_context
    }

    /** Window overflow and cumulative exhaustion are independent: only the former permits a handoff. */
    public static Decision decide(
            Estimate estimate,
            boolean validContext,
            long remainingTokens,
            long inputAllowance,
            long outputReserve) {
        if (!validContext || estimate == null) {
            return Decision.invalid_context;
        }
        if (estimate.estimatedInputTokens() > inputAllowance) {
            return Decision.input_allowance;
        }
        return estimate.fits(remainingTokens, inputAllowance, outputReserve)
                ? Decision.accepted
                : Decision.remaining_budget;
    }

    public record Estimate(
            long messageBytes,
            long schemaBytes,
            long instructionBytes,
            long envelopeBytes,
            long estimatedInputTokens,
            String estimatorKind) {

        /** Keep byte diagnostics separate from the selected model's tokenizer count. */
        public Estimate withProviderInputTokens(OptionalLong tokens) {
            if (tokens.isEmpty()) {
                return this;
            }
            if (tokens.getAsLong() < 0) {
                throw new IllegalArgumentException("Input token count must be nonnegative");
            }
            return new Estimate(
                    messageBytes,
                    schemaBytes,
                    instructionBytes,
                    envelopeBytes,
                    reserve(tokens.getAsLong(), OVERHEAD_TOKENS),
                    PROVIDER_ESTIMATOR);
        }

        public boolean fits(long remainingTokens, long inputAllowance, long outputReserve) {
            return estimatedInputTokens <= allowance(remainingTokens, inputAllowance, outputReserve);
        }

        /** Numeric-only diagnostics; no source, prompt, tool arguments or native blocks. */
        public Map<String, Object> diagnostics(
                long remainingTokens,
                long inputAllowance,
                long outputReserve,
                String profileId,
                String providerId,
                String model) {
            return Map.ofEntries(
                    Map.entry("message_bytes", messageBytes),
                    Map.entry("schema_bytes", schemaBytes),
                    Map.entry("instruction_bytes", instructionBytes),
                    Map.entry("envelope_bytes", envelopeBytes),
                    Map.entry("estimated_input_tokens", estimatedInputTokens),
                    Map.entry("estimator", estimatorKind),
                    Map.entry("configured_input_allowance", inputAllowance),
                    Map.entry(
                            "effective_input_allowance",
                            allowance(remainingTokens, inputAllowance, outputReserve)),
                    Map.entry("output_reserve", outputReserve),
                    Map.entry("remaining_cumulative_tokens", Math.max(0, remainingTokens)),
                    Map.entry("profile_id", profileId),
                    Map.entry("provider_id", providerId),
                    Map.entry("model", model));
        }
    }

    public static Estimate estimate(
            List<InferenceMessage> messages,
            String instructions,
            List<ToolRegistry.Definition> definitions) {
        var schemas = definitions.stream().map(
                tool -> Map.of(
                        "name",
                        tool.name(),
                        "description",
                        tool.description(),
                        "input_schema",
                        tool.inputSchema()))
                .toList();
        String guidance = Objects.requireNonNullElse(instructions, "");
        try {
            long messageBytes = JSON.writeValueAsBytes(messages).length;
            long schemaBytes = JSON.writeValueAsBytes(schemas).length;
            long instructionBytes = JSON.writeValueAsBytes(guidance).length;
            long complete = JSON.writeValueAsBytes(
                    Map.of(
                            "messages",
                            messages,
                            "instructions",
                            guidance,
                            "tools",
                            schemas)).length;
            return new Estimate(
                    messageBytes,
                    schemaBytes,
                    instructionBytes,
                    complete - messageBytes - schemaBytes - instructionBytes,
                    complete + OVERHEAD_TOKENS,
                    ESTIMATOR);
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("Context cannot be encoded", invalid);
        }
    }

    /**
     * Speculative completion sizing only: tokenizers require a result for every pending call.
     * These placeholders never enter saved history or generation, and actual results are sized again.
     */
    public static List<InferenceMessage> completionMessages(
            ExecutionSnapshot snapshot,
            List<InferenceMessage> messages) {
        var calls = new HashSet<String>();
        var results = new HashSet<String>();
        for (var message : messages) {
            for (var content : message.content()) {
                var interaction = InferenceToolCalls.decode(content);
                if (interaction instanceof InferenceToolCalls.Call call) {
                    calls.add(call.id());
                } else if (interaction instanceof InferenceToolCalls.Result result) {
                    results.add(result.id());
                }
            }
        }
        List<InferenceContent> pending = new ArrayList<>();
        for (var call : snapshot.calls) {
            if (call.state != CallState.settled && calls.contains(call.id) && !results.contains(call.id)) {
                pending.add(
                        new InferenceContent.ToolResult(
                                call.id,
                                List.of(
                                        new InferenceContent.Text(
                                                "Pending tool result (token reservation only).")),
                                false,
                                call.toolset));
            }
        }
        if (pending.isEmpty()) {
            return messages;
        }
        var completed = new ArrayList<>(messages);
        completed.add(new InferenceMessage(InferenceMessage.Role.USER, pending));
        return List.copyOf(completed);
    }

    public static long allowance(long remainingTokens, long inputAllowance, long outputReserve) {
        if (inputAllowance <= 0 || outputReserve <= 0) {
            throw new IllegalArgumentException("Input and output allowances must be positive");
        }
        return Math.min(
                inputAllowance,
                Math.max(0, remainingTokens) <= outputReserve
                        ? 0
                        : remainingTokens - outputReserve);
    }

    /** Saturation preserves exhaustion rather than wrapping a large configured output allowance into capacity. */
    public static long reserve(long estimatedInputTokens, long outputTokenAllowance) {
        if (estimatedInputTokens < 0 || outputTokenAllowance <= 0) {
            throw new IllegalArgumentException(
                    "Input estimate must be nonnegative and output allowance must be positive");
        }
        return outputTokenAllowance > Long.MAX_VALUE - estimatedInputTokens
                ? Long.MAX_VALUE
                : estimatedInputTokens + outputTokenAllowance;
    }

}
