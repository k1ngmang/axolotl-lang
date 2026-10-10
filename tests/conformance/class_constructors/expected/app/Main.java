package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static final class Counter {
        public int count;
        public Counter() {
            this.count = 0;
        }
        public Counter(int start) {
            this();
        }
    }
    public static final class Stack<T extends @Nullable Object> {
        public final List<T> items;
        public final int limit;
        public Stack(List<T> items, int limit) {
            Nulls.param(items, "items");
            this.items = items;
            this.limit = limit;
        }
        public Stack(int limit) {
            this(new ArrayList<T>(), limit);
        }
        public Stack() {
            this(new ArrayList<T>(), Integer.MAX_VALUE);
        }
    }
    public static final class Failure extends RuntimeException {
        public final int code;
        public final String reason;
        public Failure(int code, String reason) {
            super(Nulls.param(reason, "reason"));
            this.code = code;
            this.reason = reason;
        }
        public Failure(String reason) {
            this(500, Nulls.param(reason, "reason"));
        }
        public Failure(long code) {
            this(Math.toIntExact(code), (code > 0L ? "code " + code : "none"));
        }
    }
    public static void main(String[] args) {
        System.out.println(new Opt("a", "first").describe());
        System.out.println(new Opt("b", true, "second").describe());
        System.out.println(new Opt("c", "long", true, "third").describe());
        System.out.println(new Opt("d").describe());
        System.out.println(new Opt("e", "x").blank().describe());
        System.out.println(new Counter(3).count + new Counter().count);
        System.out.println(new Stack<String>(4).limit);
        System.out.println(new Stack<String>().items.size());
        System.out.println(new Failure("broken").code);
        System.out.println(new Failure(7L).getMessage());
        System.out.println(JavaCaller.describeAll());
    }
}
