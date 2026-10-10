package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;

@NullMarked public final class Counter {
    public final String name;
    public int step;
    public int count;
    public final ArrayList<Integer> history;
    private final String label;
    public Counter(String name, int step) {
        Nulls.param(name, "name");
        this.name = name;
        this.step = step;
        this.count = 0;
        this.history = new ArrayList<Integer>();
        this.label = "counter " + name;
    }
    public int next() {
        count += step;
        history.add(count);
        return count;
    }
    public void reset() {
        count = 0;
        history.clear();
    }
    public String describe() {
        return label + ": " + count + " after " + history.size() + " steps";
    }
    public int twice() {
        next();
        return next();
    }
    public static Counter start(String name) {
        Nulls.param(name, "name");
        return new Counter(name, 1);
    }
}
