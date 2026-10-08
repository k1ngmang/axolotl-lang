package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public enum Color { RED, GREEN, BLUE }
    @SuppressWarnings("EffectivelyPrivate") private enum Mode { ON, OFF }
    public static String describe(Color color) {
        if (color == Color.RED) {
            return "warm";
        }
        return "cool";
    }
    public static void main(String[] args) {
        System.out.println(describe(Color.RED) + describe(Color.BLUE));
        System.out.println(Color.valueOf("GREEN").ordinal());
        for (final var color : Color.values()) {
            System.out.print(color.name() + " ");
        }
        System.out.println(Mode.ON);
    }
}
