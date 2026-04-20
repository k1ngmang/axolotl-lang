package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final var words = new ArrayList<@Nullable String>();
        words.add("axolotl");
        words.add(null);
        words.add("newt");
        final List<String> present = Nulls.nonNulls(words.stream().filter(it -> it != null)).toList();
        final List<String> named = Nulls.nonNulls(words.stream().filter(w -> null != w)).toList();
        final List<Integer> lengths = Nulls.nonNulls(words.stream().filter(it -> it != null)).map(Nulls.nonNull(it -> it.length())).toList();
        final List<String> sorted = Nulls.nonNulls(words.stream().filter(it -> it != null)).sorted().toList();
        System.out.println(present);
        System.out.println(named);
        System.out.println(lengths);
        System.out.println(sorted);
    }
}
