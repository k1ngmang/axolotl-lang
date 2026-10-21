package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import static app.Texts.print;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static final class Lines {
        private final BufferedReader reader;
        public Lines(BufferedReader reader) {
            Nulls.param(reader, "reader");
            this.reader = reader;
        }
        private @Nullable String readLine() {
            try {
                Result.mayThrow(IOException.class);
                return reader.readLine();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        public @Nullable String first() {
            return readLine();
        }
    }
    public static final class Log {
        public final List<String> lines = new ArrayList<String>();
        private void println(String text) {
            lines.add(text);
        }
        public int info(String text) {
            Nulls.param(text, "text");
            println("info " + text);
            final java.util.function.Consumer<String> each = t -> println(t);
            each.accept("more " + text);
            return lines.size();
        }
    }
    public static final class Capture extends PrintStream {
        public final ByteArrayOutputStream bytes;
        public Capture(ByteArrayOutputStream bytes) {
            super(Nulls.param(bytes, "bytes"));
            this.bytes = bytes;
        }
        public String hello() {
            println("captured");
            flush();
            return bytes.toString(StandardCharsets.UTF_8).trim();
        }
    }
    public static void main(String[] args) {
        System.out.println(new Lines(new BufferedReader(new StringReader("a\nb"))).first());
        System.out.println(new Log().info("x"));
        System.out.println(new Capture(new ByteArrayOutputStream()).hello());
        print("done");
    }
}
