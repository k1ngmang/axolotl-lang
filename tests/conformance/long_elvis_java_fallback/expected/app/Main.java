package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final @Nullable String a = null;
        final @Nullable String b = "b";
        System.out.println(Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> "z".trim())))))))))))))))))))))))));
        System.out.println(Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(b, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> Nulls.<String>or(a, () -> "z".trim()))))))))))))))))))))));
    }
}
