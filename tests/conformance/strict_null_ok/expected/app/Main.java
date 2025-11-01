package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final var text = " a ";
        final var trimmed = text.trim();
        if (trimmed != null) {
            System.out.println(trimmed.length());
        }
        System.out.println(java.util.Objects.requireNonNull(text.trim()).length());
        final var missing = System.getenv("AXL_SURELY_NOT_SET");
        System.out.println(Nulls.or(missing, () -> "unset"));
        System.out.println("" + Nulls.map(missing, $n1 -> $n1.length()));
        final var n = Integer.parseInt("5");
        System.out.println(n + 1);
    }
}
