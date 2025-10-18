package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Casts {
    private Casts() {}
    public static int inIf(@Nullable String s) {
        if (s != null) {
            return s.length();
        }
        return -1;
    }
    public static int earlyReturn(@Nullable String s) {
        if (s == null) {
            return 0;
        }
        return s.length();
    }
    public static boolean conjunction(@Nullable String s) {
        return s != null && s.length() > 2;
    }
    public static boolean disjunction(@Nullable String s) {
        return s == null || s.isEmpty();
    }
    public static int local(@Nullable String input) {
        final var copy = input;
        if (copy == null) {
            return 0;
        }
        return copy.length();
    }
    public static void main(String[] args) {
        System.out.println(inIf("ab"));
        System.out.println(inIf(null));
        System.out.println(earlyReturn("abc"));
        System.out.println(conjunction("abc"));
        System.out.println(disjunction(null));
        System.out.println(local("abcd"));
    }
}
