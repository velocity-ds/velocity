package net.velocity.agent;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;

import net.velocity.agent.state.TrustedScope;
import net.velocity.util.Sha256;

/** Preserves the canonical persisted binding bytes independently of other JSON policies. */
final class CallBindings {

    private static final ObjectWriter JSON = new JsonMapper().writer();

    private CallBindings() {
    }

    static String binding(TrustedScope scope, String callId, String tool, Map<String, Object> input) {
        try {

            var fields = List.of(
                    scope.requireOwnerAccountId(),
                    scope.workspaceId(),
                    scope.requesterId(),
                    scope.channelId(),
                    scope.botId(),
                    scope.origin(),
                    callId,
                    tool,
                    normalized(input));
            return Sha256.hex(JSON.writeValueAsString(fields));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot normalize tool binding", e);
        }
    }

    private static Object normalized(Object value) {
        if (value instanceof Map<?, ?> values) {
            var result = new TreeMap<String, Object>();
            values.forEach((k, v) -> result.put((String) k, normalized(v)));
            return result;
        }
        if (value instanceof List<?> values) {
            return values.stream().map(CallBindings::normalized).toList();
        }
        if (value instanceof Number n) {
            return new java.math.BigDecimal(n.toString()).stripTrailingZeros();
        }
        return value;
    }
}
