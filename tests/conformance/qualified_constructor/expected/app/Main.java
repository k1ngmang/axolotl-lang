package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final var t = new java.sql.Timestamp(1000L);
        final var sb = new java.lang.StringBuilder("x");
        sb.append(t.getTime());
        System.out.println(sb);
    }
}
