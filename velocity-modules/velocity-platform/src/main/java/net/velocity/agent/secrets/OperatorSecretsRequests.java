package net.velocity.agent.secrets;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.velocity.agent.http.OperatorDataRequests;
import net.velocity.agent.http.StrictRequests;

import jakarta.servlet.http.HttpServletRequest;

/** Deliberately no credential DTO record, generated toString, generic JSON inspector or conversion cache. */
final class OperatorSecretsRequests {

    private OperatorSecretsRequests() {
    }

    static Input read(
            HttpServletRequest request,
            Map<String, String> parameters,
            Set<String> allowed,
            Set<String> required) {
        byte[] body = null;
        try {
            OperatorDataRequests.noParameters(parameters);
            body = body(request);
            var values = StrictRequests.object(body, StrictRequests.Numbers.PRECISE_FLOATS);
            StrictRequests.fields(values, allowed, required);
            return new Input(values);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid secrets request");
        } finally {
            if (body != null) {
                Arrays.fill(body, (byte) 0);
            }
        }
    }

    static void noBody(HttpServletRequest request) {
        byte[] body = null;
        try {
            body = body(request);
            StrictRequests.empty(body, "Invalid secrets request");
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid secrets request");
        } finally {
            if (body != null) {
                Arrays.fill(body, (byte) 0);
            }
        }
    }

    /** Bypass MVC message converters: their DEBUG/TRACE logging would serialize a String body. */
    private static byte[] body(HttpServletRequest request) {
        try {
            return StrictRequests.body(request.getInputStream(), 131072, 32768);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid secrets request");
        }
    }

    static final class Input implements AutoCloseable {

        private final Map<String, Object> values;

        Input(Map<String, Object> values) {
            this.values = values;
        }

        String string(String key) {
            if (!values.containsKey(key)) {
                return null;
            }
            if (!(values.get(key) instanceof String value)) {
                throw new IllegalArgumentException("Invalid secrets request");
            }
            return value;
        }

        long revision() {
            var value = values.get("expected_revision");
            if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 1) {
                throw new IllegalArgumentException("Invalid secrets request");
            }
            return ((Number) value).longValue();
        }

        boolean enabled() {
            if (!(values.get("enabled") instanceof Boolean enabled)) {
                throw new IllegalArgumentException("Invalid secrets request");
            }
            return enabled;
        }

        List<String> origins(String key) {
            if (!(values.get(key) instanceof List<?> list)
                    || list.stream().anyMatch(value -> !(value instanceof String))) {
                throw new IllegalArgumentException("Invalid secrets request");
            }
            return list.stream().map(String.class::cast).toList();
        }

        List<String> optionalOrigins(String key) {
            return values.containsKey(key) ? origins(key) : null;
        }

        @Override
        public void close() {
            values.clear();
        }

        @Override
        public String toString() {
            return "SecretsInput[REDACTED]";
        }
    }
}
