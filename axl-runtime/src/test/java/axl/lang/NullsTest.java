package axl.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class NullsTest {

    @Test
    void orEvaluatesFallbackLazily() {
        var calls = new AtomicInteger();
        assertEquals("a", Nulls.or("a", () -> {
            calls.incrementAndGet();
            return "b";
        }));
        assertEquals(0, calls.get());
        assertEquals("b", Nulls.or(null, () -> {
            calls.incrementAndGet();
            return "b";
        }));
        assertEquals(1, calls.get());
    }

    @Test
    void mapSkipsFunctionOnNull() {
        var calls = new AtomicInteger();
        String result = Nulls.<String, String>map(null, s -> {
            calls.incrementAndGet();
            return s.trim();
        });
        assertNull(result);
        assertEquals(0, calls.get());
    }

    @Test
    void mapAppliesFunctionOnValue() {
        assertEquals(3, Nulls.map("abc", String::length));
    }

    @Test
    void nonNullsDropsNulls() {
        assertEquals(List.of("a", "b"), Nulls.nonNulls(Stream.of("a", null, "b")).toList());
    }

    @Test
    void streamTakesCollectionsAndStreams() {
        assertEquals(List.of("a", "b"), Nulls.stream(List.of("a", "b")).toList());
        assertEquals(List.of("a", "b"), Nulls.stream(Stream.of("a", "b")).toList());
    }

    @Test
    void arrayOfKeepsTheElementClass() {
        Map.Entry<String, Integer>[] entries = Nulls.arrayOf(List.of(Map.entry("a", 1)), Map.Entry.class);
        assertEquals(Map.Entry.class, entries.getClass().getComponentType());
        assertEquals(Map.of("a", 1), Map.ofEntries(entries));
    }
}
