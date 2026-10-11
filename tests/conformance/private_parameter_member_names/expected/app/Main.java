package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static final class Range {
        public final int limit;
        public Range(int limit) {
            this.limit = limit;
        }
    }
    public static int widest(Options options, Range range) {
        Nulls.param(options, "options");
        Nulls.param(range, "range");
        return Math.max(options.limit(), range.limit);
    }
    public static void main(String[] args) {
        System.out.println(new Counter(3, 1).max());
        System.out.println(new Counter(3, 1).sameStep(new Counter(5, 1)));
        System.out.println(new Options().setLimit(4).limit());
        System.out.println(widest(new Options(), new Range(12)));
    }
}
