package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Integer, String> pick(boolean c) {
        final Result<Integer, String> r = (c ? new Ok<>(1) : new Err<>("x"));
        return r;
    }
    public static Result<Integer, String> direct(int n) {
        switch (n) {
            case 1 -> {
                return new Ok<>(1);
            }
            default -> {
                return new Err<>("x");
            }
        }
    }
    public static void main(String[] args) {
        System.out.println(pick(true));
        System.out.println(pick(false));
        System.out.println(direct(2));
    }
}
