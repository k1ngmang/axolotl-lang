package app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import axl.lang.Err;
import axl.lang.Ok;
import org.junit.jupiter.api.Test;

class TemperaturesTest {

    @Test
    void convertsFahrenheit() {
        assertEquals(new Ok<>(100.0), Temperatures.celsius(Samples.boiling));
        assertEquals(new Ok<>(0.0), Temperatures.celsius(Samples.freezing()));
    }

    @Test
    void reportsBadInput() {
        assertEquals(new Err<>("not a temperature: x"), Temperatures.celsius("x"));
        assertEquals(new Err<>("not a temperature: "), Temperatures.celsius(null));
    }

    @Test
    void javaSeesAxolotlResults() {
        assertEquals("100.0 C", Labels.forText("212"));
    }
}
