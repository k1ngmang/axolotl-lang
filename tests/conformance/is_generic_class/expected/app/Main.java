package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static String describe(Object o) {
        return (o instanceof java.util.List o$1 ? "list" : (o instanceof java.util.Map o$2 ? "map" : "other"));
    }
    public static void main(String[] args) {
        System.out.println(describe(java.util.List.of(1)));
        System.out.println(describe(java.util.Map.of()));
        System.out.println(describe("x"));
    }
}
