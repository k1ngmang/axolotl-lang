package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static String log(String s) {
        System.out.println("log " + s);
        return s;
    }
    public static void main(String[] args) {
        final @Nullable String s = " a ";
        final @Nullable String none = null;
        final var $t1 = Nulls.map(s, $n1 -> $n1.trim());
        System.out.println($t1);
        final var $t2 = Nulls.map(none, $n2 -> $n2.trim());
        System.out.println($t2);
        final var sb = new StringBuilder();
        final var $t3 = Nulls.map(s, $n3 -> $n3.trim());
        final var $t4 = sb.append($t3);
        final var $t5 = Nulls.map(none, $n4 -> $n4.length());
        $t4.append($t5);
        System.out.println(sb);
        System.out.println(log("first") + Nulls.map(s, $n5 -> $n5.trim()) + log("second"));
        final var $t6 = Nulls.map(s, $n6 -> $n6.length());
        System.out.println(String.valueOf($t6));
    }
}
