package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static boolean same(String a, @Nullable String b) {
        return a.equals(b);
    }
    public static void main(String[] args) {
        System.out.println(same("x", null));
        System.out.println(same("x", "x"));
    }
}
