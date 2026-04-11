package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.HashMap;

@NullMarked public final class Main {
    private Main() {}
    public enum Color { RED, GREEN, BLUE }
    public static void color(@Nullable Color c) {
        switch (c) {
            case Color.RED -> {
                System.out.println("red");
            }
            case null -> {
                System.out.println("no color");
            }
            default -> {}
        }
    }
    public static void word(@Nullable String x) {
        switch (x) {
            case "a" -> {
                System.out.println("A");
            }
            case null -> {
                System.out.println("no word");
            }
            default -> {}
        }
    }
    public static void main(String[] args) {
        final var counts = new HashMap<String, Integer>();
        counts.put("one", 1);
        final var r = switch (Nulls.box(counts.get("x"))) {
            case 1 -> "one";
            case null, default -> "other";
        };
        System.out.println(r);
        switch (Nulls.box(counts.get("one"))) {
            case 1 -> {
                System.out.println("one");
            }
            case null, default -> {
                System.out.println("other");
            }
        }
        color(null);
        color(Color.BLUE);
        color(Color.RED);
        word(null);
        word("b");
    }
}
