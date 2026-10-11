package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import app.Keys.Entry;
import app.Keys.Range;

@NullMarked public final class Compare {
    private Compare() {}
    public static boolean sameKeys(Entry a, Entry b) {
        Nulls.param(a, "a");
        Nulls.param(b, "b");
        return ((int) a.key()) == ((int) b.key());
    }
    public static boolean wide(Range r) {
        Nulls.param(r, "r");
        return ((int) r.low()) != ((int) r.high());
    }
}
