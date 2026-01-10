package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@NullMarked public final class Main {
    private Main() {}
    public static @Nullable String maybe() {
        return null;
    }
    public static void main(String[] args) {
        final var xs = new ArrayList<@Nullable String>();
        xs.add("ab");
        xs.add(null);
        System.out.println(xs.stream().filter(Objects::nonNull).map(s -> s.length()).toList());
        final List<@Nullable String> both = Arrays.asList("a", maybe());
        final List<@Nullable String> one = Collections.singletonList(maybe());
        System.out.println(both);
        System.out.println(one);
        final var o = Optional.ofNullable(maybe());
        System.out.println(o.isPresent());
        final var m = new HashMap<String, String>();
        m.put("k", "v");
        final @Nullable String v = m.getOrDefault("x", null);
        System.out.println(v);
        final var e = m.entrySet().iterator().next();
        System.out.println(e.getValue().length());
    }
}
