package app;

/** Java code outside the class cannot reach the private constructor either. */
final class Caller {
    static Main.Parser make() {
        return new Main.Parser(false, Boolean.TRUE);
    }

    private Caller() {
    }
}
