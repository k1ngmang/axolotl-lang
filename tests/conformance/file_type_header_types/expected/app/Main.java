package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import app.Holder.Item;
import app.Pair.Part;
import app.Repo.Key;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static final class Keys implements Repo<Key> {
        @Override public @Nullable Key find(Key key) {
            Nulls.param(key, "key");
            return (key == Key.A ? key : null);
        }
        public Key get() {
            return Key.B;
        }
    }
    public static void main(String[] args) {
        for (final var member : new Bag(List.of("a", "b"))) {
            System.out.println(member.name);
        }
        final var holder = new Holder<Item>(null);
        holder.add(new Item(3));
        final var $t1 = Nulls.map(holder.get(), $n1 -> $n1.n);
        System.out.println($t1);
        System.out.println(new Pair<Part>(new Part(1), null).get().n());
        System.out.println(new Keys().find(Key.A));
        System.out.println(Level.HIGH.get().text());
    }
}
