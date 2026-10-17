package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public sealed interface Option<T extends @Nullable Object> {}
    public record Some<T extends @Nullable Object>(T value) implements Option<T> {}
    public record None<T extends @Nullable Object>() implements Option<T> {}
    public static Result<Integer, String> parse(String s) {
        Nulls.param(s, "s");
        return (s.isEmpty() ? new Err<>("empty") : new Ok<>(s.length()));
    }
    public static void main(String[] args) {
        final List<Result<Integer, String>> rs = List.of(parse("ab"), parse(""), parse("xyz"));
        final var values = rs.stream().filter(it -> it instanceof Ok(var it$1)).map($t1 -> (Ok<Integer, String>) $t1).map(Nulls.asGiven(it -> it.value())).toList();
        final var errors = rs.stream().filter(it -> it instanceof Err(var it$2)).map($t2 -> (Err<Integer, String>) $t2).map(Nulls.asGiven(it -> it.error())).toList();
        System.out.println(values);
        System.out.println(errors);
        final List<Option<String>> os = List.of(new Some<String>("a"), new None<String>(), new Some<String>("b"));
        System.out.println(os.stream().filter(it -> it instanceof Some(var it$3)).map($t3 -> (Some<String>) $t3).map(Nulls.nonNull(it -> it.value().toUpperCase())).toList());
    }
}
