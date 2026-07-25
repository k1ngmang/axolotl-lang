package app;

import org.junit.jupiter.api.Test;

class NumbersTest {

    @Test
    void fails() {
        Numbers.number("not a number");
    }
}
