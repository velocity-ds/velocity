package net.velocity.util;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BsonJsonTest {

    @Test
    void decimal128BecomesBigDecimalEverywhereInTheTree() {
        var document = new LinkedHashMap<String, Object>();
        document.put("price", new Decimal128(new BigDecimal("19.99")));
        document.put("history", List.of(new Decimal128(new BigDecimal("1.5")), 2L));
        document.put("nested", Map.of("ratio", new Decimal128(new BigDecimal("0.25"))));

        Object decoded = BsonJson.decode(document);

        assertThat(decoded).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) decoded;
        assertThat(map.keySet()).containsExactly("price", "history", "nested");
        assertThat(map.get("price")).isEqualTo(new BigDecimal("19.99"));
        assertThat(map.get("history")).isEqualTo(List.of(new BigDecimal("1.5"), 2L));
        assertThat(((Map<?, ?>) map.get("nested")).get("ratio")).isEqualTo(new BigDecimal("0.25"));
    }

    @Test
    void otherValuesPassThroughAndCollectionsAreDetached() {
        assertThat(BsonJson.decode("text")).isEqualTo("text");
        assertThat(BsonJson.decode(42)).isEqualTo(42);
        assertThat(BsonJson.decode(null)).isNull();
        var source = new LinkedHashMap<String, Object>(Map.of("a", 1));
        Object decoded = BsonJson.decode(source);
        source.put("b", 2);
        @SuppressWarnings("unchecked")
        Map<String, Object> detached = (Map<String, Object>) decoded;
        assertThat(detached).containsOnlyKeys("a");
    }
}
