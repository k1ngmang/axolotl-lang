package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

@NullMarked public final class Main {
    private Main() {}
    public static final class CountingReader extends BufferedReader {
        private long lines;
        public CountingReader(Reader input) {
            super(Nulls.param(input, "input"));
            this.lines = 0L;
        }
        public int read() {
            final int c = Result.orThrow(() -> super.read(), e -> new IllegalStateException(e), IOException.class);
            if (c == '\n') {
                lines += 1;
            }
            return c;
        }
        public boolean markSupported() {
            return super.markSupported();
        }
        public long lineCount() {
            return lines;
        }
    }
    public static final class Recent extends LinkedHashMap<String, Integer> {
        public final int limit;
        public Recent(int limit) {
            this.limit = limit;
        }
        public boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            Nulls.param(eldest, "eldest");
            return size() > limit || super.removeEldestEntry(eldest);
        }
        public String toString() {
            return "recent " + super.toString();
        }
    }
    public static void main(String[] args) {
        final var r = new CountingReader(new StringReader("a\nb\n"));
        while (r.read() >= 0) {
        }
        System.out.println(r.lineCount());
        System.out.println(r.markSupported());
        final var recent = new Recent(2);
        recent.put("a", 1);
        recent.put("b", 2);
        recent.put("c", 3);
        System.out.println(recent);
    }
}
