package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static final class Source implements AutoCloseable {
        private final Reader input;
        private final boolean fail;
        public Source(Reader input, boolean fail) {
            Nulls.param(input, "input");
            this.input = input;
            this.fail = fail;
        }
        public int read() {
            if (fail) {
                throw Sneaky.sneaky(new IOException("boom"));
            }
            try {
                Result.mayThrow(IOException.class);
                return input.read();
            } catch (IOException e) {
                throw Sneaky.sneaky(e);
            }
        }
        public void close() {
            System.out.println("closed");
        }
    }
    public static final class Missing extends Exception {
        public Missing() {
            super("missing");
        }
    }
    public static Source source(boolean fail) {
        return new Source(new StringReader("a"), fail);
    }
    public static void skip(Source s) {
        Nulls.param(s, "s");
        try {
            Result.mayThrow(IOException.class);
            s.read();
        } catch (IOException e) {
            System.out.println("skipped: " + e.getMessage());
        }
    }
    public static int count(List<Source> sources) {
        Nulls.param(sources, "sources");
        var n = 0;
        for (final var s : sources) {
            try {
                Result.mayThrow(IOException.class);
                s.read();
            } catch (IOException e) {
                continue;
            }
            n += 1;
        }
        return n;
    }
    public static Result<Integer, String> first(Source s) {
        Nulls.param(s, "s");
        return Result.attempt(() -> s.read(), e -> "io " + e.getMessage(), IOException.class);
    }
    public static void guarded(Source s) {
        Nulls.param(s, "s");
        try (var $r1 = s) {
            Result.mayThrow(IOException.class);
            System.out.println(s.read());
        } catch (IOException e) {
            System.out.println("use caught " + e.getMessage());
        }
    }
    public static void missing() {
        try {
            Result.mayThrow(Missing.class);
            Sneaky.sneaky(new Missing());
        } catch (Missing e) {
            System.out.println("caught " + e.getMessage());
        }
    }
    public static void declared(Reader r) {
        Nulls.param(r, "r");
        try {
            Result.mayThrow(IOException.class);
            r.read();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    public static int other() {
        try {
            Result.mayThrow(IOException.class);
            Integer.parseInt("x");
        } catch (IOException e) {
            System.out.println("never");
        }
        return 0;
    }
    public static void main(String[] args) {
        skip(source(true));
        skip(source(false));
        System.out.println(count(List.of(source(false), source(true), source(false))));
        System.out.println(first(source(true)));
        guarded(source(true));
        missing();
        declared(new StringReader("z"));
        final Result<Integer, Exception> r = Result.attempt(() -> other(), e -> e);
        System.out.println(r);
    }
}
