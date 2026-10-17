package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

@NullMarked public final class Main {
    private Main() {}
    public static String token(Reader reader) {
        Nulls.param(reader, "reader");
        final var sb = new StringBuilder();
        while (true) {
            final int c = Result.orThrow(() -> reader.read(), e -> new IllegalStateException(e), IOException.class);
            if (c == -1 || c == ',') {
                break;
            }
            sb.append(Nulls.toChar(c));
        }
        return sb.toString();
    }
    public static int code(Character c) {
        Nulls.param(c, "c");
        return c;
    }
    public static Character shifted(Character c, int by) {
        Nulls.param(c, "c");
        return Nulls.toChar(c + by);
    }
    public static void main(String[] args) {
        System.out.println(token(new StringReader("abc,def")));
        final var x = Nulls.toChar(120);
        System.out.println(x);
        System.out.println(x == 'x');
        System.out.println(code('a'));
        final int n = x;
        System.out.println(n);
        System.out.println(shifted('a', 2));
        final Result<Character, String> r = Result.attempt(() -> Nulls.toChar(-1), e -> Nulls.or(e.getMessage(), () -> "none"));
        System.out.println(r);
    }
}
