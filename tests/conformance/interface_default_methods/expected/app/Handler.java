package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public interface Handler<A extends @Nullable Object> {
    default @Nullable A startArray() {
        return null;
    }
    default void endArray(@Nullable A array) {
    }
    default void value(String text) {
        Nulls.param(text, "text");
        System.out.println("ignored " + text);
    }
    public static void parse(List<String> texts, Handler<?> handler) {
        Nulls.param(texts, "texts");
        Nulls.param(handler, "handler");
        handler.startArray();
        for (final var text : texts) {
            handler.value(text);
        }
    }
}
