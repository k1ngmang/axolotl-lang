package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static final class Parser {
        public final boolean partial;
        private final @Nullable Boolean quotes;
        private Parser(boolean partial, @Nullable Boolean quotes) {
            this.partial = partial;
            this.quotes = quotes;
        }
        public Parser() {
            this(true, null);
        }
        public Parser(boolean partial) {
            this(partial, null);
        }
        public String describe() {
            return "partial=" + partial + " quotes=" + quotes;
        }
        public Parser quoting(boolean q) {
            return new Parser(partial, q);
        }
    }
    public static final class Builder {
        public int count;
        @Deprecated public Builder() {
            this.count = 0;
        }
        public Builder add() {
            count += 1;
            return this;
        }
    }
    public static final class Window<T extends @Nullable Object> {
        public final List<T> items;
        public final int size;
        @Deprecated(since = "2") private Window(List<T> items, int size) {
            this.items = items;
            this.size = size;
        }
        public Window(int size) {
            this(List.of(), size);
        }
        public Window<T> grown() {
            return new Window<T>(items, size * 2);
        }
    }
    public static final class Counter {
        public int count;
        private Counter() {
            this.count = 0;
        }
        public Counter(int start) {
            this();
        }
    }
    @SuppressWarnings("deprecation") public static Builder builder() {
        return new Builder();
    }
    public static void main(String[] args) {
        System.out.println(new Parser().describe());
        System.out.println(new Parser(false).quoting(true).describe());
        System.out.println(builder().add().add().count);
        System.out.println(new Window<String>(3).grown().size);
        System.out.println(new Counter(5).count);
        System.out.println(JavaCaller.describe());
    }
}
