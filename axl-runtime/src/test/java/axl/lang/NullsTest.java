package axl.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicInteger;
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
}
