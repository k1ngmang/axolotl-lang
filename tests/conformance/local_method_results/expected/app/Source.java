package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.io.Reader;

@NullMarked public final class Source extends Reader {
    private final String text;
    private int pos;
    public Source(String text) {
        Nulls.param(text, "text");
        this.text = text;
        this.pos = 0;
    }
    public int read() {
        if (pos >= text.length()) {
            return -1;
        }
        final int c = text.charAt(pos);
        pos += 1;
        return c;
    }
    public int read(char[] buf, int off, int len) {
        Nulls.param(buf, "buf");
        return -1;
    }
    public void close() {
    }
}
