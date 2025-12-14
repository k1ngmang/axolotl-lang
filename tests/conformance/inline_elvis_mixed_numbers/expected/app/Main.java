package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static @Nullable Integer maybeInt() {
        return null;
    }
    public static @Nullable Long maybeLong() {
        return null;
    }
    public static void main(String[] args) {
        final var a = Nulls.or(maybeInt(), () -> maybeLong());
        System.out.println(a);
    }
}
