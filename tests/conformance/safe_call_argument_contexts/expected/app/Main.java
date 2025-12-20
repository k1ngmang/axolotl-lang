package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static final @Nullable String s = " x ";
    public static final String t = String.valueOf(Nulls.typed(Nulls.map(s, $n1 -> $n1.length())).value());
    public static void main(String[] args) {
        List.of(1).stream().forEach(it -> System.out.println(Nulls.typed(Nulls.map(s, $n1 -> $n1.trim())).value()));
        final var r = Result.attempt(() -> String.valueOf(Nulls.typed(Nulls.map(s, $n2 -> $n2.length())).value()), $ex1 -> "err");
        System.out.println(r);
        System.out.println((Nulls.typed(Nulls.map(s, $n3 -> $n3.trim())).value()));
        System.out.println(java.util.Objects.requireNonNull(Nulls.typed(Nulls.map(s, $n4 -> $n4.trim())).value()));
        System.out.println(t);
    }
}
