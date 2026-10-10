package util;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Strings {
    private Strings() {}
    public static final int limit = 3;
    public static String shout(String s) {
        Nulls.param(s, "s");
        return s.toUpperCase() + "!";
    }
}
