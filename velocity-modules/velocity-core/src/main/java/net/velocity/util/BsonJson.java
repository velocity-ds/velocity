package net.velocity.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.types.Decimal128;

/** Detached JSON values from BSON; write ordering and numeric rejection remain repository policies. */
public final class BsonJson {

    private BsonJson() {
    }

    public static Object decode(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((key, child) -> converted.put((String) key, decode(child)));
            return converted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(BsonJson::decode).toList();
        }
        if (value instanceof Decimal128 number) {
            return number.bigDecimalValue();
        }
        return value;
    }
}
