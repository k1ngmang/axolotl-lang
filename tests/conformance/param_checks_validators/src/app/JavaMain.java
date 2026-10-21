package app;

public final class JavaMain {
    public static void main(String[] args) {
        System.out.println(Lib.parse("1.0") + " " + Lib.both("a", "b") + " " + Lib.local(" c ") + " "
                + Lib.sibling("d") + Lib.imported("e") + Lib.fromJava("f") + Lib.fromJavaText("g") + " "
                + Lib.printed("h") + Lib.logged("i") + Lib.measured("j") + " " + new Lib.Range("k", "l").describe());
        expect(() -> Lib.parse(null));
        expect(() -> Lib.both(null, "b"));
        expect(() -> Lib.both("a", null));
        expect(() -> Lib.local(null));
        expect(() -> Lib.sibling(null));
        expect(() -> Lib.imported(null));
        expect(() -> Lib.fromJava(null));
        expect(() -> Lib.fromJavaText(null));
        expect(() -> Lib.printed(null));
        expect(() -> Lib.logged(null));
        expect(() -> Lib.measured(null));
        expect(() -> Lib.defaulted(null));
        expect(() -> new Lib.Range(null, "l"));
        expect(() -> new Lib.Range("k", null));
    }

    private static void expect(Runnable call) {
        try {
            call.run();
            System.out.println("no exception");
        } catch (RuntimeException e) {
            System.out.println(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
