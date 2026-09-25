package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;
import lib.Selector;

@NullMarked public final class Main {
    private Main() {}
    public static final class Group implements Selector {
        private @Nullable String selected = null;
        public Result<@Nullable Void, String> select(String name) {
            if (selected != null) {
                return new Err<>("already selected");
            }
            selected = name;
            return new Ok<>(null);
        }
        public int count(List<@Nullable String> names) {
            return names.size() + names.indexOf(null);
        }
    }
    public static void main(String[] args) {
        final var group = new Group();
        final var names = new ArrayList<String>();
        names.add("a");
        System.out.println(Selector.run(group, names));
        System.out.println(group.select("b"));
    }
}
