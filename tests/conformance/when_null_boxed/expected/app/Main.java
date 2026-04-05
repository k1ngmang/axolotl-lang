package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static void describe(@Nullable Double d) {
        final var $e1 = d;
        switch ($e1 == null ? 0 : -1) {
            case 0 -> {
                System.out.println("nil");
            }
            default -> {
                System.out.println("value " + d);
            }
        }
    }
    public static String size(@Nullable Long n) {
        final var $e1 = n;
        switch ($e1 == null ? 1 : $e1 == 1000L ? 0 : -1) {
            case 0 -> {
                return "k";
            }
            case 1 -> {
                return "nil";
            }
            default -> {
                return "other";
            }
        }
    }
    public static String either(@Nullable Long n) {
        final var $e1 = n;
        switch ($e1 == null ? 0 : $e1 == 5L ? 0 : -1) {
            case 0 -> {
                return "five or nil";
            }
            default -> {
                return "other";
            }
        }
    }
    public static void flag(@Nullable Boolean x) {
        final var $e1 = x;
        switch ($e1 == null ? 2 : $e1 == true ? 0 : $e1 == false ? 1 : -1) {
            case 0 -> {
                System.out.println("T");
            }
            case 1 -> {
                System.out.println("F");
            }
            case 2 -> {
                System.out.println("N");
            }
        }
    }
    public static void main(String[] args) {
        describe(null);
        describe(1.5);
        System.out.println(size(null));
        System.out.println(size(1000L));
        System.out.println(either(null));
        System.out.println(either(5L));
        System.out.println(either(6L));
        flag(null);
        flag(true);
    }
}
