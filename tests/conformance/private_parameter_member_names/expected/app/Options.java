package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Options {
    private int limit = 10;
    public Options setLimit(int limit) {
        this.limit = limit;
        return this;
    }
    public int limit() {
        return limit;
    }
}
