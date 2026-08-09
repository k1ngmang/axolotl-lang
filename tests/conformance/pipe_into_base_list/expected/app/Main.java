package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public sealed interface Token {}
    public record Num(int n) implements Token {}
    public record Word(String w) implements Token {}
    public static List<Token> numbers(List<Token> tokens) {
        return tokens.stream().filter(it -> it instanceof Num it$1).toList();
    }
    public static void main(String[] args) {
        final List<Token> tokens = List.of(new Num(1), new Word("a"), new Num(2));
        final List<Token> kept = tokens.stream().filter(it -> it instanceof Num it$1).toList();
        final var nums = tokens.stream().filter(it -> it instanceof Num it$2).<Num>map(Num.class::cast).toList();
        System.out.println(kept.size());
        System.out.println(numbers(tokens));
        System.out.println(nums.getFirst().n() + nums.getLast().n());
    }
}
