package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.function.Supplier;

@NullMarked public final class Main {
    private Main() {}
    public static @Nullable Supplier<Integer> given() {
        return null;
    }
    public static void main(String[] args) {
        var n = 1;
        n += 1;
        final var $c1 = n;
        final var s = Nulls.or(given(), () -> () -> {
            final var k = $c1;
            return k;
        });
        System.out.println(s.get());
    }
}
