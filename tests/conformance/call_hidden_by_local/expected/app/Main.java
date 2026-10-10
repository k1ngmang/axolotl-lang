package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static final class Values {
        private final ArrayList<String> values = new ArrayList<String>();
        private void add(String value) {
            values.add(value);
        }
        public List<String> process(String value) {
            Nulls.param(value, "value");
            final var add = value.trim();
            add(add);
            final java.util.function.Function<String, String> wrap = label -> label(label) + label(add);
            add(wrap.apply("x"));
            return values;
        }
    }
    public static final class Recent extends ArrayList<String> {
        public int keep(String value) {
            Nulls.param(value, "value");
            final var size = value.length();
            add(value);
            return size() + size;
        }
    }
    public static String label(String text) {
        Nulls.param(text, "text");
        return "<" + text + ">";
    }
    public static void main(String[] args) {
        final var label = "top";
        System.out.println(label(label));
        System.out.println(new Values().process("  a  "));
        System.out.println(new Recent().keep("abc"));
    }
}
