package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.function.Supplier;

@NullMarked public interface Repo<T extends Comparable<Repo.Key>> extends Supplier<Repo.Key> {
    @Nullable T find(Key key);
    public enum Key { A, B }
}
