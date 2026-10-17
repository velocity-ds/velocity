package net.velocity.agent;

import java.util.List;
import java.util.OptionalLong;
import java.util.function.Consumer;

import net.velocity.inference.objects.InferenceRequest;
import net.velocity.inference.objects.InferenceResponse;

/** Exactly one configured provider; no independent loop or provider fallback. */
@FunctionalInterface
public interface ModelClient {

    InferenceResponse infer(
            InferenceRequest request,
            List<ToolRegistry.Definition> tools,
            CancellationHandle cancellation);

    /**
     * The same request, relaying assistant text to {@code textDelta} as it is generated when the client
     * streams. The returned response is complete and identical in shape to {@link #infer}; a client that does
     * not stream ignores the consumer.
     */
    default InferenceResponse infer(
            InferenceRequest request,
            List<ToolRegistry.Definition> tools,
            CancellationHandle cancellation,
            Consumer<String> textDelta) {
        return infer(request, tools, cancellation);
    }

    default boolean available() {
        return true;
    }

    /** Count the same request without generation. Empty retains compatibility sizing for custom clients. */
    default OptionalLong countInputTokens(
            InferenceRequest request,
            List<ToolRegistry.Definition> tools,
            CancellationHandle cancellation) {
        cancellation.check();
        return OptionalLong.empty();
    }

    /** Configured maximum output tokens, or a conservative compatibility allowance for custom clients. No I/O. */
    default long outputTokenAllowance() {
        return 2048;
    }
}
