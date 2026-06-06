package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;
import java.util.Optional;

@NullMarked public final class Main {
    private Main() {}
    public static Result<String, String> found(@Nullable String v) {
        return (v == null ? new Err<>("none") : new Ok<>(v));
    }
    public static Result<String, String> present(@Nullable String v) {
        return (v != null ? new Ok<>(v) : new Err<>("none"));
    }
    public static List<String> listed(@Nullable String s) {
        return (s != null ? List.of(s) : List.of());
    }
    public static List<String> both(@Nullable String s, boolean c) {
        return (s != null && c ? List.of(s) : List.of());
    }
    public static Optional<String> optional(@Nullable String s) {
        return (s == null ? Optional.empty() : Optional.of(s));
    }
    public static Result<Boolean, String> flag(@Nullable Boolean b) {
        return (b == null ? new Err<>("none") : new Ok<>(b));
    }
    public static Result<Integer, String> number(@Nullable Integer n) {
        return (n == null ? new Err<>("none") : new Ok<>(n));
    }
    public static List<String> arms(@Nullable String s) {
        switch (s) {
            case null -> {
                return List.of();
            }
            default -> {
                return List.of(s);
            }
        }
    }
    public static void main(String[] args) {
        System.out.println(found("a"));
        System.out.println(found(null));
        System.out.println(present("b"));
        System.out.println(listed("c"));
        System.out.println(both("d", true));
        System.out.println(optional("e"));
        System.out.println(flag(true));
        System.out.println(number(3));
        final Result<String, String> r = (System.nanoTime() > 0 ? found("f") : new Err<>("never"));
        System.out.println(r);
        System.out.println(arms("g"));
        System.out.println(arms(null));
    }
}
