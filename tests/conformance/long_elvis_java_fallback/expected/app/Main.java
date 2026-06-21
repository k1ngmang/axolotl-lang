package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final @Nullable String a = null;
        final @Nullable String b = "b";
        System.out.println(Nulls.or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(a, () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> "z".trim()));
        System.out.println(Nulls.or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(Nulls.<@Nullable String>or(a, () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> b), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> a), () -> "z".trim()));
    }
}
