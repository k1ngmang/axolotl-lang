package app;

/** Java reaches the class's parameters through the chain of constructors too. */
public final class JavaCaller {
    public static String label() {
        return new Main.Label(12).text + new Main.Parser(new java.io.StringReader("y"), 2L, 3L).offset();
    }

    private JavaCaller() {
    }
}
