package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

@NullMarked public final class Main {
    private Main() {}
    public static final int EOF = -1;
    public interface Ticks {
        int next();
    }
    public record Cell(String text) {
        public Cell {
            Nulls.param(text, "text");
        }
        public Character first() {
            return text.charAt(0);
        }
    }
    public static final class Lexer {
        private final Source source;
        private final Ticks ticks;
        public Lexer(Source source, Ticks ticks) {
            Nulls.param(source, "source");
            Nulls.param(ticks, "ticks");
            this.source = source;
            this.ticks = ticks;
        }
        public String kind() {
            final var ch = source.read();
            switch (ch) {
                case 'a' -> {
                    return "a";
                }
                case EOF -> {
                    return "end";
                }
                default -> {
                    return "other";
                }
            }
        }
        public String tick() {
            final var t = ticks.next();
            switch (t) {
                case 1 -> {
                    return "one";
                }
                default -> {
                    return "many";
                }
            }
        }
        public boolean sameStart(Cell cell, Cell other) {
            Nulls.param(cell, "cell");
            Nulls.param(other, "other");
            final var a = cell.first();
            final var b = other.first();
            return Nulls.same(a, b);
        }
    }
    public static final class Format {
        private final @Nullable Character escapeChar;
        public Format(@Nullable Character escapeChar) {
            this.escapeChar = escapeChar;
        }
        public Character getEscape() {
            return Nulls.or(escapeChar, () -> '\\');
        }
        public boolean isEscape(String s) {
            Nulls.param(s, "s");
            final Character c = s.charAt(0);
            final var esc = getEscape();
            final var again = this.getEscape();
            return Nulls.same(c, esc) && Nulls.same(esc, again);
        }
    }
    public static String position(String text) {
        Nulls.param(text, "text");
        final var sb = new StringBuilder(text);
        final var at = sb.indexOf("b");
        switch (at) {
            case EOF -> {
                return "none";
            }
            default -> {
                return "at " + at;
            }
        }
    }
    public static int countQuotes(Reader reader, Character quote) {
        Nulls.param(reader, "reader");
        Nulls.param(quote, "quote");
        var count = 0;
        while (true) {
            final int c = Result.orThrow(() -> reader.read(), e -> new IllegalStateException(e), IOException.class);
            if (c == -1) {
                break;
            }
            if (c == quote) {
                count += 1;
            }
        }
        return count;
    }
    public static String firstKind(Reader reader) {
        Nulls.param(reader, "reader");
        final int ch = Result.orThrow(() -> reader.read(), e -> new IllegalStateException(e), IOException.class);
        switch (ch) {
            case 'x' -> {
                return "x";
            }
            case EOF -> {
                return "end";
            }
            default -> {
                return "other";
            }
        }
    }
    public static final int ZERO = 0;
    public static String remainder(int n) {
        switch (Nulls.box(Math.floorMod(n, 3))) {
            case ZERO -> {
                return "none";
            }
            case 1 -> {
                return "one";
            }
            case null, default -> {
                return "two";
            }
        }
    }
    public static final class Counter implements Ticks {
        private int n = 0;
        @Override public int next() {
            n += 1;
            return n;
        }
    }
    public static void main(String[] args) {
        System.out.println(new Lexer(new Source("a"), new Counter()).kind());
        System.out.println(new Lexer(new Source(""), new Counter()).kind());
        final var lexer = new Lexer(new Source("b"), new Counter());
        System.out.println(lexer.kind());
        System.out.println(lexer.tick());
        System.out.println(lexer.tick());
        System.out.println(lexer.sameStart(new Cell("x1"), new Cell("x2")));
        System.out.println(lexer.sameStart(new Cell("x1"), new Cell("y2")));
        System.out.println(new Format(null).isEscape("\\"));
        System.out.println(new Format('!').isEscape("\\"));
        System.out.println(position("abc"));
        System.out.println(position("x"));
        System.out.println(countQuotes(new StringReader("a\"b\""), '"'));
        System.out.println(firstKind(new StringReader("x")) + " " + firstKind(new StringReader("")));
        System.out.println(remainder(6) + " " + remainder(7) + " " + remainder(-1));
    }
}
