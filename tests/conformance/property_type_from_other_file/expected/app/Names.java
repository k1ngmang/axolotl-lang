package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;
import app.Model.Missing;
import app.Model.Option;
import app.Model.Some;

@NullMarked public final class Names {
    private Names() {}
    public record Tag(String label) {
        public Tag {
            Nulls.param(label, "label");
        }
    }
    public static List<String> all() {
        return List.of("ada", "bob");
    }
    public static Tag tag() {
        return new Tag("t");
    }
    public static Option<String> find(int i) {
        return (i < all().size() ? new Some<>(all().get(i)) : new Missing<>("none"));
    }
}
