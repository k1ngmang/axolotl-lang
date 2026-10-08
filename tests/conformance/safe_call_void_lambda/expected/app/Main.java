package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

@NullMarked public final class Main {
    private Main() {}
    public interface Listener {
        void step(int value);
    }
    public static final class Recorder implements Listener {
        public final List<Integer> seen = new ArrayList<Integer>();
        @Override public void step(int value) {
            seen.add(value);
        }
    }
    public static void run(Consumer<Integer> steps) {
        steps.accept(1);
        steps.accept(2);
    }
    public static void report(@Nullable Listener progress) {
        final var $n1 = progress;
        if ($n1 != null) {
            $n1.step(0);
        }
        run(idx -> {
            final var $n2 = progress;
            if ($n2 != null) {
                $n2.step(idx);
            }
        });
    }
    public static void clearAll(@Nullable List<String> items) {
        run(idx -> {
            final var $n1 = items;
            if ($n1 != null) {
                $n1.clear();
            }
        });
    }
    public static Function<String, @Nullable StringBuilder> appender(@Nullable StringBuilder sb) {
        return s -> Nulls.map(sb, $n1 -> $n1.append(s));
    }
    public static void main(String[] args) {
        final var recorder = new Recorder();
        report(recorder);
        report(null);
        System.out.println(recorder.seen);
        final var items = new ArrayList<String>(List.of("a"));
        clearAll(items);
        clearAll(null);
        System.out.println(items.size());
        final var sb = new StringBuilder();
        run(i -> Nulls.map(sb, $n1 -> $n1.append(i)));
        System.out.println(appender(sb).apply("x"));
        System.out.println(appender(null).apply("x"));
    }
}
