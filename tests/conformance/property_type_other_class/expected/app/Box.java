package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public record Box<T extends @Nullable Object>(T value, String label) {
    public Box {
        Nulls.param(label, "label");
    }
    public static final class Holder<T extends @Nullable Object> {
        private final T item;
        public Holder(T item) {
            this.item = item;
        }
        public T get() {
            return item;
        }
    }
}
