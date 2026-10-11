package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Counter {
    private final int step;
    private final int max;
    private final int by;
    public Counter(int limit, int step) {
        this.step = step;
        this.max = limit * 2;
        this.by = step;
    }
    public int max() {
        return max;
    }
    public boolean sameStep(Counter other) {
        Nulls.param(other, "other");
        return other.step == by;
    }
}
