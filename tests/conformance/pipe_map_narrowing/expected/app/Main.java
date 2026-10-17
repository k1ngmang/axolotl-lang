package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Integer, String> parse(String s) {
        Nulls.param(s, "s");
        return Result.attempt(() -> Integer.parseInt(s), e -> "bad " + s);
    }
    public static void main(String[] args) {
        final var parsed = List.of("1", "x", "3").stream().<Result<Integer, String>>map(Nulls.asGiven(it -> parse(it))).toList();
        System.out.println(parsed.stream().filter(it -> it instanceof Ok(var it$1)).map($t1 -> (Ok<Integer, String>) $t1).map(Nulls.nonNull(it -> it.value())).toList());
        System.out.println(parsed.stream().filter(it -> it instanceof Err(var it$2)).map($t2 -> (Err<Integer, String>) $t2).map(Nulls.nonNull(it -> it.error())).toList());
        System.out.println(List.of("4", "y").stream().<Result<Integer, String>>map(Nulls.asGiven(it -> parse(it))).filter(it -> it instanceof Ok(var it$3)).map($t3 -> (Ok<Integer, String>) $t3).map(Nulls.nonNull(it -> it.value() + 1)).toList());
    }
}
