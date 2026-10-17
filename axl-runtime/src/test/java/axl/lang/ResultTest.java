package axl.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ResultTest {

    @Test
    void attemptWrapsValueInOk() {
        Result<Integer, String> r = Result.attempt(() -> 42, Exception::getMessage);
        assertEquals(new Ok<>(42), r);
    }

    @Test
    void attemptCatchesCheckedAndUncheckedExceptions() {
        Result<Integer, String> checked = Result.attempt(() -> { throw new java.io.IOException("io"); }, Exception::getMessage);
        Result<Integer, String> unchecked = Result.attempt(() -> Integer.parseInt("x"), e -> e.getClass().getSimpleName());
        assertEquals(new Err<>("io"), checked);
        assertEquals(new Err<>("NumberFormatException"), unchecked);
    }

    @Test
    void mayThrowOnlyLetsACatchOfTheClassCompile() throws java.io.IOException {
        String caught = "none";
        try {
            Result.mayThrow(java.io.IOException.class);
        } catch (java.io.IOException e) {
            caught = "io";
        }
        assertEquals("none", caught);
    }

    @Test
    void attemptDoesNotCatchErrors() {
        assertThrows(StackOverflowError.class,
                () -> Result.attempt(() -> { throw new StackOverflowError(); }, Exception::getMessage));
    }

    @Test
    void attemptKeepsTheInterrupt() {
        Thread.currentThread().interrupt();
        Result<Integer, String> slept = Result.attempt(() -> { Thread.sleep(10_000L); return 1; }, Exception::getMessage);
        assertTrue(slept.isErr());
        assertTrue(Thread.interrupted());
        Thread.currentThread().interrupt();
        assertThrows(IllegalStateException.class,
                () -> Result.orThrow(() -> { Thread.sleep(10_000L); return 1; }, IllegalStateException::new));
        assertTrue(Thread.interrupted());
    }

    @Test
    void attemptOfAClassCatchesOnlyThatClass() {
        Result<Integer, String> io = Result.attempt(() -> { throw new java.io.FileNotFoundException("f"); }, e -> "io " + e.getMessage(),
                java.io.IOException.class);
        assertEquals(new Err<>("io f"), io);
        assertThrows(NumberFormatException.class,
                () -> Result.attempt(() -> Integer.parseInt("x"), e -> "io", java.io.IOException.class));
        assertEquals(new Ok<>(3), Result.attempt(() -> 3, java.io.IOException::getMessage, java.io.IOException.class));
        assertThrows(java.io.UncheckedIOException.class, () -> Result.orThrow(() -> { throw new java.io.IOException("io"); },
                java.io.UncheckedIOException::new, java.io.IOException.class));
        assertThrows(IllegalArgumentException.class, () -> Result.orThrow(() -> { throw new IllegalArgumentException(); },
                java.io.UncheckedIOException::new, java.io.IOException.class));
        Thread.currentThread().interrupt();
        Result<Integer, String> slept = Result.attempt(() -> { Thread.sleep(10_000L); return 1; }, Exception::getMessage,
                InterruptedException.class);
        assertTrue(slept.isErr());
        assertTrue(Thread.interrupted());
    }

    @Test
    void unwrapOnErrThrows() {
        assertThrows(IllegalStateException.class, () -> Result.unwrap(new Err<Integer, String>("e")));
    }

    @Test
    void propagateOnOkThrows() {
        assertThrows(IllegalStateException.class, () -> Result.propagate(new Ok<Integer, String>(1)));
    }

    @Test
    void propagateKeepsErrorWithNewValueType() {
        Result<String, String> r = Result.propagate(new Err<Integer, String>("e"));
        assertTrue(r.isErr());
        assertEquals(new Err<>("e"), r);
    }

    @Test
    void propagateWidensErrorType() {
        Result<String, CharSequence> r = Result.propagate(new Err<Integer, String>("e"));
        assertEquals(new Err<>("e"), r);
    }

    @Test
    void exhaustiveSwitchNeedsNoDefault() {
        assertEquals("ok 1", describe(new Ok<>(1)));
        assertEquals("fail x", describe(new Err<>("x")));
    }

    private static String describe(Result<Integer, String> r) {
        return switch (r) {
            case Ok<Integer, String>(var v) -> "ok " + v;
            case Err<Integer, String>(var e) -> "fail " + e;
        };
    }
}
