package lib;

import java.util.Arrays;
import java.util.List;

public class Legacy {
    public static List<String> names() { return Arrays.asList("a", null); }

    public static class Holder<T> {
        public T value;

        public T get() { return value; }
    }
}
