package util;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Strings {
    private Strings() {}
    public static String shout(String s) {
        Nulls.param(s, "s");
        return s.toUpperCase() + "!";
    }
    public static final String suffix = "!";
}
