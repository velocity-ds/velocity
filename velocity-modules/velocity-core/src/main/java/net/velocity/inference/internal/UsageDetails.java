package net.velocity.inference.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads usage evidence without turning absent or malformed counts into a complete estimate. */
public final class UsageDetails {

    private final List<String> unsupported = new ArrayList<>();
    private boolean incomplete;

    public long count(Map<?, ?> values, String key, boolean required) {
        Object value = values.get(key);
        if (value == null) {
            incomplete |= required;
            return 0;
        }
        if (value instanceof Number number) {
            try {
                long count = new BigDecimal(number.toString()).longValueExact();
                if (count >= 0) {
                    return count;
                }
            } catch (ArithmeticException | NumberFormatException ignored) {
            }
        }
        incomplete = true;
        unsupported(key);
        return 0;
    }

    public Map<?, ?> details(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> fields) {
            return fields;
        }
        incomplete = true;
        unsupported(key);
        return Map.of();
    }

    public void unknown(Map<?, ?> values, Set<String> recognized, String prefix) {
        values.forEach((key, value) -> {
            if (!recognized.contains(key) && present(value)) {
                unsupported(prefix + key);
            }
        });
    }

    public void unsupported(String category) {
        if (!unsupported.contains(category)) {
            unsupported.add(category);
        }
    }

    public boolean incomplete() {
        return incomplete;
    }

    public List<String> unsupportedCategories() {
        return List.copyOf(unsupported);
    }

    private static boolean present(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).signum() != 0;
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(UsageDetails::present);
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(UsageDetails::present);
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return !value.toString().isBlank();
    }
}
