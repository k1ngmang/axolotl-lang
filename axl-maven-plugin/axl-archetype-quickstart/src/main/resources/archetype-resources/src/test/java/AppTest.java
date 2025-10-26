package ${package};

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AppTest {

    @Test
    void greetsTheWorldByDefault() {
        assertEquals("Hello, world!", App.greeting(null));
        assertEquals("Hello, axolotl!", App.greeting("axolotl"));
    }
}
