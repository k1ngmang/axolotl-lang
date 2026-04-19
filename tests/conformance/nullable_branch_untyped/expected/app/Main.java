package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static @Nullable Integer next(boolean c, @Nullable Integer n, int k) {
        return (c ? n : Nulls.box(k + 1));
    }
    public static @Nullable Integer size(boolean c, @Nullable Integer n, String s) {
        return (c ? n : Nulls.box(s.length()));
    }
    public static @Nullable Integer nested(boolean c, @Nullable Integer n) {
        return (c ? n : Nulls.box((!c ? 42 : 7)));
    }
    public static void chosen(int c, @Nullable Integer n, int k) {
        final var r = switch (c) {
            case 1 -> n;
            default -> Nulls.box(k + 1);
        };
        System.out.println(r);
    }
    public static void main(String[] args) {
        System.out.println(next(true, null, 1));
        System.out.println(next(false, null, 1));
        System.out.println(size(true, null, "abc"));
        System.out.println(size(false, null, "abc"));
        System.out.println(nested(true, null));
        chosen(1, null, 1);
        chosen(2, null, 1);
    }
}
