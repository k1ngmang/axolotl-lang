package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.io.Reader;
import java.io.StringReader;

@NullMarked public final class Main {
    private Main() {}
    public static final class Parser {
        private final Reader reader;
        private final long offset;
        private final long first;
        private final boolean track;
        private Parser(Reader reader, long offset, long first, boolean track) {
            this.reader = reader;
            this.offset = offset;
            this.first = first;
            this.track = track;
        }
        public Parser(Reader reader, long offset, long first) {
            this(Nulls.param(reader, "reader"), offset, first, false);
        }
        public Parser(Reader reader) {
            this(Nulls.param(reader, "reader"), 0L, 1L);
        }
        public long offset() {
            return offset + first;
        }
        public boolean tracking() {
            return track;
        }
        public Reader input() {
            return reader;
        }
    }
    public static final class Label {
        public final String text;
        public final int size;
        public Label(String text, int size) {
            Nulls.param(text, "text");
            this.text = text;
            this.size = size;
        }
        public Label(String text) {
            this(Nulls.param(text, "text"), text.length());
        }
        public Label(int size) {
            this("#" + size);
        }
        public Label() {
            this(3);
        }
    }
    public static void main(String[] args) {
        final var parser = new Parser(new StringReader("x"));
        System.out.println(parser.offset());
        System.out.println(parser.tracking());
        System.out.println(new Label().text + " " + new Label().size);
        System.out.println(new Label(7).text);
        System.out.println(new Label("abc").size);
        System.out.println(JavaCaller.label());
    }
}
