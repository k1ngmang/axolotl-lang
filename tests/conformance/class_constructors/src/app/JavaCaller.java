package app;

/** Java creates the class with any of its public constructors, as the Java tests of commons-cli do. */
public final class JavaCaller {
    @SuppressWarnings("deprecation")
    public static String describeAll() {
        return new Opt("j", "two").describe() + " " + new Opt("k", false, "three").describe() + " "
                + new Opt("l", "long", true, "four").describe() + " " + new Opt("m").describe();
    }

    private JavaCaller() {
    }
}
