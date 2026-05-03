package lib;

import java.util.function.Consumer;
import java.util.function.Function;

public class Legacy {
    public static void call(Consumer<String> c) { c.accept(null); }

    public static int apply(Function<String, Integer> f) { return f.apply(null); }
}
