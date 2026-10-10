package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

@NullMarked public final class Main {
    private Main() {}
    public static int copy(InputStream input, OutputStream out, byte[] buffer) {
        Nulls.param(input, "input");
        Nulls.param(out, "out");
        Nulls.param(buffer, "buffer");
        var total = 0;
        var n = Result.orThrow(() -> input.read(buffer), e -> new IllegalStateException(e));
        while (n > 0) {
            final var $c1 = n;
            try {
                out.write(buffer, 0, $c1);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            total += n;
            n = Result.orThrow(() -> input.read(buffer), e -> new IllegalStateException(e));
        }
        return total;
    }
    public static String read(String text) {
        Nulls.param(text, "text");
        final var reader = new StringReader(text);
        final var chars = new char[4];
        final var out = new StringBuilder();
        var n = Result.orThrow(() -> reader.read(chars), e -> new IllegalStateException(e));
        while (n > 0) {
            out.append(chars, 0, n);
            n = Result.orThrow(() -> reader.read(chars), e -> new IllegalStateException(e));
        }
        return out.toString();
    }
    public static byte[] bytes(String text) {
        Nulls.param(text, "text");
        return text.getBytes(StandardCharsets.UTF_8);
    }
    public static void main(String[] args) {
        final var out = new ByteArrayOutputStream();
        System.out.println(copy(new ByteArrayInputStream(bytes("hello world")), out, new byte[4]));
        System.out.println(out.toString(StandardCharsets.UTF_8));
        System.out.println(read("abcdefghij"));
        final var buffer = new byte[3];
        System.out.println(buffer.length);
        for (final var b : bytes("AB")) {
            System.out.println(b);
        }
    }
}
