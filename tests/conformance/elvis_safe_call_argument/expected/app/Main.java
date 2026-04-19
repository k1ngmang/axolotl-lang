package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final @Nullable String x = null;
        System.out.println(Nulls.present(Nulls.or(Nulls.map(x, $n1 -> $n1.length()), () -> "none")).value());
        final var sb = new StringBuilder();
        sb.append(Nulls.typed(Nulls.or(Nulls.map(x, $n2 -> $n2.trim()), () -> Nulls.map(x, $n3 -> $n3.length()))).value());
        System.out.println(sb);
        final @Nullable String y = " ab ";
        System.out.println(Nulls.present(Nulls.or(Nulls.map(y, $n4 -> $n4.length()), () -> "none")).value());
    }
}
