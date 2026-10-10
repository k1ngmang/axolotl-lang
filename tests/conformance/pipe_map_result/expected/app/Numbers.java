package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Numbers {
    private Numbers() {}
    public static Result<Integer, String> parse(String text) {
        Nulls.param(text, "text");
        System.out.println("parse " + text);
        return Result.attempt(() -> Integer.parseInt(text), $ex1 -> "not a number: " + text);
    }
    public static Result<List<Integer>, String> doubled(List<String> texts) {
        Nulls.param(texts, "texts");
        final java.util.List<Integer> $p1 = new java.util.ArrayList<>();
        for (final var it : texts.stream().filter(it -> !it.isEmpty()).toList()) {
            final var $t1 = parse(it);
            if ($t1.isErr()) return Result.propagate($t1);
            $p1.add(Result.<Integer>unwrap($t1));
        }
        final var numbers = Nulls.stream($p1).map(Nulls.nonNull(it -> it * 2)).toList();
        return new Ok<>(numbers);
    }
    public static Result<List<Integer>, String> lengths(List<String> texts) {
        Nulls.param(texts, "texts");
        final java.util.List<Integer> $p1 = new java.util.ArrayList<>();
        for (final var t : texts) {
            final var $t1 = Result.attempt(() -> t.length(), $ex1 -> "never");
            if ($t1.isErr()) return Result.propagate($t1);
            $p1.add(Result.<Integer>unwrap($t1));
        }
        final List<Integer> sizes = $p1;
        return new Ok<>(sizes);
    }
    public static void main(String[] args) {
        System.out.println(doubled(List.of("1", "", "2")));
        System.out.println(doubled(List.of("3", "x", "4")));
        System.out.println(lengths(List.of("ab", "c")));
    }
}
